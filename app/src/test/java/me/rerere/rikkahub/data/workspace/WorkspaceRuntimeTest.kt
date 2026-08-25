package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceRuntimeTest {
    @Test
    fun `dns selection is empty without an active network`() {
        assertEquals(
            emptyList<String>(),
            selectWorkspaceDnsServers(
                activeNetworkPresent = false,
                dnsServerAddresses = listOf("1.1.1.1"),
            ),
        )
    }

    @Test
    fun `dns selection falls back when the platform query is forbidden`() {
        assertEquals(
            emptyList<String>(),
            selectWorkspaceDnsServers(true) {
                throw SecurityException("permission denied")
            },
        )
    }

    @Test
    fun `dns selection removes blanks and duplicate addresses`() {
        assertEquals(
            listOf("1.1.1.1", "::1"),
            selectWorkspaceDnsServers(
                activeNetworkPresent = true,
                dnsServerAddresses = listOf(" 1.1.1.1 ", "", "1.1.1.1", "::1"),
            ),
        )
    }
}
