package me.rerere.rikkahub.data.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * A portable workspace holds the Ubuntu base rootfs, which links through its own directories:
 * `bin -> usr/bin`, `lib -> usr/lib`, and files such as `etc/alternatives/pager` that point back
 * through them ("/bin/more"). A backup packs that tree into a TAR and immediately extracts it again
 * to verify the payload, so the round trip has to accept those links.
 *
 * The JVM test cannot cover this: creating symbolic links on a Windows workstation needs
 * privileges, so it is skipped there. This device test always runs the real Linux case.
 */
@RunWith(AndroidJUnit4::class)
class WorkspaceArchiveTarRootfsInstrumentedTest {
    @Test
    fun usrmergeRootfsSurvivesArchiveRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = File(context.cacheDir, "rootfs-round-trip-${System.nanoTime()}")
        val source = File(base, "linux")
        File(source, "usr/bin").mkdirs()
        File(source, "usr/lib/systemd/system").mkdirs()
        File(source, "etc/alternatives").mkdirs()
        File(source, "etc/systemd/system/timers.target.wants").mkdirs()
        File(source, "usr/bin/more").writeText("more\n")
        File(source, "usr/lib/systemd/system/apt-daily.timer").writeText("[Timer]\n")
        mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "etc/alternatives/pager" to "../../bin/more",
            "etc/systemd/system/timers.target.wants/apt-daily.timer" to
                "../../../../lib/systemd/system/apt-daily.timer",
        ).forEach { (name, target) ->
            Files.createSymbolicLink(File(source, name).toPath(), Paths.get(target))
        }
        try {
            val payload = File(base, "linux.tar")
            WorkspaceArchiveTar.create(source, payload)
            val restored = File(base, "restored")
            WorkspaceArchiveTar.extract(payload, restored)

            assertTrue(Files.isSymbolicLink(File(restored, "bin").toPath()))
            assertTrue(
                "pager must still reach the file it named through bin -> usr/bin",
                File(restored, "etc/alternatives/pager").canonicalFile.endsWith(File("usr/bin/more")),
            )
            assertTrue(
                "the timer link must still reach its unit through lib -> usr/lib",
                File(restored, "etc/systemd/system/timers.target.wants/apt-daily.timer")
                    .canonicalFile.endsWith(File("usr/lib/systemd/system/apt-daily.timer")),
            )
        } finally {
            base.deleteRecursively()
        }
    }
}
