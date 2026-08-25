package me.rerere.rikkahub.data.workspace

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.WorkspaceAuditEvent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceAuditDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun shellAuditDatabaseDoesNotContainOriginalCommandOrSecrets() = runBlocking {
        val command = "AWS_SECRET=aws-secret echo secret; curl -u user:password https://user:url-secret@example.com"
        val event = WorkspaceAuditEvent(
            workspaceId = "workspace-a",
            actor = WORKSPACE_AUDIT_ACTOR_ASSISTANT,
            operation = "shell",
            target = WORKSPACE_AUDIT_SHELL_TARGET,
            cwd = "/workspace",
            startedAt = 1,
            finishedAt = 2,
            result = WORKSPACE_AUDIT_RESULT_SUCCESS,
            details = shellAuditDetailsForTest(command),
        )
        database.workspaceAuditDao().insert(event)

        val stored = database.workspaceAuditDao().getRecent("workspace-a", 10).single()
        val serialized = listOf(stored.target, stored.cwd, stored.details).joinToString(" ")
        assertEquals(WORKSPACE_AUDIT_SHELL_TARGET, stored.target)
        assertFalse(serialized.contains("echo secret"))
        assertFalse(serialized.contains("curl -u"))
        assertFalse(serialized.contains("AWS_SECRET"))
        assertFalse(serialized.contains("https://user:url-secret@example.com"))
        assertFalse(serialized.contains("password"))
    }

    private fun shellAuditDetailsForTest(command: String): String =
        sanitizeWorkspaceAuditDetails(
            kotlinx.serialization.json.buildJsonObject {
                put("commandLength", command.length)
                put("commandSha256", summarizeWorkspaceShellCommand(command).commandSha256)
                put("commandName", "echo")
            }.toString()
        )
}
