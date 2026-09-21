package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.DiffMetadata
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.workspace.AssistantWorkspaceAccessGate
import me.rerere.rikkahub.data.workspace.MAX_WORKSPACE_SHELL_COMMAND_CHARS
import me.rerere.rikkahub.data.workspace.WorkspaceAuditInvocation
import me.rerere.rikkahub.data.workspace.WorkspaceAuditSuccess
import me.rerere.rikkahub.data.workspace.WORKSPACE_AUDIT_RESULT_FAILURE
import me.rerere.rikkahub.data.workspace.WORKSPACE_AUDIT_RESULT_SUCCESS
import me.rerere.rikkahub.data.workspace.WORKSPACE_AUDIT_SHELL_TARGET
import me.rerere.rikkahub.data.workspace.runWorkspaceAudit
import me.rerere.rikkahub.data.workspace.summarizeWorkspaceShellCommand
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = WorkspaceManager.MAX_COMMAND_TIMEOUT_MS / 1_000L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val WORKSPACE_ROOT = "/workspace"

internal const val WORKSPACE_TOOL_REQUIRES_APPROVAL = false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
    assistantId: String? = null,
    conversationId: String? = null,
    accessGate: AssistantWorkspaceAccessGate,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    require(!assistantId.isNullOrBlank() && !conversationId.isNullOrBlank()) {
        "Workspace tools require an assistant and conversation owner"
    }
    val shellCwd = normalizeAiWorkspaceCwd(cwd)

    return listOf(
        createReadFileTool(workspaceId, workspaceRepository, assistantId, conversationId, shellCwd, accessGate),
        createWriteFileTool(workspaceId, workspaceRepository, assistantId, conversationId, shellCwd, accessGate),
        createEditFileTool(workspaceId, workspaceRepository, assistantId, conversationId, shellCwd, accessGate),
        createShellTool(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            defaultCwd = shellCwd,
            assistantId = assistantId,
            conversationId = conversationId,
            accessGate = accessGate,
        ),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    assistantId: String,
    conversationId: String,
    auditCwd: String,
    accessGate: AssistantWorkspaceAccessGate,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file from the current conversation's workspace. Paths must be absolute and remain under /workspace.
        Supports UTF-8 text files and image files (png, jpg, jpeg, gif, webp, bmp, svg, heic, heif, avif, ico).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty()
            },
            required = listOf("path"),
        )
    },
    needsApproval = { WORKSPACE_TOOL_REQUIRES_APPROVAL },
    execute = { input ->
        accessGate.withWorkspaceAccess(assistantId, conversationId, workspaceId) {
            val params = input.jsonObject
        val rawPath = params.string("path").orEmpty()
        runWorkspaceAudit(
            invocation = WorkspaceAuditInvocation(
                workspaceId = workspaceId,
                assistantId = assistantId,
                conversationId = conversationId,
                operation = "read",
                target = rawPath,
                cwd = auditCwd,
            ),
            record = workspaceRepository::recordWorkspaceAudit,
        ) {
            val path = normalizeAiWorkspacePath(params.string("path") ?: error("path is required"))
            val buffer = workspaceRepository.readWorkspaceBuffer(workspaceId, path)
            val bytes = buffer.size().toLong()
            val parts = if (path.absolute.isImagePath()) {
                val filesManager = getKoin().get<FilesManager>()
                val uris = filesManager.createChatFilesByByteArrays(listOf(buffer.toByteArray()))
                listOf(
                    UIMessagePart.Image(url = uris.first().toString()),
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path.absolute)
                            put("description", "Image file read successfully")
                        }.toString()
                    ),
                )
            } else {
                val text = buffer.toString(Charsets.UTF_8.name())
                listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("path", path.absolute)
                            put("text", text)
                        }.toString()
                    )
                )
            }
            WorkspaceAuditSuccess(value = parts, bytes = bytes)
        }
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    assistantId: String,
    conversationId: String,
    auditCwd: String,
    accessGate: AssistantWorkspaceAccessGate,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file in the current conversation's workspace. Paths must be absolute and remain under /workspace.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty()
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "UTF-8 text content to write")
                })
                put("overwrite", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to overwrite an existing file. Defaults to true.")
                })
            },
            required = listOf("path", "text"),
        )
    },
    needsApproval = { WORKSPACE_TOOL_REQUIRES_APPROVAL },
    execute = { input ->
        accessGate.withWorkspaceAccess(assistantId, conversationId, workspaceId) {
            val params = input.jsonObject
        val rawPath = params.string("path").orEmpty()
        val overwrite = params.boolean("overwrite", default = true)
        runWorkspaceAudit(
            invocation = WorkspaceAuditInvocation(
                workspaceId = workspaceId,
                assistantId = assistantId,
                conversationId = conversationId,
                operation = "write",
                target = rawPath,
                cwd = auditCwd,
            ),
            record = workspaceRepository::recordWorkspaceAudit,
        ) {
            val path = normalizeAiWorkspacePath(params.string("path") ?: error("path is required"))
            val text = params.string("text") ?: error("text is required")
            val entry = workspaceRepository.writeTextInWorkspace(workspaceId, path, text, overwrite)
            WorkspaceAuditSuccess(
                value = listOf(UIMessagePart.Text(entry.toJson().toString())),
                bytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
                details = buildJsonObject { put("overwrite", overwrite) }.toString(),
            )
        }
        }
    },
)

