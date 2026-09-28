package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.data.db.entity.WorkspaceAptPlanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the execution half of the apt plan model: the fixed argv a confirmed plan may run, the
 * boundary check that rejects anything else, and the mapping from a finished attempt to the terminal
 * persisted state. Nothing here executes apt; the argv is inspected as data, which is exactly what
 * makes the real channel narrow.
 */
class WorkspaceAptPlanExecutionTest {

    @Test
    fun `execution commands have one fixed shape and never carry an option`() {
        assertEquals(
            listOf("/usr/bin/apt-get", "-y", "install", "curl", "htop"),
            WorkspaceAptRequest.install(listOf("htop", "curl")).executionCommand(),
        )
        assertEquals(
            listOf("/usr/bin/apt-get", "-y", "remove", "nano"),
            WorkspaceAptRequest.remove(listOf("nano")).executionCommand(),
        )
        assertEquals(
            listOf("/usr/bin/apt-get", "-y", "purge", "nano"),
            WorkspaceAptRequest.remove(listOf("nano"), purge = true).executionCommand(),
        )
        assertEquals(
            listOf("/usr/bin/apt-get", "-y", "--only-upgrade", "install", "curl"),
            WorkspaceAptRequest.upgrade(listOf("curl")).executionCommand(),
        )

        // `-y` is the only option a valid argv may contain.
        val requiredOption = "-y"
        val forbidden = setOf(
            "--yes",
            "--assume-yes",
            "--assume-no",
            "--force-yes",
            "-f",
            "--fix-broken",
            "--allow-unauthenticated",
            "--allow-downgrades",
            "--allow-change-held-packages",
            "--auto-remove",
            "--dry-run",
            "-s",
            "--simulate",
            "dist-upgrade",
            "full-upgrade",
            "autoremove",
            "clean",
            "update",
        )
        listOf(
            WorkspaceAptRequest.install(listOf("curl", "htop")),
            WorkspaceAptRequest.remove(listOf("nano")),
            WorkspaceAptRequest.remove(listOf("nano"), purge = true),
            WorkspaceAptRequest.upgrade(listOf("curl")),
        ).map { it.executionCommand() }.forEach { argv ->
            assertEquals("/usr/bin/apt-get", argv.first())
            assertEquals(requiredOption, argv[1])
            assertTrue("unexpected option in $argv", forbidden.intersect(argv.toSet()).isEmpty())
            assertTrue("argv must not fuse arguments: $argv", argv.none { it.contains(' ') })
        }
    }

    @Test
    fun `only the fixed execution shapes pass the runner boundary`() {
        listOf(
            WorkspaceAptRequest.install(listOf("curl", "htop")).executionCommand(),
            WorkspaceAptRequest.remove(listOf("nano")).executionCommand(),
            WorkspaceAptRequest.remove(listOf("nano"), purge = true).executionCommand(),
            WorkspaceAptRequest.upgrade(listOf("curl")).executionCommand(),
        ).forEach { requireExecutionArgv(it) }

        val rejected = listOf(
            emptyList(),
            listOf("/usr/bin/apt-get"),
            listOf("/usr/bin/apt-get", "-y"),
            // Missing or misplaced `-y`.
            listOf("/usr/bin/apt-get", "install", "curl"),
            listOf("/usr/bin/apt-get", "-y", "install"),
            // Not the guest apt path, or an interpreter instead of it.
            listOf("apt-get", "-y", "install", "curl"),
            listOf("/bin/sh", "-y", "install", "curl"),
            listOf("/bin/sh", "-c", "apt-get -y install curl"),
            // An option smuggled in as if it were a package.
            listOf("/usr/bin/apt-get", "-y", "install", "curl", "--force-yes"),
            listOf("/usr/bin/apt-get", "-y", "--allow-unauthenticated", "curl"),
            listOf("/usr/bin/apt-get", "-y", "install", "-y"),
            listOf("/usr/bin/apt-get", "-y", "install", "--auto-remove"),
            // An unknown verb, a shell metacharacter, an architecture selector.
            listOf("/usr/bin/apt-get", "-y", "upgrade", "curl"),
            listOf("/usr/bin/apt-get", "-y", "dist-upgrade", "curl"),
            listOf("/usr/bin/apt-get", "-y", "install", "curl;id"),
            listOf("/usr/bin/apt-get", "-y", "install", "curl|tee"),
            listOf("/usr/bin/apt-get", "-y", "install", "curl:amd64"),
            listOf("/usr/bin/apt-get", "-y", "install", "curl\nrm -rf /"),
            // Duplicated or unsorted packages are not the normalized plan.
            listOf("/usr/bin/apt-get", "-y", "install", "curl", "curl"),
            listOf("/usr/bin/apt-get", "-y", "install", "htop", "curl"),
        )
        rejected.forEach { argv ->
            assertThrows(
                "expected $argv to be rejected",
                IllegalArgumentException::class.java,
            ) {
                requireExecutionArgv(argv)
            }
        }
    }

    @Test
    fun `a confirmed zero exit succeeds and every other confirmed exit needs recovery`() {
        assertEquals(
            WorkspaceAptPlanState.SUCCEEDED,
            WorkspaceAptExecutionReport.Exited(0).terminalPlanState(),
        )
        assertEquals(
            WorkspaceAptPlanState.RECOVERY_REQUIRED,
            WorkspaceAptExecutionReport.Exited(1).terminalPlanState(),
        )
        assertEquals(
            WorkspaceAptPlanState.RECOVERY_REQUIRED,
            WorkspaceAptExecutionReport.Exited(-1).terminalPlanState(),
        )
        assertEquals(
            WorkspaceAptPlanState.UNKNOWN,
            WorkspaceAptExecutionReport.ExitUnknown.terminalPlanState(),
        )
        assertThrows(IllegalStateException::class.java) {
            WorkspaceAptExecutionReport.Rejected("never started").terminalPlanState()
        }
    }
}
