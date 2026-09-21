package me.rerere.rikkahub.data.workspace

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The result of inspecting only the outer archive envelope. */
enum class WorkspaceArchiveEnvelope {
    ENCRYPTED,
    LEGACY,
}

data class WorkspaceArchiveInspection(
    val envelope: WorkspaceArchiveEnvelope,
    val version: Int? = null,
) {
    val isEncrypted: Boolean
        get() = envelope == WorkspaceArchiveEnvelope.ENCRYPTED

    val isLegacy: Boolean
        get() = envelope == WorkspaceArchiveEnvelope.LEGACY
}

/**
 * Only a plaintext archive that predates the portable manifest still needs the legacy warning.
 * An encrypted envelope authenticates itself with its GCM tag, and a current plaintext archive
 * (no magic, portable manifest present) is an ordinary backup produced by this same version.
 */
internal fun WorkspaceArchiveInspection.requiresLegacyConfirmation(
    hasPortableManifest: Boolean,
): Boolean = isLegacy && !hasPortableManifest

/** Raised when the selected archive is an encrypted legacy one and no password was supplied. */
class WorkspaceArchivePasswordRequiredException : IllegalStateException(
    "This backup is an encrypted legacy archive and requires its password",
)

/** Raised when an old, unauthenticated ZIP needs an explicit user decision before restore. */
class LegacyWorkspaceArchiveRequiresConfirmationException : IllegalStateException(
    "This backup is a legacy unencrypted archive and requires explicit confirmation before restore",
)

/** Raised for malformed, truncated, tampered, or incorrectly decrypted encrypted archives. */
class WorkspaceArchiveAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Read-only support for the AES-256-GCM envelope that older versions used to write. Nothing in
 * this app produces that format any more: backups are plaintext ZIPs, and this code exists so that
 * previously exported `.zip.enc` files can still be authenticated and restored.
 */
internal object WorkspaceArchiveEncryption {
    private const val VERSION = 1
    private const val ALGORITHM_ID = 1 // AES-256-GCM with PBKDF2-HMAC-SHA256
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val TAG_BYTES = TAG_BITS / 8
    private const val MIN_PBKDF2_ITERATIONS = 10_000
    private const val MAX_PBKDF2_ITERATIONS = 1_000_000
    private const val BUFFER_SIZE = 64 * 1024

    /** Eight bytes make accidental legacy ZIP detection unambiguous. */
    val MAGIC: ByteArray = byteArrayOf(
        'H'.code.toByte(),
        'M'.code.toByte(),
        'A'.code.toByte(),
        'E'.code.toByte(),
        'N'.code.toByte(),
        'C'.code.toByte(),
        '0'.code.toByte(),
        '1'.code.toByte(),
    )

    const val MIN_PASSWORD_LENGTH = 8

    private const val PREFIX_BYTES = 8 + 1 + 1 + 4 + 1 + 1

