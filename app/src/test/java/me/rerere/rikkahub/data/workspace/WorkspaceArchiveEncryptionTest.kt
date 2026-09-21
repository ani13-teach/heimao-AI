package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class WorkspaceArchiveEncryptionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `fixed envelope decrypts to its original plaintext`() {
        val encrypted = fixtureFile()

        val inspection = WorkspaceArchiveEncryption.inspect(encrypted)
        assertEquals(WorkspaceArchiveEnvelope.ENCRYPTED, inspection.envelope)
        assertTrue(inspection.isEncrypted)
        assertEquals(1, inspection.version)

        val restored = temporaryFolder.newFile("restored.zip")
        WorkspaceArchiveEncryption.decrypt(encrypted, restored, FIXTURE_PASSWORD.toCharArray())
        assertArrayEquals(FIXTURE_PLAINTEXT.toByteArray(Charsets.UTF_8), restored.readBytes())
    }

    @Test
    fun `wrong password removes decrypted temporary plaintext`() {
        val encrypted = fixtureFile()
        val decrypted = temporaryFolder.newFile("decrypted.zip")

        assertThrows(WorkspaceArchiveAuthenticationException::class.java) {
            WorkspaceArchiveEncryption.decrypt(encrypted, decrypted, "wrong-password".toCharArray())
        }
        assertFalse(decrypted.exists())
    }

    @Test
    fun `tampered and truncated envelopes fail authentication`() {
        val bytes = fixtureFile().readBytes()
        val password = FIXTURE_PASSWORD.toCharArray()

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
    fun `legacy confirmation is only required for plaintext archives without a portable manifest`() {
        val encrypted = WorkspaceArchiveEncryption.inspect(fixtureFile())
        val legacy = WorkspaceArchiveInspection(WorkspaceArchiveEnvelope.LEGACY)

        assertTrue(legacy.requiresLegacyConfirmation(hasPortableManifest = false))
        assertFalse(legacy.requiresLegacyConfirmation(hasPortableManifest = true))
        assertFalse(encrypted.requiresLegacyConfirmation(hasPortableManifest = false))
        assertFalse(encrypted.requiresLegacyConfirmation(hasPortableManifest = true))
    }

    private fun fixtureFile(): File {
        val file = temporaryFolder.newFile("fixture.zip.enc")
        file.writeBytes(Base64.getDecoder().decode(FIXTURE_BASE64))
        assertEquals(FIXTURE_BYTES, file.length())
        return file
    }

    private companion object {
        // Produced once by the retired encrypt() writer and pinned here, so the reader keeps being
        // checked against bytes that were really written by 2.4.9 and can never be regenerated.
        const val FIXTURE_PLAINTEXT = "PK\u0003\u0004portable-workspace-archive-from-2.4.9"
        const val FIXTURE_PASSWORD = "legacy-backup-password"
        const val FIXTURE_BYTES = 101L
        const val FIXTURE_BASE64 =
            "SE1BRU5DMDEBAQAB1MAQDLLnjxtAPazsZ7dDPM/ghkZvbT+Y2YxYzPX0VncYMrbpPhZrfvEgir7Nky0HOw9HW5jaf" +
                "sVfPq+VVzkM7zFvrAvttZ3fl/iJdhxh5cQPMB+2U8i7u+0="
    }
}