private fun createEditFileTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    assistantId: String,
    conversationId: String,
    auditCwd: String,
    accessGate: AssistantWorkspaceAccessGate,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file in the current conversation's workspace. Paths must be absolute and remain under /workspace.
        Provide old_text and new_text. By default old_text must occur exactly once; set replace_all=true to replace every occurrence.
        If no exact match is found, whitespace-tolerant line matching is attempted automatically.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putPathProperty()
                put("old_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact text to replace")
                })
                put("new_text", buildJsonObject {
                    put("type", "string")
                    put("description", "Replacement text")
                })
                put("replace_all", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Whether to replace every occurrence. Defaults to false.")
                })
            },
            required = listOf("path", "old_text", "new_text"),
        )
    },
    needsApproval = { WORKSPACE_TOOL_REQUIRES_APPROVAL },
    execute = { input ->
        accessGate.withWorkspaceAccess(assistantId, conversationId, workspaceId) {
            val params = input.jsonObject
        val rawPath = params.string("path").orEmpty()
        val replaceAll = params.boolean("replace_all", default = false)
        runWorkspaceAudit(
            invocation = WorkspaceAuditInvocation(
                workspaceId = workspaceId,
                assistantId = assistantId,
                conversationId = conversationId,
                operation = "edit",
                target = rawPath,
                cwd = auditCwd,
            ),
            record = workspaceRepository::recordWorkspaceAudit,
        ) {
            val path = normalizeAiWorkspacePath(params.string("path") ?: error("path is required"))
            val oldText = params.string("old_text") ?: error("old_text is required")
            val newText = params.string("new_text") ?: error("new_text is required")
            require(oldText.isNotEmpty()) { "old_text must not be empty" }

            val original = workspaceRepository.readTextInWorkspace(workspaceId, path)
            val result = try {
                replaceText(original, oldText, newText, replaceAll)
            } catch (e: IllegalArgumentException) {
                error("${e.message} (path: ${path.absolute})")
            }
            val entry = workspaceRepository.writeTextInWorkspace(
                workspaceId = workspaceId,
                path = path,
                text = result.updated,
                overwrite = true,
            )
            val diff = generateUnifiedDiff(original, result.updated, entry.path)
            WorkspaceAuditSuccess(
                value = listOf(
                    UIMessagePart.Text(
                        text = buildJsonObject {
                            put("path", entry.path)
                            put("replacements", result.replacements)
                            if (result.strategy != ExactReplacer.name) put("matchStrategy", result.strategy)
                            put("sizeBytes", entry.sizeBytes)
                            put("updatedAt", entry.updatedAt)
                        }.toString(),
                        metadata = diff?.let { value -> DiffMetadata(diff = value).toMetadata() },
                    )
                ),
                bytes = entry.sizeBytes,
                details = buildJsonObject {
                    put("replacements", result.replacements)
                    put("replaceAll", replaceAll)
                }.toString(),
            )
        }
        }
    },
)

