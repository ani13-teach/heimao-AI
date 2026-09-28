package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/**
 * Workspace 系统提示注入转换器
 *
 * 当会话记录了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 * 同时读取 ~/.agents、/workspace 和会话当前目录中的 AGENTS.md 作为工作区指令。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.workspaceId ?: return messages
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatService.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages

        val prompt = buildWorkspacePrompt(workspace, ctx.workspaceCwd) +
            buildAgentsPrompt(workspaceId, ctx.workspaceCwd)

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }

    private suspend fun buildAgentsPrompt(workspaceId: String, cwd: String?): String {
        // ProotShellRunner 把 HOME 定为 /root；相对 cwd 按 /workspace 解析。
        val workingDirectory = Paths.get("/workspace")
            .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "/root/.agents/AGENTS.md",
            "/workspace/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        val instructions = paths.mapNotNull { path ->
            try {
                val size = workspaceRepository.rootfsFileSize(workspaceId, path)
                require(size <= MAX_AGENTS_BYTES) { "AGENTS.md exceeds $MAX_AGENTS_BYTES bytes" }
                val content = ByteArrayOutputStream().use { output ->
                    workspaceRepository.exportRootfsFile(workspaceId, path, output)
                    output.toString(Charsets.UTF_8.name())
                }
                content.takeIf { it.isNotBlank() }?.let { path to it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 文件不存在、太大或读不了都直接跳过，不影响本轮对话
                Log.d("WorkspaceReminder", "Skipping workspace instructions: $path", e)
                null
            }
        }
        if (instructions.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            appendLine("<workspace_instructions>")
            appendLine("Follow the AGENTS.md instructions below.")
            instructions.forEach { (path, content) ->
                appendLine()
                appendLine("AGENTS.md source: $path")
                appendLine(content)
            }
            append("</workspace_instructions>")
        }
    }

    private companion object {
        const val MAX_AGENTS_BYTES = 64L * 1024
    }
}

private fun buildWorkspacePrompt(workspace: WorkspaceEntity, cwd: String? = null): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\".")
    appendLine("- Your file access is confined to `/workspace`; files written there persist across turns of this conversation.")
    appendLine("- Every path passed to a workspace file tool must be absolute and start with `/workspace` (for example `/workspace/notes.md`).")
    appendLine("- `workspace_shell` uses a full development shell only in a private Linux environment. In a shared environment it uses a minimal files-only sandbox without public system packages, Python, pip or bash. Its working directory is always `/workspace` or a subdirectory.")
    appendLine("- Public packages affect every workspace using that environment. Use the public environment maintenance UI for system-wide installation, upgrades, and removal; use a private environment for project dependencies requiring a full Linux shell.")
    appendLine("- To change public apt packages, use `workspace_apt_plan` first and then `workspace_apt_execute` with the plan ID and fingerprint; execution waits for explicit user confirmation of the simulated changes. Do not use `workspace_shell` for public package management.")
    appendLine("- In a private environment, use `python3 -m venv /workspace/.venv` and `/workspace/.venv/bin/python -m pip` for project-specific Python dependencies. A private Rootfs does not inherit packages installed in the public Rootfs.")
    appendLine("- The AI workspace channel does not mount or expose `/skills`, `/upload`, `/tool_outputs`, `/proc`, `/sys`, or external app directories. The PRoot guest root is not Android host root.")
    appendLine("- Workspace file and shell tools are internal workspace operations and do not require per-call approval.")
    appendLine("- Keep all user-file operations under `/workspace`.")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_shell`: run commands and install project-local tools; pass `cwd` as a relative path or an absolute path under `/workspace`.")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
