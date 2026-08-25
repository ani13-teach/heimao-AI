package me.rerere.rikkahub.data.workspace

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Separate encrypted-at-rest slots for transport-specific archive passwords. */
enum class BackupPasswordSlot(val preferenceKey: String) {
    WEBDAV("webdav"),
    S3("s3"),
}

class BackupPasswordStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    fun read(slot: BackupPasswordSlot): CharArray? {
        val encoded = preferences.getString(slot.preferenceKey, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.DEFAULT)
            require(packed.size > IV_BYTES) { "Stored backup password is malformed" }
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                plaintext.toString(Charsets.UTF_8).toCharArray()
            } finally {
                plaintext.fill(0)
                iv.fill(0)
                ciphertext.fill(0)
                packed.fill(0)
            }
        }.getOrElse {
            // A lost Keystore key (for example after an OS-level app-data restore) must not leave
            // an unusable ciphertext that is retried forever. The user will be prompted again.
            preferences.edit().remove(slot.preferenceKey).apply()
            null
        }
    }

    fun write(slot: BackupPasswordSlot, password: CharArray) {
        if (password.isEmpty()) {
            clear(slot)
            return
        }
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val plaintext = password.concatToString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)
            val packed = ByteArray(iv.size + ciphertext.size)
            iv.copyInto(packed)
            ciphertext.copyInto(packed, destinationOffset = iv.size)
            try {
                preferences.edit()
                    .putString(slot.preferenceKey, Base64.encodeToString(packed, Base64.NO_WRAP))
                    .commit()
                    .also { require(it) { "Unable to persist backup password securely" } }
            } finally {
                packed.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            plaintext.fill(0)
            iv.fill(0)
        }
    }

    fun clear(slot: BackupPasswordSlot) {
        preferences.edit().remove(slot.preferenceKey).commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFERENCES_NAME = "workspace_backup_passwords"
        private const val KEY_ALIAS = "heimao_workspace_backup_passwords"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
