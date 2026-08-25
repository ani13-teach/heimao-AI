package me.rerere.rikkahub.data.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.entity.WorkspaceAuditEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceAuditTest {
    private val invocation = WorkspaceAuditInvocation(
        workspaceId = "workspace-a",
        assistantId = "assistant-a",
        conversationId = "conversation-a",
        operation = "shell",
        target = "curl -H 'Authorization: Bearer bearer-secret' " +
            "--api-key=api-secret --password=password-secret " +
            "https://user:url-secret@example.com?token=query-secret",
        cwd = "/workspace/project",
    )

    @Test
    fun `shell audit stores only structured metadata and never command text`() = runBlocking {
        val events = mutableListOf<WorkspaceAuditEvent>()
        val command = "AWS_SECRET=aws-secret echo secret; curl -u user:password https://user:url-secret@example.com"
        val metadata = summarizeWorkspaceShellCommand(command)

        runWorkspaceAudit(
            invocation = invocation.copy(
                target = WORKSPACE_AUDIT_SHELL_TARGET,
                shell = metadata,
            ),
            record = { events += it },
        ) {
            WorkspaceAuditSuccess(value = "done")
        }

        val event = events.single()
        val persisted = listOf(event.target, event.cwd, event.details).joinToString(" ")
        assertEquals(WORKSPACE_AUDIT_SHELL_TARGET, event.target)
        assertTrue(event.details.contains("commandLength"))
        assertTrue(event.details.contains(metadata.commandSha256))
        assertTrue(event.details.contains("echo"))
        listOf("echo secret", "curl -u", "password", "url-secret", "AWS_SECRET").forEach {
            assertFalse("audit contains $it", persisted.contains(it))
        }
        assertEquals(MAX_WORKSPACE_AUDIT_TEXT_CHARS, limitWorkspaceAuditText(
            "x".repeat(MAX_WORKSPACE_AUDIT_TEXT_CHARS + 100)
        ).length)
    }

    @Test
    fun `unknown executable-looking secret is not persisted as executable name`() = runBlocking {
        val events = mutableListOf<WorkspaceAuditEvent>()
        val secret = "personal-access-token-7f3a"

        runWorkspaceAudit(
            invocation = invocation.copy(
                target = "$secret --password='password-secret'",
                shell = null,
            ),
            record = { events += it },
        ) {
            WorkspaceAuditSuccess(value = Unit)
        }

        val event = events.single()
        val persisted = listOf(event.target, event.cwd, event.details).joinToString(" ")
        assertEquals(WORKSPACE_AUDIT_SHELL_TARGET, event.target)
        assertFalse(persisted.contains(secret))
        assertFalse(persisted.contains("password-secret"))
        assertFalse(event.details.contains("commandName"))
    }

    @Test
    fun `direct shell event is normalized before it reaches the storage callback`() {
        val secret = "Bearer-direct-secret-123"
        val event = sanitizeWorkspaceAuditEvent(
            WorkspaceAuditEvent(
                workspaceId = "workspace-a",
                actor = WORKSPACE_AUDIT_ACTOR_ASSISTANT,
                operation = "shell",
                target = "curl -H 'Authorization: $secret'",
                cwd = "/workspace",
                startedAt = 1,
                finishedAt = 2,
                result = WORKSPACE_AUDIT_RESULT_SUCCESS,
                details = "{\"commandName\":\"$secret\"}",
            ),
        )

        val persisted = listOf(event.target, event.cwd, event.details).joinToString(" ")
        assertEquals(WORKSPACE_AUDIT_SHELL_TARGET, event.target)
        assertFalse(persisted.contains(secret))
        assertTrue(event.details.contains("curl"))
    }

    @Test
    fun `successful operation writes success audit and returns value`() = runBlocking {
        val events = mutableListOf<WorkspaceAuditEvent>()

        val value = runWorkspaceAudit(
            invocation = invocation,
            record = { events += it },
        ) {
            WorkspaceAuditSuccess(value = "done", bytes = 12)
        }

        assertEquals("done", value)
        assertEquals(1, events.size)
        assertEquals(WORKSPACE_AUDIT_RESULT_SUCCESS, events.single().result)
        assertEquals(12L, events.single().bytes)
        assertEquals("assistant-a", events.single().assistantId)
        assertFalse(events.single().target.contains("api-secret"))
    }

    @Test
    fun `audit write failure does not change successful operation result`() = runBlocking {
        val value = runWorkspaceAudit(
            invocation = invocation,
            record = { error("database unavailable") },
        ) {
            WorkspaceAuditSuccess(value = "still-done")
        }

        assertEquals("still-done", value)
    }

    @Test
    fun `failed operation writes failure audit and rethrows without message`() = runBlocking {
        val events = mutableListOf<WorkspaceAuditEvent>()

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                runWorkspaceAudit(
                    invocation = invocation,
                    record = { events += it },
                ) {
                    error("file body and password=should-not-be-recorded")
                }
            }
        }

        assertEquals(WORKSPACE_AUDIT_RESULT_FAILURE, events.single().result)
        assertFalse(events.single().details.contains("should-not-be-recorded"))
    }

    @Test
    fun `cancelled operation writes cancel audit and rethrows cancellation`() {
        val events = mutableListOf<WorkspaceAuditEvent>()
        val cancellation = CancellationException("cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                runWorkspaceAudit(
                    invocation = invocation,
                    record = { events += it },
                ) {
                    throw cancellation
                }
            }
        }

        assertEquals(WORKSPACE_AUDIT_RESULT_CANCEL, events.single().result)
        assertTrue(events.single().details.contains("cancelled"))
    }
}
