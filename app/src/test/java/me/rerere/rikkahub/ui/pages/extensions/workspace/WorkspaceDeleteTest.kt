package me.rerere.rikkahub.ui.pages.extensions.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.workspace.WorkspaceBusyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceDeleteTest {
    @Test
    fun successfulDeletionHasNoFailure() = runBlocking {
        assertNull(deleteWorkspaceOrFailure { true })
    }

    @Test
    fun missingWorkspaceIsReported() = runBlocking {
        assertEquals(WorkspaceDeleteFailure.FAILED, deleteWorkspaceOrFailure { false })
    }

    @Test
    fun busyWorkspaceDoesNotCrash() = runBlocking {
        assertEquals(WorkspaceDeleteFailure.BUSY, deleteWorkspaceOrFailure {
            throw WorkspaceBusyException("Workspace is busy")
        })
    }

    @Test
    fun otherDeletionErrorsDoNotCrash() = runBlocking {
        assertEquals(WorkspaceDeleteFailure.FAILED, deleteWorkspaceOrFailure {
            throw IllegalStateException("Failed to delete")
        })
    }

    @Test
    fun cancellationIsNotSwallowed() {
        assertThrows(CancellationException::class.java) {
            runBlocking { deleteWorkspaceOrFailure { throw CancellationException() } }
        }
    }
}