    fun requirePassword(password: CharArray) {
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "Backup password must contain at least $MIN_PASSWORD_LENGTH characters"
        }
    }

    fun inspect(file: File): WorkspaceArchiveInspection {
        requireRegularFile(file)
        FileInputStream(file).use { input ->
            val prefix = ByteArray(MAGIC.size)
            val read = readUpTo(input, prefix)
            if (read < MAGIC.size) {
                if (read > 0 && MAGIC.copyOf(read).contentEquals(prefix.copyOf(read))) {
                    throw WorkspaceArchiveAuthenticationException(
                        "Encrypted workspace archive header is truncated",
                    )
                }
                return WorkspaceArchiveInspection(WorkspaceArchiveEnvelope.LEGACY)
            }
            if (!prefix.contentEquals(MAGIC)) {
                return WorkspaceArchiveInspection(WorkspaceArchiveEnvelope.LEGACY)
            }
            val header = try {
                readHeader(input, file.length(), prefix)
            } catch (error: Throwable) {
                throw authenticationFailure(error)
            }
            return WorkspaceArchiveInspection(
                envelope = WorkspaceArchiveEnvelope.ENCRYPTED,
                version = header.version,
            )
        }
    }

    /**
     * Authenticates and decrypts [source] into [target]. No caller may read [target] until this
     * method returns successfully; on every authentication/IO failure it is removed.
     */
    fun decrypt(
        source: File,
        target: File,
        password: CharArray,
        onOutputBytes: (Long) -> Unit = {},
    ) {
        requirePassword(password)
        requireRegularFile(source)
        prepareOutput(target)
        var succeeded = false
        try {
            FileInputStream(source).use { rawInput ->
                val input = BufferedInputStream(rawInput)
                val header = readHeader(input, source.length())
                val ciphertextBytes = source.length() - header.size
                require(ciphertextBytes >= TAG_BYTES) {
                    "Encrypted workspace archive is truncated"
                }
                val keyBytes = deriveKey(password, header.salt, header.iterations)
                try {
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(keyBytes.copyOf(), "AES"),
                        GCMParameterSpec(TAG_BITS, header.nonce),
                    )
                    cipher.updateAAD(header.bytes)
                    FileOutputStream(target).use { rawOutput ->
                        BufferedOutputStream(rawOutput).use { output ->
                            CipherInputStream(input, cipher).use { decrypted ->
                                val buffer = ByteArray(BUFFER_SIZE)
                                while (true) {
                                    val read = decrypted.read(buffer)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    output.write(buffer, 0, read)
                                    onOutputBytes(read.toLong())
                                }
                            }
                            output.flush()
                            rawOutput.fd.sync()
                        }
                    }
                } finally {
                    keyBytes.fill(0)
                }
            }
            succeeded = true
        } catch (error: Throwable) {
            throw authenticationFailure(error)
        } finally {
            if (!succeeded) {
                deleteTargetAfterFailure(target)
            }
        }
    }

    private fun readHeader(
        input: InputStream,
        fileLength: Long,
        knownMagic: ByteArray? = null,
    ): Header {
        val prefix = if (knownMagic != null) {
            knownMagic + readExact(input, PREFIX_BYTES - MAGIC.size)
        } else {
            readExact(input, PREFIX_BYTES)
        }
        val buffer = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size)
        buffer.get(magic)
        require(magic.contentEquals(MAGIC)) {
            "Not a versioned encrypted workspace archive"
        }
        val version = buffer.get().toInt() and 0xff
        require(version == VERSION) {
            "Unsupported encrypted workspace archive version: $version"
        }
        val algorithm = buffer.get().toInt() and 0xff
        require(algorithm == ALGORITHM_ID) {
            "Unsupported encrypted workspace archive algorithm: $algorithm"
        }
        val iterations = buffer.int
        require(iterations in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) {
            "Invalid encrypted workspace archive KDF parameters"
        }
        val saltLength = buffer.get().toInt() and 0xff
        val nonceLength = buffer.get().toInt() and 0xff
        require(saltLength == SALT_BYTES && nonceLength == NONCE_BYTES) {
            "Invalid encrypted workspace archive nonce or salt length"
        }
        val salt = readExact(input, saltLength)
        val nonce = readExact(input, nonceLength)
        val bytes = prefix + salt + nonce
        require(fileLength >= bytes.size.toLong() + TAG_BYTES) {
            "Encrypted workspace archive is truncated"
        }
        return Header(version, iterations, salt, nonce, bytes)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun requireRegularFile(file: File) {
        require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.canRead()) {
            "Archive file does not exist or is unreadable"
        }
    }

    private fun prepareOutput(target: File) {
        target.parentFile?.let {
            require(it.mkdirs() || it.isDirectory) { "Unable to create archive output directory" }
        }
        require(!Files.isSymbolicLink(target.toPath())) {
            "Archive output is a symbolic link"
        }
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            require(target.delete()) { "Unable to replace archive output" }
        }
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read < 0) throw EOFException("Encrypted workspace archive header is truncated")
            if (read == 0) continue
            offset += read
        }
        return bytes
    }

    private fun readUpTo(input: InputStream, bytes: ByteArray): Int {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) break
            if (read == 0) continue
            offset += read
        }
        return offset
    }

    private fun authenticationFailure(error: Throwable): Throwable {
        if (error is WorkspaceArchiveAuthenticationException) return error
        val cause = deepestCause(error)
        return when (cause) {
            is AEADBadTagException,
            is BadPaddingException,
            is EOFException,
            -> WorkspaceArchiveAuthenticationException(
                "Backup password is incorrect or the encrypted archive is corrupted",
                error,
            )

            is GeneralSecurityException,
            is IllegalArgumentException,
            -> WorkspaceArchiveAuthenticationException(
                "Unable to authenticate encrypted workspace archive",
                error,
            )

            else -> error
        }
    }

    private fun deepestCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private fun deleteTargetAfterFailure(target: File) {
        if (!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(target.toPath())) {
            "Unable to clean failed archive decryption target"
        }
        require(target.delete()) { "Unable to clean failed archive decryption target" }
    }

    private data class Header(
        val version: Int,
        val iterations: Int,
        val salt: ByteArray,
        val nonce: ByteArray,
        val bytes: ByteArray,
    ) {
        val size: Int
            get() = bytes.size
    }
}
