package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRuntimeMetadataTest {
    @Test
    fun `metadata round trips all runtime fields`() {
        val metadata = RootfsInstallResult(
            archiveSha256 = "a".repeat(64),
            archiveSize = 42,
            expectedSha256 = "b".repeat(64),
            expectedArchitecture = "arm64",
            detectedArchitecture = "arm64",
            supportedAbis = listOf("arm64-v8a"),
            distroId = "ubuntu",
            distroVersion = "24.04",
            capabilityChecks = mapOf("shell" to true),
            toolVersions = mapOf("bash" to "5.2"),
            toolScanAt = 1234,
            warnings = listOf("version unavailable"),
        ).toWorkspaceRuntimeMetadata(
            sourceUrl = "https://example.test/rootfs-arm64.tar.gz?token=secret",
            installedAt = 5678,
        )

        val decoded = JsonInstant.decodeFromString<WorkspaceRuntimeMetadata>(
            JsonInstant.encodeToString(metadata)
        )

        assertEquals(metadata, decoded)
        assertEquals("ubuntu", decoded.distroId)
        assertEquals("5.2", decoded.toolVersions["bash"])
    }

    @Test
    fun `redacted URL removes query credentials and user info`() {
        val redacted = redactWorkspaceUrl(
            "https://user:password@example.test/rootfs.tar.gz?token=secret&sig=private#fragment"
        )

        assertEquals("https://example.test/rootfs.tar.gz", redacted)
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("password"))
        assertTrue(redacted.endsWith("rootfs.tar.gz"))
        assertEquals(
            "download failed: https://example.test/rootfs.tar.gz",
            redactWorkspaceText("download failed: https://example.test/rootfs.tar.gz?token=secret"),
        )
    }
}
