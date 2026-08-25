package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows
import java.io.File

class WorkspaceArchiveEncryptionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `versioned envelope round trips and never contains plaintext`() {
        val source = temporaryFolder.newFile("portable.zip").apply {
            writeText("manifest.json\nprivate workspace payload")
        }
        val encrypted = temporaryFolder.newFile("portable.zip.enc")
        val password = "correct horse battery".toCharArray()
        WorkspaceArchiveEncryption.encrypt(source, encrypted, password)

        assertEquals(WorkspaceArchiveEnvelope.ENCRYPTED, WorkspaceArchiveEncryption.inspect(encrypted).envelope)
        val encryptedText = encrypted.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(encryptedText.contains("private workspace payload"))
        assertFalse(encryptedText.contains("correct horse battery"))

        val restored = temporaryFolder.newFile("restored.zip")
        WorkspaceArchiveEncryption.decrypt(encrypted, restored, password)
        assertEquals(source.readText(), restored.readText())
    }

    @Test
    fun `wrong password removes decrypted temporary plaintext`() {
        val source = temporaryFolder.newFile("portable.zip").apply { writeText("secret workspace") }
        val encrypted = temporaryFolder.newFile("portable.zip.enc")
        WorkspaceArchiveEncryption.encrypt(source, encrypted, "correct-password".toCharArray())
        val decrypted = temporaryFolder.newFile("decrypted.zip")

        assertThrows(WorkspaceArchiveAuthenticationException::class.java) {
            WorkspaceArchiveEncryption.decrypt(encrypted, decrypted, "wrong-password".toCharArray())
        }
        assertFalse(decrypted.exists())
    }

    @Test
    fun `tampered and truncated envelopes fail authentication`() {
        val source = temporaryFolder.newFile("portable.zip").apply { writeText("authenticated payload") }
        val encrypted = temporaryFolder.newFile("portable.zip.enc")
        val password = "correct-password".toCharArray()
        WorkspaceArchiveEncryption.encrypt(source, encrypted, password)
        val bytes = encrypted.readBytes()

        val tampered = temporaryFolder.newFile("tampered.enc").apply {
            writeBytes(bytes.copyOf().also { it[it.lastIndex] = (it[it.lastIndex].toInt() xor 1).toByte() })
        }
        assertThrows(WorkspaceArchiveAuthenticationException::class.java) {
            WorkspaceArchiveEncryption.decrypt(
                tampered,
                temporaryFolder.newFile("tampered.zip"),
                password,
            )
        }

        val truncated = temporaryFolder.newFile("truncated.enc").apply {
            writeBytes(bytes.copyOf(bytes.size - 1))
        }
        assertThrows(WorkspaceArchiveAuthenticationException::class.java) {
            WorkspaceArchiveEncryption.decrypt(
                truncated,
                temporaryFolder.newFile("truncated.zip"),
                password,
            )
        }
    }

    @Test
    fun `missing encryption magic is explicitly legacy`() {
        val legacy = temporaryFolder.newFile("legacy.zip").apply { writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04)) }
        val inspection = WorkspaceArchiveEncryption.inspect(legacy)
        assertEquals(WorkspaceArchiveEnvelope.LEGACY, inspection.envelope)
        assertTrue(inspection.isLegacy)
    }

    @Test
    fun `settings sanitizer strips transport credentials and archive passwords`() {
        val transportPassword = "transport-password"
        val archivePassword = "archive-password"
        val sanitized = sanitizeWorkspaceArchiveSettings(
            Settings(
                webDavConfig = WebDavConfig(
                    username = "user",
                    password = archivePassword,
                ),
                s3Config = S3Config(
                    accessKeyId = "access",
                    secretAccessKey = transportPassword,
                ),
            )
        )
        val encoded = JsonInstant.encodeToString(sanitized)
        assertFalse(encoded.contains(transportPassword))
        assertFalse(encoded.contains(archivePassword))
        assertEquals("", sanitized.webDavConfig.password)
        assertEquals("", sanitized.s3Config.secretAccessKey)
    }
}
