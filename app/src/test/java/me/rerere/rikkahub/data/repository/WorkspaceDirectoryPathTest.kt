package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceDirectoryPathTest {
    @Test
    fun `workspace directory normalization accepts root and descendants`() {
        assertEquals("", normalizeWorkspaceDirectoryPath("/workspace/"))
        assertEquals("project/src", normalizeWorkspaceDirectoryPath("\\workspace\\project\\src"))
    }

    @Test
    fun `workspace directory normalization rejects traversal and adjacent roots`() {
        listOf("/workspace/../other", "/workspace/./src", "/workspace-other", "/root").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizeWorkspaceDirectoryPath(path)
            }
        }
    }
}
