package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale

/**
 * Covers the simulation-only apt plan model: request validation, the simulated argv, parsing of
 * real `apt-get -s` output, the plan-level rejections (empty plan, unexpected removal) and the
 * fingerprint. Nothing here executes apt; every result is fed to [parseSimulation] as text.
 */
class WorkspaceAptPlanTest {

    @Test
    fun `a request normalizes duplicates and order`() {
        val request = WorkspaceAptRequest.install(listOf("curl", "htop", "curl"))

        assertEquals(WorkspaceAptAction.INSTALL, request.action)
        assertEquals(listOf("curl", "htop"), request.packages)
        assertFalse(request.purge)
    }

    @Test
    fun `the persisted action names stay stable`() {
        assertEquals("install", WorkspaceAptAction.INSTALL.wireName)
        assertEquals("remove", WorkspaceAptAction.REMOVE.wireName)
        assertEquals("upgrade", WorkspaceAptAction.UPGRADE.wireName)
        assertEquals(WorkspaceAptAction.INSTALL, WorkspaceAptAction.fromWireName("install"))
        assertNull(WorkspaceAptAction.fromWireName("INSTALL"))
        assertNull(WorkspaceAptAction.fromWireName("purge"))

        val restored = WorkspaceAptRequest.fromWireName("install", listOf("htop"))
        assertEquals(WorkspaceAptRequest.install(listOf("htop")), restored)
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.fromWireName("nonsense", listOf("htop"))
        }
    }

    @Test
    fun `package arguments outside the Debian grammar are rejected`() {
        val rejected = listOf(
            "",
            " ",
            " htop",
            "htop ",
            "-y",
            "--force-yes",
            "-f",
            "htop;rm -rf /",
            "htop;id",
            "htop:amd64",
            "htop=1.0",
            "htop/stable",
            "htop|tee",
            "HTop",
            "HTOP",
            "\$(id)",
            "`id`",
            "htop\ncurl",
            "..",
            "a".repeat(256),
            "\u00E9htop",
        )

        rejected.forEach { argument ->
            assertThrows(
                "expected '$argument' to be rejected",
                IllegalArgumentException::class.java,
            ) {
                WorkspaceAptRequest.install(listOf(argument))
            }
        }
    }

    @Test
    fun `an empty request is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.install(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.remove(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.upgrade(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.of(WorkspaceAptAction.UPGRADE, emptyList())
        }
    }

    @Test
    fun `at most 32 distinct packages are accepted`() {
        val accepted = (1..MAX_WORKSPACE_APT_PACKAGES).map { "package$it" }
        assertEquals(MAX_WORKSPACE_APT_PACKAGES, WorkspaceAptRequest.install(accepted).packages.size)

        val tooMany = (1..MAX_WORKSPACE_APT_PACKAGES + 1).map { "package$it" }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.install(tooMany)
        }

        // The limit counts the normalized list, so a repeated argument does not spend a slot.
        val padded = (1..MAX_WORKSPACE_APT_PACKAGES).flatMap { listOf("package$it", "package$it") }
        assertEquals(MAX_WORKSPACE_APT_PACKAGES, WorkspaceAptRequest.install(padded).packages.size)
    }

    @Test
    fun `purge is only allowed for a remove request`() {
        assertEquals(
            WorkspaceAptRequest(WorkspaceAptAction.REMOVE, listOf("nano"), purge = true),
            WorkspaceAptRequest.remove(listOf("nano"), purge = true),
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.of(WorkspaceAptAction.INSTALL, listOf("nano"), purge = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.of(WorkspaceAptAction.UPGRADE, listOf("nano"), purge = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.install(listOf("nano")).copy(purge = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.upgrade(listOf("nano")).copy(purge = true)
        }
    }

    @Test
    fun `copy cannot bypass the request invariants`() {
        val request = WorkspaceAptRequest.install(listOf("curl", "htop"))

        assertThrows(IllegalArgumentException::class.java) {
            request.copy(packages = listOf("libuv1", "curl"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(packages = listOf("curl", "curl"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(packages = listOf("curl", "htop;id"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            request.copy(packages = emptyList())
        }
    }

    @Test
    fun `commands are simulation only`() {
        assertEquals(
            listOf("apt-get", "-s", "install", "curl", "htop"),
            WorkspaceAptRequest.install(listOf("htop", "curl")).command(),
        )
        assertEquals(
            listOf("apt-get", "-s", "remove", "nano"),
            WorkspaceAptRequest.remove(listOf("nano")).command(),
        )
        assertEquals(
            listOf("apt-get", "-s", "purge", "nano"),
            WorkspaceAptRequest.remove(listOf("nano"), purge = true).command(),
        )
        assertEquals(
            listOf("apt-get", "-s", "--only-upgrade", "install", "curl"),
            WorkspaceAptRequest.upgrade(listOf("curl")).command(),
        )

        val commands = listOf(
            WorkspaceAptRequest.install(listOf("htop")),
            WorkspaceAptRequest.remove(listOf("nano")),
            WorkspaceAptRequest.remove(listOf("nano"), purge = true),
            WorkspaceAptRequest.upgrade(listOf("curl")),
        ).map { it.command() }

        val forbidden = setOf(
            "-y",
            "--yes",
            "--assume-yes",
            "--assume-no",
            "--force-yes",
            "-f",
            "--fix-broken",
            "dist-upgrade",
            "full-upgrade",
            "autoremove",
            "clean",
            "update",
        )
        commands.forEach { command ->
            assertEquals("apt-get", command.first())
            assertTrue("expected a simulated argv: $command", command.contains("-s"))
            assertTrue("expected no forbidden argument: $command", forbidden.intersect(command.toSet()).isEmpty())
            // Every requested package is an argument of its own; nothing is fused into one string.
            assertEquals(command.size, command.filter { it.isNotBlank() }.size)
        }
        assertFalse(commands.any { it.any { argument -> argument.contains(' ') } })
    }

    @Test
    fun `an install simulation counts only the apt verbs`() {
        val simulation = WorkspaceAptRequest.install(listOf("htop"))
            .parseSimulation(result(exitCode = 0, stdout = INSTALL_OUTPUT))

        assertEquals(
            listOf(
                WorkspaceAptPackageChange("libuv1", toVersion = "1.40.0-1"),
                WorkspaceAptPackageChange("htop", toVersion = "3.0.5-7"),
            ),
            simulation.installs,
        )
        // The "will be installed:" listing repeats the names; it must not double the plan.
        assertEquals(2, simulation.installs.size)
        assertTrue(simulation.upgrades.isEmpty())
        assertTrue(simulation.removals.isEmpty())
        assertTrue(simulation.purges.isEmpty())
        assertEquals(listOf("libuv1", "htop"), simulation.configured)
        assertFalse(simulation.isEmpty)
        assertFalse(simulation.removesPackages)
    }

    @Test
    fun `an install that replaces a version is an upgrade`() {
        val simulation = WorkspaceAptRequest.upgrade(listOf("curl"))
            .parseSimulation(result(exitCode = 0, stdout = UPGRADE_OUTPUT))

        assertTrue(simulation.installs.isEmpty())
        assertEquals(
            listOf(
                WorkspaceAptPackageChange(
                    name = "curl",
                    fromVersion = "7.68.0-1ubuntu2.20",
                    toVersion = "7.81.0-1ubuntu1.15",
                ),
            ),
            simulation.upgrades,
        )
        assertEquals(listOf("curl"), simulation.configured)
    }

    @Test
    fun `a removal simulation keeps the versions apt printed`() {
        val stdout = """
            Reading package lists...
            Building dependency tree...
            Reading state information...
            The following packages will be REMOVED:
              nano vim-common
            0 upgraded, 0 newly installed, 2 to remove and 0 not upgraded.
            Remv nano [5.4-2]
            Remv vim-common
        """.trimIndent()

        val simulation = WorkspaceAptRequest.remove(listOf("nano", "vim-common"))
            .parseSimulation(result(exitCode = 0, stdout = stdout))

        assertEquals(
            listOf(
                WorkspaceAptPackageChange("nano", fromVersion = "5.4-2"),
                WorkspaceAptPackageChange("vim-common"),
            ),
            simulation.removals,
        )
        assertTrue(simulation.purges.isEmpty())
        assertFalse(simulation.isEmpty)
        assertTrue(simulation.removesPackages)
    }

    @Test
    fun `a purge simulation reads purged packages`() {
        val stdout = """
            Reading package lists...
            Reading state information...
            The following packages will be REMOVED:
              vim*
            0 upgraded, 0 newly installed, 1 to remove and 0 not upgraded.
            Purg vim [2:8.2.2434-3]
            Conf libc6 (2.36-9)
        """.trimIndent()

        val simulation = WorkspaceAptRequest.remove(listOf("vim"), purge = true)
            .parseSimulation(result(exitCode = 0, stdout = stdout))

        assertEquals(
            listOf(WorkspaceAptPackageChange("vim", fromVersion = "2:8.2.2434-3")),
            simulation.purges,
        )
        assertTrue(simulation.removals.isEmpty())
        assertEquals(listOf("libc6"), simulation.configured)
    }

    @Test
    fun `names parsed from apt output may carry an architecture qualifier`() {
        val stdout = """
            Inst libc6:i386 (2.36-9 Debian:12/stable [i386])
            Conf libc6:i386 (2.36-9 Debian:12/stable [i386])
        """.trimIndent()

        val simulation = WorkspaceAptRequest.install(listOf("libc6"))
            .parseSimulation(result(exitCode = 0, stdout = stdout))

        assertEquals(
            listOf(WorkspaceAptPackageChange("libc6:i386", toVersion = "2.36-9")),
            simulation.installs,
        )
    }

    @Test
    fun `several archives in one version line are still one upgrade`() {
        val stdout = "Inst curl [7.68.0-1] " +
            "(7.81.0-1 Ubuntu:22.04/jammy-updates, Ubuntu:22.04/jammy-security [amd64]) []"

        val simulation = WorkspaceAptRequest.upgrade(listOf("curl"))
            .parseSimulation(result(exitCode = 0, stdout = stdout))

        assertEquals(
            listOf(
                WorkspaceAptPackageChange("curl", fromVersion = "7.68.0-1", toVersion = "7.81.0-1"),
            ),
            simulation.upgrades,
        )
    }

    @Test
    fun `locale dependent summary lines are never trusted`() {
        val stdout = """
            Paketlisten werden gelesen...
            Abhaengigkeitsbaum wird aufgebaut...
            Installiert: htop
            0 aktualisiert, 1 neu installiert, 0 zu entfernen und 0 nicht aktualisiert.
            Inst htop (3.0.5-7 Debian:12/stable [amd64])
            Conf htop (3.0.5-7 Debian:12/stable [amd64])
            Removed: nichts
        """.trimIndent()

        val simulation = WorkspaceAptRequest.install(listOf("htop"))
            .parseSimulation(result(exitCode = 0, stdout = stdout))

        assertEquals(
            listOf(WorkspaceAptPackageChange("htop", toVersion = "3.0.5-7")),
            simulation.installs,
        )
        assertTrue(simulation.removals.isEmpty())
    }

    @Test
    fun `an unparseable verb line rejects the whole simulation`() {
        val unparseable = listOf(
            // A verb without the version parenthesis apt prints for a real change.
            "Inst htop",
            // A name that is not a Debian package name.
            "Inst htop;id (3.0.5-7 Debian:12/stable [amd64])",
            "Remv ../../etc/passwd [1.0]",
            "Purg EVIL [1.0]",
            // Control characters inside a version string.
            "Inst htop (1.0\u001B[31m)",
        )

        unparseable.forEach { line ->
            assertThrows(
                "expected '$line' to be rejected",
                IllegalArgumentException::class.java,
            ) {
                WorkspaceAptRequest.install(listOf("htop"))
                    .parseSimulation(result(exitCode = 0, stdout = line))
            }
        }
    }

    @Test
    fun `a finished looking plan without changes is rejected`() {
        val stdout = """
            Reading package lists...
            Building dependency tree...
            Reading state information...
            htop is already the newest version (3.0.5-7).
            0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.install(listOf("htop"))
                .parseSimulation(result(exitCode = 0, stdout = stdout))
        }
    }

    @Test
    fun `an install that would remove a package is rejected`() {
        val stdout = """
            The following packages will be REMOVED:
              libcurl4
            The following NEW packages will be installed:
              htop
            0 upgraded, 1 newly installed, 1 to remove and 0 not upgraded.
            Remv libcurl4 [7.81.0-1]
            Inst htop (3.0.5-7 Debian:12/stable [amd64])
        """.trimIndent()

        val failure = assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.install(listOf("htop"))
                .parseSimulation(result(exitCode = 0, stdout = stdout))
        }
        assertTrue(failure.message.orEmpty().contains("remove"))

        // The same output is not itself invalid: it stays parseable so a caller can inspect it.
        val parsed = WorkspaceAptSimulation(
            request = WorkspaceAptRequest.install(listOf("htop")),
            removals = listOf(WorkspaceAptPackageChange("libcurl4", fromVersion = "7.81.0-1")),
            installs = listOf(WorkspaceAptPackageChange("htop", toVersion = "3.0.5-7")),
        )
        assertTrue(parsed.removesPackages)
        assertThrows(IllegalArgumentException::class.java) { parsed.requireConfirmable() }
    }

    @Test
    fun `an upgrade may surface removals instead of rejecting them`() {
        val stdout = """
            The following packages will be REMOVED:
              libssl1.1
            The following packages will be upgraded:
              curl
            Remv libssl1.1 [1.1.1n-0+deb11u5]
            Inst curl [7.68.0-1] (7.81.0-1 Debian:12/stable [amd64])
        """.trimIndent()

        val simulation = WorkspaceAptRequest.upgrade(listOf("curl"))
            .parseSimulation(result(exitCode = 0, stdout = stdout))

        assertEquals(listOf("libssl1.1"), simulation.removals.map { it.name })
        assertTrue(simulation.removesPackages)
    }

    @Test
    fun `an unfinished simulation is never parsed`() {
        val stdout = "Inst htop (3.0.5-7 Debian:12/stable [amd64])"

        val timedOut = assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.install(listOf("htop"))
                .parseSimulation(result(exitCode = 0, stdout = stdout, timedOut = true))
        }
        assertTrue(timedOut.message.orEmpty().contains("timed out"))

        val truncated = assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.install(listOf("htop"))
                .parseSimulation(result(exitCode = 0, stdout = stdout, truncated = true))
        }
        assertTrue(truncated.message.orEmpty().contains("truncated"))

        val failed = assertThrows(IllegalArgumentException::class.java) {
            WorkspaceAptRequest.install(listOf("htop"))
                .parseSimulation(result(exitCode = 100, stdout = stdout))
        }
        assertTrue(failed.message.orEmpty().contains("100"))
    }

    @Test
    fun `the summary lists full names and versions`() {
        val install = WorkspaceAptRequest.install(listOf("htop"))
            .parseSimulation(result(exitCode = 0, stdout = INSTALL_OUTPUT))
        assertEquals(
            listOf("install libuv1 1.40.0-1", "install htop 3.0.5-7", "configure libuv1", "configure htop"),
            install.summaryLines(),
        )

        val upgrade = WorkspaceAptRequest.upgrade(listOf("curl"))
            .parseSimulation(result(exitCode = 0, stdout = UPGRADE_OUTPUT))
        assertEquals(
            listOf("upgrade curl 7.68.0-1ubuntu2.20 -> 7.81.0-1ubuntu1.15", "configure curl"),
            upgrade.summaryLines(),
        )

        val removal = WorkspaceAptRequest.remove(listOf("nano"), purge = true)
            .parseSimulation(result(exitCode = 0, stdout = "Purg nano [5.4-2]"))
        assertEquals(listOf("purge nano 5.4-2"), removal.summaryLines())

        val unknownVersion = WorkspaceAptRequest.remove(listOf("nano"))
            .parseSimulation(result(exitCode = 0, stdout = "Remv nano"))
        assertEquals(listOf("remove nano unknown"), unknownVersion.summaryLines())
    }

    @Test
    fun `the fingerprint is a stable SHA-256 of the canonical plan`() {
        val simulation = WorkspaceAptRequest.install(listOf("htop"))
            .parseSimulation(result(exitCode = 0, stdout = INSTALL_OUTPUT))

        val fingerprint = simulation.fingerprint(BASE_FINGERPRINT)
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(fingerprint, simulation.fingerprint(BASE_FINGERPRINT))

        val expected = MessageDigest.getInstance("SHA-256")
            .digest(simulation.canonicalDescription(BASE_FINGERPRINT).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        assertEquals(expected, fingerprint)

        assertNotEquals(fingerprint, simulation.fingerprint("$BASE_FINGERPRINT-other"))
    }

    @Test
    fun `the fingerprint covers the request and the parsed details`() {
        val simulation = WorkspaceAptRequest.install(listOf("htop"))
            .parseSimulation(result(exitCode = 0, stdout = INSTALL_OUTPUT))
        val fingerprint = simulation.fingerprint(BASE_FINGERPRINT)

        assertNotEquals(
            fingerprint,
            simulation.copy(
                request = WorkspaceAptRequest.install(listOf("htop", "curl")),
            ).fingerprint(BASE_FINGERPRINT),
        )
        assertNotEquals(
            fingerprint,
            simulation.copy(
                request = WorkspaceAptRequest.remove(listOf("htop"), purge = true),
            ).fingerprint(BASE_FINGERPRINT),
        )
        assertNotEquals(
            fingerprint,
            simulation.copy(
                installs = simulation.installs.reversed(),
            ).fingerprint(BASE_FINGERPRINT),
        )
        assertNotEquals(
            fingerprint,
            simulation.copy(
                installs = simulation.installs.map { it.copy(toVersion = "9.9.9") },
            ).fingerprint(BASE_FINGERPRINT),
        )
        assertNotEquals(
            fingerprint,
            simulation.copy(configured = simulation.configured + "extra").fingerprint(BASE_FINGERPRINT),
        )
    }

    @Test
    fun `the fingerprint ignores everything that is not a parsed detail`() {
        val noisy = WorkspaceAptRequest.install(listOf("htop")).parseSimulation(
            result(
                exitCode = 0,
                stdout = "Reading package lists...\n" + INSTALL_OUTPUT + "\nW: ignored warning\n",
            ),
        )
        val clean = WorkspaceAptRequest.install(listOf("htop"))
            .parseSimulation(result(exitCode = 0, stdout = INSTALL_OUTPUT))

        assertEquals(clean.fingerprint(BASE_FINGERPRINT), noisy.fingerprint(BASE_FINGERPRINT))
    }

    @Test
    fun `an unusable base fingerprint is rejected`() {
        val simulation = WorkspaceAptRequest.install(listOf("htop"))
            .parseSimulation(result(exitCode = 0, stdout = INSTALL_OUTPUT))

        listOf("", " ", "with space", "two\nlines", "a".repeat(257)).forEach { base ->
            assertThrows(
                "expected base fingerprint '$base' to be rejected",
                IllegalArgumentException::class.java,
            ) {
                simulation.fingerprint(base)
            }
        }
    }

    @Test
    fun `redaction removes escape sequences and control characters`() {
        val redacted = redactWorkspaceAptSimulationText(
            "W: \u001B[33mfetch failed\u001B[0m\u0007\nOK\r\nsecond\u001B]0;title\u0007line",
        )

        assertFalse(redacted.contains('\u001B'))
        assertFalse(redacted.contains('\u0007'))
        assertEquals("W: fetch failed\nOK\nsecondline", redacted)
    }

    @Test
    fun `redaction removes credentials from urls and secrets from query parameters`() {
        val redacted = redactWorkspaceAptSimulationText(
            "E: Failed to fetch https://user:proxy-secret@repo.example.com/dists/stable\n" +
                "E: https://repo.example.com/dists/stable?token=abc123&arch=amd64\n" +
                "E: socks5h://apt:another-secret@127.0.0.1:1080\n",
        )

        assertFalse(redacted.contains("proxy-secret"))
        assertFalse(redacted.contains("another-secret"))
        assertFalse(redacted.contains("abc123"))
        assertTrue(redacted.contains("https://<redacted>@repo.example.com/dists/stable"))
        assertTrue(redacted.contains("token=<redacted>"))
        assertTrue(redacted.contains("arch=amd64"))
    }

    @Test
    fun `redaction keeps ordinary simulation output readable`() {
        val stdout = INSTALL_OUTPUT.trim()
        assertEquals(stdout, redactWorkspaceAptSimulationText(stdout))
        assertEquals("", redactWorkspaceAptSimulationText(""))
    }

    @Test
    fun `redaction bounds the text it hands to the ui`() {
        val long = "x".repeat(MAX_WORKSPACE_APT_SIMULATION_TEXT_CHARS * 2)

        val redacted = redactWorkspaceAptSimulationText(long)

        assertEquals(MAX_WORKSPACE_APT_SIMULATION_TEXT_CHARS, redacted.length)
        assertTrue(redacted.endsWith("\u2026[truncated]"))

        val small = redactWorkspaceAptSimulationText(long, maxChars = 64)
        assertEquals(64, small.length)
        assertTrue(small.endsWith("\u2026[truncated]"))

        assertThrows(IllegalArgumentException::class.java) {
            redactWorkspaceAptSimulationText(long, maxChars = 1)
        }
    }

    private fun result(
        exitCode: Int,
        stdout: String,
        timedOut: Boolean = false,
        truncated: Boolean = false,
    ): WorkspaceAptSimulationResult = WorkspaceAptSimulationResult(
        exitCode = exitCode,
        timedOut = timedOut,
        truncated = truncated,
        stdout = stdout,
        stderr = "E: irrelevant for the plan",
    )

    private companion object {
        const val BASE_FINGERPRINT = "rootfs-9f1c2b3a4d5e6f70"

        val INSTALL_OUTPUT = """
            Reading package lists...
            Building dependency tree...
            Reading state information...
            The following additional packages will be installed:
              libuv1
            The following NEW packages will be installed:
              htop libuv1
            0 upgraded, 2 newly installed, 0 to remove and 0 not upgraded.
            Need to get 296 kB of archives.
            After this operation, 1,073 kB of additional disk space will be used.
            Inst libuv1 (1.40.0-1 Debian:11/stable, Debian:12/stable [amd64])
            Conf libuv1 (1.40.0-1 Debian:11/stable, Debian:12/stable [amd64])
            Inst htop (3.0.5-7 Debian:11/stable [amd64])
            Conf htop (3.0.5-7 Debian:11/stable [amd64])
        """.trimIndent()

        val UPGRADE_OUTPUT = """
            Reading package lists...
            Building dependency tree...
            Reading state information...
            Calculating upgrade...
            The following packages will be upgraded:
              curl
            1 upgraded, 0 newly installed, 0 to remove and 0 not upgraded.
            Inst curl [7.68.0-1ubuntu2.20] (7.81.0-1ubuntu1.15 Ubuntu:22.04/jammy-updates [amd64])
            Conf curl (7.81.0-1ubuntu1.15 Ubuntu:22.04/jammy-updates [amd64])
        """.trimIndent()
    }
}