internal fun workspaceShellDescription(defaultCwd: String = ""): String = buildString {
    append("Run a shell command in the current workspace's selected Linux environment. ")
    append("The working directory is always /workspace or one of its subdirectories, and user files are confined to /workspace. ")
    append("Package mode provides the workspace's full Linux Rootfs, so installed tools such as python3, pip, npm, Go, and Cargo can be used when present. ")
    append("Sandbox mode is intentionally minimal and only provides basic shell/toybox commands; python3, pip, bash, and other full Linux tools are unavailable there. ")
    append("The Linux environment may be shared with other workspaces. Use the public environment maintenance UI for system-wide package installation, upgrades, and removal. ")
    append("For project-specific Python dependencies use python3 -m venv /workspace/.venv and /workspace/.venv/bin/python -m pip; use --system-site-packages only when the project should inherit public libraries. ")
    append("The workspace does not mount or expose /skills, /upload, /tool_outputs, /proc, /sys, or external app directories. ")
    append("The PRoot guest root is not Android host root. ")
    if (defaultCwd.isNotBlank()) {
        append("Defaults to '$defaultCwd'. ")
    }
    append("Workspace tools do not require per-call approval. Commands are limited to $MAX_WORKSPACE_SHELL_COMMAND_CHARS characters. Requires the workspace shell to be ready.")
}

internal fun workspaceShellParametersSchema(defaultCwd: String = ""): JsonObject = buildJsonObject {
    put("command", buildJsonObject {
        put("type", "string")
        put("description", "Shell command to run (maximum $MAX_WORKSPACE_SHELL_COMMAND_CHARS characters)")
    })
    put("cwd", buildJsonObject {
        put("type", "string")
        put(
            "description",
            if (defaultCwd.isNotBlank()) {
                "Working directory under /workspace; accepts a relative path or an absolute /workspace path. Defaults to '$defaultCwd'."
            } else {
                "Working directory under /workspace; accepts a relative path or an absolute /workspace path. Defaults to /workspace."
            }
        )
    })
    put("timeout", buildJsonObject {
        put("type", "integer")
        put(
            "description",
            "Command timeout in seconds. Defaults to 30, max $SHELL_TIMEOUT_MAX_SECONDS."
        )
    })
}

internal fun validateWorkspaceShellCommand(command: String): String {
    require(command.isNotBlank()) { "command is required" }
    require(command.length <= MAX_WORKSPACE_SHELL_COMMAND_CHARS) {
        "command is too long (max $MAX_WORKSPACE_SHELL_COMMAND_CHARS characters)"
    }
    return command
}

