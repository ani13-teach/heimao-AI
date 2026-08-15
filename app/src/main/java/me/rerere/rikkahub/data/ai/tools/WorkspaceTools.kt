package me.rerere.rikkahub.data.ai.tools

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
import me.rerere.rikkahub.utils.generateUnifiedDiff
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.java.KoinJavaComponent.getKoin
import java.io.ByteArrayOutputStream

private const val SHELL_TIMEOUT_MAX_SECONDS = 600L
private const val MAX_READ_FILE_BYTES = 8L * 1024 * 1024
private const val WORKSPACE_ROOT = "/workspace"

val WorkspaceToolDefaultApprovals: Map<String, Boolean> = mapOf(
    "workspace_read_file" to false,
    "workspace_write_file" to false,
    "workspace_edit_file" to false,
    "workspace_shell" to true,
)

fun resolveWorkspaceToolApproval(name: String, overrides: Map<String, Boolean>): Boolean =
    overrides[name] ?: WorkspaceToolDefaultApprovals[name] ?: false

suspend fun createWorkspaceTools(
    workspaceId: String?,
    workspaceRepository: WorkspaceRepository,
    cwd: String? = null,
): List<Tool> {
    if (workspaceId.isNullOrBlank()) return emptyList()
    val approvalOverrides = workspaceRepository.getById(workspaceId)?.toolApprovalOverrides().orEmpty()
    fun needsApproval(name: String) = resolveWorkspaceToolApproval(name, approvalOverrides)

    val shellCwd = normalizeAiWorkspaceCwd(cwd)

    return listOf(
        createReadFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createWriteFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createEditFileTool(workspaceId, ::needsApproval, workspaceRepository),
        createShellTool(workspaceId, ::needsApproval, workspaceRepository, shellCwd),
    )
}

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif", "ico",
)

private fun String.isImagePath(): Boolean =
    substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

private fun createReadFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_read_file",
    description = """
        Read a file from the assistant's bound workspace. Paths must be absolute and remain under /workspace.
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
    needsApproval = { needsApproval("workspace_read_file") },
    execute = {
        val path = normalizeAiWorkspacePath(it.jsonObject.string("path") ?: error("path is required"))
        if (path.absolute.isImagePath()) {
            workspaceRepository.readImageInWorkspace(workspaceId, path)
        } else {
            val text = workspaceRepository.readTextInWorkspace(workspaceId, path)
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("path", path.absolute)
                        put("text", text)
                    }.toString()
                )
            )
        }
    },
)

private fun createWriteFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_write_file",
    description = """
        Write a UTF-8 text file in the assistant's bound workspace. Paths must be absolute and remain under /workspace.
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
    needsApproval = { needsApproval("workspace_write_file") },
    execute = {
        val params = it.jsonObject
        val path = normalizeAiWorkspacePath(params.string("path") ?: error("path is required"))
        val text = params.string("text") ?: error("text is required")
        val overwrite = params["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
        val entry = workspaceRepository.writeTextInWorkspace(workspaceId, path, text, overwrite)
        listOf(UIMessagePart.Text(entry.toJson().toString()))
    },
)

private fun createEditFileTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
) = Tool(
    name = "workspace_edit_file",
    description = """
        Edit a UTF-8 text file in the assistant's bound workspace. Paths must be absolute and remain under /workspace.
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
    needsApproval = { needsApproval("workspace_edit_file") },
    execute = {
        val params = it.jsonObject
        val path = normalizeAiWorkspacePath(params.string("path") ?: error("path is required"))
        val oldText = params.string("old_text") ?: error("old_text is required")
        val newText = params.string("new_text") ?: error("new_text is required")
        val replaceAll = params["replace_all"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
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
        listOf(
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
        )
    },
)

private fun createShellTool(
    workspaceId: String,
    needsApproval: (String) -> Boolean,
    workspaceRepository: WorkspaceRepository,
    defaultCwd: String = "",
) = Tool(
    name = "workspace_shell",
    description = buildString {
        append("Run a shell command with user-file access confined to /workspace. ")
        append("The private Linux Rootfs, external app directories, and host pseudo-filesystems are not mounted for AI commands. ")
        append("A basic read-only Android command runtime is available at /bin and /usr/bin. ")
        append("Use cwd for a path relative to /workspace. ")
        if (defaultCwd.isNotBlank()) {
            append("Defaults to '$defaultCwd'. ")
        }
        append("Requires the workspace shell to be ready.")
    },
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run")
                })
                put("cwd", buildJsonObject {
                    put("type", "string")
                    put(
                        "description",
                        if (defaultCwd.isNotBlank()) {
                            "Working directory relative to /workspace. Defaults to '$defaultCwd'."
                        } else {
                            "Working directory relative to /workspace. Defaults to the workspace root."
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
            },
            required = listOf("command"),
        )
    },
    needsApproval = { needsApproval("workspace_shell") },
    execute = {
        val params = it.jsonObject
        val command = params.string("command") ?: error("command is required")
        val cwd = normalizeAiWorkspaceCwd(params.string("cwd") ?: defaultCwd)
        val timeoutMillis = params.string("timeout")?.toLongOrNull()
            ?.coerceIn(1L, SHELL_TIMEOUT_MAX_SECONDS)
            ?.times(1_000L)
            ?: WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS
        val result = workspaceRepository.executeAiCommand(workspaceId, command, cwd, timeoutMillis)
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("exitCode", result.exitCode)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    if (result.truncated) put("truncated", true)
                }.toString()
            )
        )
    },
)

private fun kotlinx.serialization.json.JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

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

private suspend fun WorkspaceRepository.readImageInWorkspace(
    workspaceId: String,
    path: AiWorkspacePath,
): List<UIMessagePart> {
    val bytes = readWorkspaceBuffer(workspaceId, path).toByteArray()
    val filesManager = getKoin().get<FilesManager>()
    val uris = filesManager.createChatFilesByByteArrays(listOf(bytes))
    return listOf(
        UIMessagePart.Image(url = uris.first().toString()),
        UIMessagePart.Text(
            buildJsonObject {
                put("path", path.absolute)
                put("description", "Image file read successfully")
            }.toString()
        ),
    )
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
