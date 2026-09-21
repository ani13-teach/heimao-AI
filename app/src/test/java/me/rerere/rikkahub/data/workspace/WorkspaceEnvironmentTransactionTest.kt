package me.rerere.rikkahub.data.workspace

import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.hasUsableFullRootfs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Covers the crash-safe exchange of the public rootfs. Every test uses a real temporary tree; the
 * journal is written by hand where a crash has to be simulated between two renames.
 */
class WorkspaceEnvironmentTransactionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var activeDir: File
    private lateinit var previousDir: File
    private lateinit var stagingDir: File
    private lateinit var recoveryDir: File
    private lateinit var journal: File
    private lateinit var store: WorkspaceEnvironmentTransactionStore

    @Before
    fun setUp() {
        rootDir = temporaryFolder.newFolder("shared-linux")
        activeDir = File(rootDir, "linux")
        previousDir = File(rootDir, "previous")
        stagingDir = File(rootDir, "install")
        recoveryDir = File(rootDir, "recovery")
        journal = File(rootDir, "transaction.json")
        store = WorkspaceEnvironmentTransactionStore(
            activeDir = activeDir,
            previousDir = previousDir,
            stagingDir = stagingDir,
            recoveryArchive = File(recoveryDir, "active.tar"),
            transactionFile = journal,
        )
    }

    @Test
    fun `an exchange makes the candidate active and clears the journal`() {
        usableRootfs(activeDir, marker = "old")
        val candidate = File(rootDir, "candidate").also { usableRootfs(it, marker = "new") }

        store.prepareExchange(candidate)
        store.finalizeExchange()

        assertEquals("new", markerOf(activeDir))
        assertFalse(previousDir.exists())
        assertNull(store.readTransaction())
        assertTrue(activeDir.hasUsableFullRootfs())
    }

    @Test
    fun `a crash between the two renames restores the previous rootfs`() {
        usableRootfs(activeDir, marker = "old")
        // The first rename happened; the candidate never arrived.
        Files.move(activeDir.toPath(), previousDir.toPath())
        writeJournal(WorkspaceEnvironmentPhase.PREPARED)

        val outcome = store.recover()

        assertEquals(WorkspaceEnvironmentRecovery.RESTORED_PREVIOUS, outcome)
        assertTrue(activeDir.hasUsableFullRootfs())
        assertEquals("old", markerOf(activeDir))
        assertFalse(previousDir.exists())
        assertNull(store.readTransaction())
    }

    @Test
    fun `a crash after candidate arrival but before commit restores the previous tree`() {
        usableRootfs(activeDir, marker = "old")
        val candidate = File(rootDir, "candidate").also { usableRootfs(it, marker = "new") }
        Files.move(activeDir.toPath(), previousDir.toPath())
        Files.move(candidate.toPath(), activeDir.toPath())
        // COMMITTED was never written, so recovery must restore the last committed environment.
        writeJournal(WorkspaceEnvironmentPhase.PREPARED)

        val outcome = store.recover()

        assertEquals(WorkspaceEnvironmentRecovery.RESTORED_PREVIOUS, outcome)
        assertEquals("old", markerOf(activeDir))
        assertFalse(previousDir.exists())
        assertNull(store.readTransaction())
    }

    @Test
    fun `a committed journal only removes the displaced tree`() {
        usableRootfs(activeDir, marker = "new")
        usableRootfs(previousDir, marker = "old")
        writeJournal(WorkspaceEnvironmentPhase.COMMITTED)

        val outcome = store.recover()

        assertEquals(WorkspaceEnvironmentRecovery.DISCARDED_PREVIOUS, outcome)
        assertEquals("new", markerOf(activeDir))
        assertFalse(previousDir.exists())
    }

    @Test
    fun `an unreadable backup is kept instead of deleting the only copy`() {
        File(previousDir, "junk").mkdirs()
        writeJournal(WorkspaceEnvironmentPhase.PREPARED)

        val outcome = store.recover()

        assertEquals(WorkspaceEnvironmentRecovery.KEPT_FOR_INSPECTION, outcome)
        assertFalse(previousDir.exists())
        assertTrue(rootDir.listFiles().orEmpty().any { it.name.startsWith("inspection-") && File(it, "junk").isDirectory })
        val candidate = File(rootDir, "candidate").also { usableRootfs(it, marker = "repaired") }
        store.prepareExchange(candidate)
        store.finalizeExchange()
        assertEquals("repaired", markerOf(activeDir))
        assertNull(store.readTransaction())
    }

    @Test
    fun `a partial tree in the way is replaced by the usable backup`() {
        File(activeDir, "junk").mkdirs()
        usableRootfs(previousDir, marker = "old")
        writeJournal(WorkspaceEnvironmentPhase.PREPARED)

        val outcome = store.recover()

        assertEquals(WorkspaceEnvironmentRecovery.RESTORED_PREVIOUS, outcome)
        assertTrue(activeDir.hasUsableFullRootfs())
        assertEquals("old", markerOf(activeDir))
        assertFalse(File(rootDir, "incomplete").exists())
        assertFalse(previousDir.exists())
    }

    @Test
    fun `recovery without a journal and without a displaced tree does nothing`() {
        usableRootfs(activeDir, marker = "old")

        assertEquals(WorkspaceEnvironmentRecovery.NONE, store.recover())
        assertEquals("old", markerOf(activeDir))
    }

    @Test
    fun `a recovery snapshot can rebuild a broken rootfs`() {
        usableRootfs(activeDir, marker = "old")
        val payload = File(activeDir, "usr/bin/extra").apply {
            parentFile?.mkdirs()
            writeText("payload")
        }

        assertTrue(store.snapshotActiveToRecovery())
        assertTrue(store.recoveryAvailable())

        // Break the live rootfs the way a failed maintenance run would.
        payload.delete()
        File(activeDir, "bin/sh").delete()
        assertFalse(activeDir.hasUsableFullRootfs())

        assertTrue(store.restoreRecoverySnapshot())

        assertTrue(activeDir.hasUsableFullRootfs())
        assertEquals("payload", File(activeDir, "usr/bin/extra").readText())
        assertEquals("old", markerOf(activeDir))
        assertNull(store.readTransaction())
        assertFalse(stagingDir.exists())
    }

    @Test
    fun `an uninstalled environment is not snapshotted`() {
        assertFalse(store.snapshotActiveToRecovery())
        assertFalse(store.recoveryAvailable())
    }

    @Test
    fun `a snapshot keeps symbolic links instead of copying their targets`() {
        usableRootfs(activeDir, marker = "old")
        val target = File(activeDir, "usr/lib64")
        target.mkdirs()
        File(target, "libc.so").writeText("libc")
        assumeTrue("platform cannot create symbolic links", supportsSymbolicLinks(target.parentFile))
        Files.createSymbolicLink(File(activeDir, "lib64").toPath(), Paths.get("usr/lib64"))

        assertTrue(store.snapshotActiveToRecovery())
        File(activeDir, "lib64").delete()
        assertTrue(store.restoreRecoverySnapshot())

        val restored = File(activeDir, "lib64")
        assertTrue(Files.isSymbolicLink(restored.toPath()))
        assertEquals("libc", File(restored, "libc.so").readText())
    }

    @Test
    fun `recursive delete never follows a symbolic link`() {
        val outside = temporaryFolder.newFolder("outside")
        val keep = File(outside, "keep").apply { writeText("keep") }
        val tree = File(rootDir, "tree").apply { mkdirs() }
        assumeTrue("platform cannot create symbolic links", supportsSymbolicLinks(tree))
        Files.createSymbolicLink(File(tree, "link").toPath(), outside.toPath())

        deleteTreeNoFollow(tree)

        assertFalse(tree.exists())
        assertTrue(keep.exists())
        assertEquals("keep", keep.readText())
    }

    private fun usableRootfs(directory: File, marker: String) {
        File(directory, "bin").mkdirs()
        File(directory, "usr/bin").mkdirs()
        File(directory, "bin/sh").writeText("#!/bin/sh\n")
        File(directory, "bin/bash").writeText("#!/bin/bash\n")
        File(directory, "usr/bin/env").writeText("#!/usr/bin/env\n")
        File(directory, "marker").writeText(marker)
    }

    private fun markerOf(directory: File): String = File(directory, "marker").readText()

    private fun supportsSymbolicLinks(directory: File): Boolean {
        val probe = File(directory, "probe-link")
        return runCatching {
            Files.createSymbolicLink(probe.toPath(), Paths.get("probe-target"))
            probe.delete()
            true
        }.getOrDefault(false)
    }

    private fun writeJournal(phase: WorkspaceEnvironmentPhase) {
        journal.parentFile?.mkdirs()
        journal.writeText(
            JsonInstant.encodeToString(
                WorkspaceEnvironmentTransaction(
                    phase = phase,
                    activeDir = activeDir.absolutePath,
                    previousDir = previousDir.absolutePath,
                    recoveryArchive = File(recoveryDir, "active.tar").absolutePath,
                    startedAt = 1L,
                )
            )
        )
    }
}
