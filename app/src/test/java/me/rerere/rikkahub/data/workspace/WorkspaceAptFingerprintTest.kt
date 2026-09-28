package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceAptFingerprintTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `dpkg and source changes invalidate a public apt plan without exposing their contents`() {
        val root = temp.newFolder("rootfs")
        val status = File(root, "var/lib/dpkg/status").apply { parentFile!!.mkdirs(); writeText("Package: example\n") }
        val source = File(root, "etc/apt/sources.list.d/custom.sources").apply {
            parentFile!!.mkdirs()
            writeText("URIs: https://user:secret@example.org/\n")
        }
        val initial = sharedAptFingerprint(root)
        assertEquals(initial, sharedAptFingerprint(root))
        assertFalse(initial.contains("secret"))
        status.appendText("Version: 2\n")
        val changed = sharedAptFingerprint(root)
        assertNotEquals(initial, changed)
        source.writeText("URIs: https://user:different@example.org/\n")
        assertNotEquals(changed, sharedAptFingerprint(root))
    }

    @Test fun `package index metadata invalidates a plan`() {
        val root = temp.newFolder("rootfs-index")
        val lists = File(root, "var/lib/apt/lists").apply { mkdirs() }
        val before = sharedAptFingerprint(root)
        File(lists, "example_Packages").writeText("Packages list")
        assertNotEquals(before, sharedAptFingerprint(root))
    }
}