private fun createShellTool(
    workspaceId: String,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String = "",
    assistantId: String,
    conversationId: String,
    accessGate: AssistantWorkspaceAccessGate,
) = Tool(
    name = "workspace_shell",
    description = workspaceShellDescription(defaultCwd),
    parameters = {
        InputSchema.Obj(
            properties = workspaceShellParametersSchema(defaultCwd),
            required = listOf("command"),
        )
    },
    needsApproval = { WORKSPACE_TOOL_REQUIRES_APPROVAL },
    execute = { input ->
        accessGate.withWorkspaceAccess(assistantId, conversationId, workspaceId) {
            val params = input.jsonObject
        val rawCommand = params.string("command").orEmpty()
        val rawCwd = params.string("cwd") ?: defaultCwd
        val auditCwd = runCatching { normalizeAiWorkspaceCwd(rawCwd) }.getOrDefault("")
        val shellAudit = summarizeWorkspaceShellCommand(rawCommand)
        runWorkspaceAudit(
            invocation = WorkspaceAuditInvocation(
                workspaceId = workspaceId,
                assistantId = assistantId,
                conversationId = conversationId,
                operation = "shell",
                target = WORKSPACE_AUDIT_SHELL_TARGET,
                cwd = auditCwd,
                shell = shellAudit,
            ),
            record = workspaceRepository::recordWorkspaceAudit,
        ) {
            val command = validateWorkspaceShellCommand(
                params.string("command") ?: error("command is required")
            )
            val cwd = normalizeAiWorkspaceCwd(rawCwd)
            val timeoutMillis = params.string("timeout")?.toLongOrNull()
                ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
                ?.times(1_000L)
                ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
            val result = workspaceRepository.executeAiPackageCommand(workspaceId, command, cwd, timeoutMillis)
            val outputBytes = (
                result.stdout.toByteArray(Charsets.UTF_8).size +
                    result.stderr.toByteArray(Charsets.UTF_8).size
                ).toLong()
            WorkspaceAuditSuccess(
                value = listOf(
                    UIMessagePart.Text(
                        buildJsonObject {
                            put("exitCode", result.exitCode)
                            put("stdout", result.stdout)
                            put("stderr", result.stderr)
                            put("timedOut", result.timedOut)
                            if (result.truncated) put("truncated", true)
                        }.toString()
                    )
                ),
                result = if (result.exitCode == 0 && !result.timedOut) {
                    WORKSPACE_AUDIT_RESULT_SUCCESS
                } else {
                    WORKSPACE_AUDIT_RESULT_FAILURE
                },
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                bytes = outputBytes,
                details = buildJsonObject {
                    if (result.truncated) put("truncated", true)
                }.toString(),
            )
        }
        }
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? = runCatching {
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
}.getOrNull()

private fun kotlinx.serialization.json.JsonObject.boolean(name: String, default: Boolean): Boolean =
    runCatching {
        this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    }.getOrNull() ?: default

private suspend fun WorkspaceRepository.readTextInWorkspace(
    workspaceId: String,
    path: AiWorkspacePath,
): String = readWorkspaceBuffer(workspaceId, path).toString(Charsets.UTF_8.name())

private suspend fun WorkspaceRepository.readWorkspaceBuffer(
    workspaceId: String,
    path: AiWorkspacePath,
): ByteArrayOutputStream {
    val size = fileSize(workspaceId, WorkspaceStorageArea.FILES, path.relative)
    require(size <= MAX_READ_FILE_BYTES) {
        "File is too large to read: ${path.absolute} (${size / 1024 / 1024}MB, max ${MAX_READ_FILE_BYTES / 1024 / 1024}MB). Use shell commands like head, tail, or grep to read parts of it."
    }
    return ByteArrayOutputStream(size.toInt()).also { output ->
        exportFile(workspaceId, WorkspaceStorageArea.FILES, path.relative, output)
    }
}

private suspend fun WorkspaceRepository.writeTextInWorkspace(
    workspaceId: String,
    path: AiWorkspacePath,
    text: String,
    overwrite: Boolean,
): WorkspaceFileEntry = writeText(
    id = workspaceId,
    path = path.relative,
    text = text,
    overwrite = overwrite,
).withWorkspaceAbsolutePath()

private fun WorkspaceFileEntry.withWorkspaceAbsolutePath(): WorkspaceFileEntry {
    val relative = path.replace('\\', '/').trimStart('/')
    return copy(path = if (relative.isBlank()) WORKSPACE_ROOT else "$WORKSPACE_ROOT/$relative")
}

internal data class AiWorkspacePath(
    val absolute: String,
    val relative: String,
)

internal fun normalizeAiWorkspacePath(rawPath: String): AiWorkspacePath {
    val path = rawPath.replace('\\', '/').trim()
    require(path.isNotBlank()) { "path is required" }
    require(!path.contains('\u0000')) { "path contains invalid character" }
    require(path == WORKSPACE_ROOT || path.startsWith("$WORKSPACE_ROOT/")) {
        "path must remain inside $WORKSPACE_ROOT"
    }
    val relative = normalizeRelativeWorkspacePath(path.removePrefix(WORKSPACE_ROOT).trimStart('/'))
    return AiWorkspacePath(
        absolute = if (relative.isBlank()) WORKSPACE_ROOT else "$WORKSPACE_ROOT/$relative",
        relative = relative,
    )
}

internal fun normalizeAiWorkspaceCwd(rawPath: String?): String {
    val path = rawPath?.replace('\\', '/')?.trim().orEmpty()
    if (path.isBlank()) return ""
    require(!path.contains('\u0000')) { "cwd contains invalid character" }
    return if (path.startsWith('/')) {
        normalizeAiWorkspacePath(path).relative
    } else {
        normalizeRelativeWorkspacePath(path)
    }
}

private fun normalizeRelativeWorkspacePath(path: String): String {
    val segments = path.split('/').filter { it.isNotBlank() && it != "." }
    require(segments.none { it == ".." }) { "path must remain inside $WORKSPACE_ROOT" }
    return segments.joinToString("/")
}

private fun JsonObjectBuilder.putPathProperty() {
    put("path", buildJsonObject {
        put("type", "string")
        put("description", "Absolute path under /workspace, for example /workspace/notes.md.")
    })
}

private fun WorkspaceFileEntry.toJson() = buildJsonObject {
    put("path", path)
    put("name", name)
    put("isDirectory", isDirectory)
    put("sizeBytes", sizeBytes)
    put("updatedAt", updatedAt)
}
