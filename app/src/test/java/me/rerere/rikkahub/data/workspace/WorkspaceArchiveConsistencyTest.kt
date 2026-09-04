package me.rerere.rikkahub.data.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceArchiveConsistencyTest {
    private val workspace = WorkspaceArchiveWorkspaceKey("workspace", "workspace-root")
    private val assistantReference = WorkspaceAssistantReference("assistant", "workspace")
    private val conversationReference = WorkspaceConversationReference("conversation", null)

    private fun consistentState() = WorkspaceArchiveCatalogConsistency(
        stagedDatabaseWorkspaces = listOf(workspace),
        manifestWorkspaces = listOf(workspace),
        settingsAssistantReferences = listOf(assistantReference),
        manifestAssistantReferences = listOf(assistantReference),
        stagedDatabaseConversationReferences = listOf(conversationReference),
        manifestConversationReferences = listOf(conversationReference),
        actualDirectoryRoots = setOf("workspace-root"),
    )

    @Test
    fun `catalog consistency rejects mismatched database manifest references and directories`() {
        assertTrue(workspaceArchiveCatalogIsConsistent(consistentState()))
        assertFalse(
            workspaceArchiveCatalogIsConsistent(
                consistentState().copy(
                    stagedDatabaseWorkspaces = listOf(workspace, WorkspaceArchiveWorkspaceKey("extra", "extra-root")),
                )
            )
        )
        assertFalse(
            workspaceArchiveCatalogIsConsistent(
                consistentState().copy(
                    manifestWorkspaces = listOf(WorkspaceArchiveWorkspaceKey("other", "other-root")),
                )
            )
        )
        assertFalse(
            workspaceArchiveCatalogIsConsistent(
                consistentState().copy(
                    settingsAssistantReferences = listOf(assistantReference.copy(workspaceId = "missing")),
                )
            )
        )
        assertFalse(
            workspaceArchiveCatalogIsConsistent(
                consistentState().copy(
                    stagedDatabaseConversationReferences = listOf(
                        conversationReference.copy(workspaceId = "workspace"),
                    ),
                )
            )
        )
        assertFalse(
            workspaceArchiveCatalogIsConsistent(
                consistentState().copy(actualDirectoryRoots = setOf("workspace-root", "orphan-root")),
            )
        )
    }

    @Test
    fun `new archive conversation references retain ids but clear legacy workspace values`() {
        assertEquals(
            listOf(
                WorkspaceConversationReference("legacy", null),
                WorkspaceConversationReference("current", null),
            ),
            authoritativeArchiveConversationReferences(
                listOf(
                    WorkspaceConversationReference("legacy", "workspace"),
                    WorkspaceConversationReference("current", null),
                )
            ),
        )
    }

    @Test
    fun `new archive consistency rejects legacy conversation workspace values`() {
        assertFalse(
            workspaceArchiveCatalogIsConsistent(
                consistentState().copy(
                    stagedDatabaseConversationReferences = listOf(
                        conversationReference.copy(workspaceId = "workspace"),
                    ),
                    manifestConversationReferences = listOf(
                        conversationReference.copy(workspaceId = "workspace"),
                    ),
                )
            )
        )
    }

    @Test
    fun `closed database action order has no post-close cleanup`() {
        assertTrue(
            workspaceArchivePostCloseActionsAreSafe(
                listOf(
                    WorkspaceArchivePostCloseAction.EXCHANGE_DATABASE,
                    WorkspaceArchivePostCloseAction.SCHEDULE_RELAUNCH,
                    WorkspaceArchivePostCloseAction.EXIT_PROCESS,
                )
            )
        )
        assertFalse(
            workspaceArchivePostCloseActionsAreSafe(
                listOf(
                    WorkspaceArchivePostCloseAction.EXCHANGE_DATABASE,
                    WorkspaceArchivePostCloseAction.EXIT_PROCESS,
                    WorkspaceArchivePostCloseAction.SCHEDULE_RELAUNCH,
                )
            )
        )
    }

    @Test
    fun `restart decision never forces exit for a restore without database`() {
        assertFalse(
            shouldRestartWorkspaceArchive(
                databaseRestoreAttempted = false,
                databaseStatus = WorkspaceArchiveDatabaseRestoreStatus.NOT_REQUESTED,
            )
        )
        assertTrue(
            shouldRestartWorkspaceArchive(
                databaseRestoreAttempted = true,
                databaseStatus = WorkspaceArchiveDatabaseRestoreStatus.ACTIVATED_RESTART_REQUIRED,
            )
        )
        assertTrue(
            shouldRestartWorkspaceArchive(
                databaseRestoreAttempted = true,
                databaseStatus = WorkspaceArchiveDatabaseRestoreStatus.ROLLED_BACK_RESTART_REQUIRED,
            )
        )
        assertTrue(
            shouldRestartWorkspaceArchive(
                databaseRestoreAttempted = true,
                databaseStatus = WorkspaceArchiveDatabaseRestoreStatus.ROOM_CLOSED_NO_SWAP_RESTART_REQUIRED,
            )
        )
    }
}
