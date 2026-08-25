package me.rerere.rikkahub.data.workspace

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.entity.WorkspaceAuditEvent
import java.security.MessageDigest
import java.util.Locale

const val WORKSPACE_AUDIT_RESULT_SUCCESS = "success"
const val WORKSPACE_AUDIT_RESULT_FAILURE = "failure"
const val WORKSPACE_AUDIT_RESULT_CANCEL = "cancel"
const val WORKSPACE_AUDIT_ACTOR_ASSISTANT = "assistant"

/** Maximum command length accepted by the model-facing workspace shell. */
const val MAX_WORKSPACE_SHELL_COMMAND_CHARS = 16 * 1024

/** Maximum amount of path/cwd text retained in an audit record. */
const val MAX_WORKSPACE_AUDIT_TEXT_CHARS = 4 * 1024

/** Shell audit records use a constant target; the command is never put in this column. */
const val WORKSPACE_AUDIT_SHELL_TARGET = "shell"

private const val AUDIT_TAG = "WorkspaceAudit"
private val JSON = Json { ignoreUnknownKeys = true }
private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
private val COMMAND_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,63}")
private val OPERATION_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,31}")

/**
 * Only well-known command names are retained. A syntax-only check is not enough here: a secret
 * such as `personal-access-token` is also a valid shell word. Unknown or workspace-installed
 * executables still run normally, but their basename is omitted from the audit record.
 */
private val WORKSPACE_AUDIT_EXECUTABLE_ALLOWLIST = setOf(
    "apk", "apt", "apt-cache", "apt-get", "awk", "basename", "bash", "busybox", "cat",
    "cd", "chmod", "chown", "clang", "clang++", "cmake", "command", "cp",
    "cargo", "curl", "cut", "dash", "dirname", "dpkg", "echo", "env", "exec", "false",
    "find", "fgrep", "gcc", "g++", "git", "go", "grep", "gunzip", "gzip", "head",
    "java", "javac", "ln", "ls", "make", "mkdir", "mv", "node", "nohup", "npm", "npx",
    "perl", "pip", "pip3", "pnpm", "printf", "pwd", "python", "python3", "readlink", "realpath",
    "rm", "rmdir", "ruby", "rustc", "sed", "set", "sh", "sort", "source", "sqlite3", "sudo",
    "tail", "tar", "tee", "timeout", "touch", "tr", "true", "unzip", "unset", "uniq", "wc",
    "wget", "which", "xargs", "yarn", "zip", "zsh",
)

private fun isWhitelistedExecutableName(value: String): Boolean =
    value.matches(COMMAND_NAME_PATTERN) &&
        value.lowercase(Locale.US) in WORKSPACE_AUDIT_EXECUTABLE_ALLOWLIST

private fun isShellAuditOperation(operation: String): Boolean =
    operation.trim().equals("shell", ignoreCase = true)

private fun logAuditFailure() {
    // Audit persistence is deliberately best effort. Do not include target, command, details,
    // stdin, output, operation text, or exception text in this log line. A logger failure must
    // not escape either.
    runCatching {
        Log.w(AUDIT_TAG, "Failed to persist workspace audit event")
    }
}

/** Derived shell metadata. It contains no command text. */
data class WorkspaceShellAuditMetadata(
    val commandLength: Int,
    val commandSha256: String,
    val executableName: String? = null,
)

/**
 * Hashes and summarizes a command before it enters the audit layer. The original command remains
 * available only to the executor and is never copied into a [WorkspaceAuditEvent].
 */
fun summarizeWorkspaceShellCommand(command: String): WorkspaceShellAuditMetadata {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(command.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    return WorkspaceShellAuditMetadata(
        commandLength = command.length,
        commandSha256 = digest,
        executableName = firstExecutableName(command),
    )
}

/**
 * Values needed to identify one operation. [target] is a workspace path for file operations. For
 * shell operations it is ignored and replaced by [WORKSPACE_AUDIT_SHELL_TARGET].
 */
data class WorkspaceAuditInvocation(
    val workspaceId: String,
    val assistantId: String?,
    val conversationId: String?,
    val operation: String,
    val target: String,
    val cwd: String = "",
    val actor: String = WORKSPACE_AUDIT_ACTOR_ASSISTANT,
    val shell: WorkspaceShellAuditMetadata? = null,
)

/**
 * Applies the storage boundary for one event. This is intentionally idempotent so both the
 * operation wrapper and the repository can call it: no caller can accidentally persist a raw
 * shell target or an arbitrary details JSON object.
 *
 * A legacy/direct caller may still provide the command in [WorkspaceAuditEvent.target]. Hash it
 * before replacing the target with the constant, so that path cannot bypass the shell policy.
 */
fun sanitizeWorkspaceAuditEvent(event: WorkspaceAuditEvent): WorkspaceAuditEvent {
    val shell = isShellAuditOperation(event.operation)
    val shellMetadata = if (shell && event.target != WORKSPACE_AUDIT_SHELL_TARGET) {
        summarizeWorkspaceShellCommand(event.target)
    } else {
        null
    }
    val operation = event.operation.trim()
    val safeOperation = if (shell) {
        "shell"
    } else {
        operation.takeIf { it.matches(OPERATION_PATTERN) }?.lowercase(Locale.US) ?: "unknown"
    }
    return event.copy(
        operation = safeOperation,
        target = if (shell) {
            WORKSPACE_AUDIT_SHELL_TARGET
        } else {
            limitWorkspaceAuditText(event.target)
        },
        cwd = limitWorkspaceAuditText(event.cwd),
        details = if (shell && shellMetadata != null) {
            shellAuditDetails(shellMetadata, event.details)
        } else {
            sanitizeWorkspaceAuditDetails(event.details)
        },
    )
}

data class WorkspaceAuditSuccess<T>(
    val value: T,
    val result: String = WORKSPACE_AUDIT_RESULT_SUCCESS,
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val bytes: Long? = null,
    /** Only known JSON metadata is retained; operation input/output is never accepted here. */
    val details: String = "{}",
)

/**
 * Runs a workspace operation and records success, failure, or cancellation without allowing an
 * audit failure to alter the operation's result. Cancellation is persisted from a
 * [NonCancellable] context so a cancelled shell/tool still gets a best-effort record.
 */
suspend fun <T> runWorkspaceAudit(
    invocation: WorkspaceAuditInvocation,
    record: suspend (WorkspaceAuditEvent) -> Unit,
    block: suspend () -> WorkspaceAuditSuccess<T>,
): T {
    val startedAt = System.currentTimeMillis()
    val shellMetadata = if (isShellAuditOperation(invocation.operation)) {
        invocation.shell ?: summarizeWorkspaceShellCommand(invocation.target)
    } else {
        null
    }

    suspend fun persist(
        result: String,
        finishedAt: Long = System.currentTimeMillis(),
        exitCode: Int? = null,
        timedOut: Boolean = false,
        bytes: Long? = null,
        details: String = "{}",
    ) {
        val event = sanitizeWorkspaceAuditEvent(
            WorkspaceAuditEvent(
                workspaceId = invocation.workspaceId,
                assistantId = invocation.assistantId,
                conversationId = invocation.conversationId,
                actor = invocation.actor,
                operation = invocation.operation,
                target = if (shellMetadata != null) {
                    WORKSPACE_AUDIT_SHELL_TARGET
                } else {
                    invocation.target
                },
                cwd = invocation.cwd,
                startedAt = startedAt,
                finishedAt = finishedAt,
                result = result,
                exitCode = exitCode,
                timedOut = timedOut,
                bytes = bytes,
                details = if (shellMetadata != null) {
                    shellAuditDetails(shellMetadata, details)
                } else {
                    details
                },
            ),
        )
        withContext(NonCancellable) {
            runCatching { record(event) }
                .onFailure { logAuditFailure() }
        }
    }

    return try {
        val success = block()
        persist(
            result = success.result,
            exitCode = success.exitCode,
            timedOut = success.timedOut,
            bytes = success.bytes,
            details = success.details,
        )
        success.value
    } catch (error: CancellationException) {
        persist(
            result = WORKSPACE_AUDIT_RESULT_CANCEL,
            details = buildJsonObject { put("cancelled", true) }.toString(),
        )
        throw error
    } catch (error: Throwable) {
        // Keep only the exception type. Exception messages can contain file contents, command
        // output, credentials, or environment values supplied by a workspace program.
        persist(
            result = WORKSPACE_AUDIT_RESULT_FAILURE,
            details = buildJsonObject {
                put("errorType", error::class.simpleName ?: "Unknown")
            }.toString(),
        )
        throw error
    }
}

/**
 * Validates a structured audit-details object. This is intentionally allow-list based: there is
 * no generic regex replacement followed by saving the original value, so a new caller cannot
 * accidentally persist a command or a secret by passing it through this function.
 */
fun sanitizeWorkspaceAuditDetails(value: String): String {
    val source = runCatching { JSON.parseToJsonElement(value).jsonObject }.getOrNull() ?: return "{}"
    return buildJsonObject {
        source.boolean("cancelled")?.let { put("cancelled", it) }
        source.boolean("truncated")?.let { put("truncated", it) }
        source.boolean("overwrite")?.let { put("overwrite", it) }
        source.boolean("replaceAll")?.let { put("replaceAll", it) }
        source.int("replacements")?.takeIf { it >= 0 }?.let { put("replacements", it) }
        // Keep the measured length even for a rejected/over-limit command. The shell executor
        // enforces MAX_WORKSPACE_SHELL_COMMAND_CHARS separately; the audit must not silently
        // turn a length measurement into a different value.
        source.int("commandLength")?.takeIf { it >= 0 }?.let {
            put("commandLength", it)
        }
        source.string("commandSha256")?.takeIf { it.matches(SHA256_PATTERN) }?.let {
            put("commandSha256", it.lowercase(Locale.US))
        }
        val executableName = source.string("executableName") ?: source.string("commandName")
        executableName?.takeIf(::isWhitelistedExecutableName)?.let {
            // commandName is retained as the stable on-disk key for existing audit rows.
            put("commandName", it)
        }
        source.string("errorType")?.takeIf { it.matches(ERROR_TYPE_PATTERN) }?.let {
            put("errorType", it)
        }
    }.toString()
}

/** Simple length limiting is safe for paths; it is not a credential redaction mechanism. */
fun limitWorkspaceAuditText(value: String, maxLength: Int = MAX_WORKSPACE_AUDIT_TEXT_CHARS): String {
    if (value.isEmpty() || maxLength <= 0) return ""
    return if (value.length <= maxLength) value else value.take(maxLength - 1) + '…'
}

private fun shellAuditDetails(
    metadata: WorkspaceShellAuditMetadata,
    extra: String,
): String {
    val details = buildJsonObject {
        put("commandLength", metadata.commandLength.coerceAtLeast(0))
        metadata.commandSha256
            .takeIf { it.matches(SHA256_PATTERN) }
            ?.let { put("commandSha256", it.lowercase(Locale.US)) }
        metadata.executableName
            ?.takeIf(::isWhitelistedExecutableName)
            ?.let { put("commandName", it) }
        val safeExtra = runCatching { JSON.parseToJsonElement(extra).jsonObject }.getOrNull()
        safeExtra?.boolean("cancelled")?.let { put("cancelled", it) }
        safeExtra?.boolean("truncated")?.let { put("truncated", it) }
        safeExtra?.string("errorType")?.takeIf { it.matches(ERROR_TYPE_PATTERN) }?.let {
            put("errorType", it)
        }
    }
    return sanitizeWorkspaceAuditDetails(details.toString())
}

private fun firstExecutableName(command: String): String? {
    val tokens = shellWords(command)
    for (token in tokens) {
        if (isShellAssignment(token)) continue
        val candidate = token.substringAfterLast('/').trim('"', '\'')
        return candidate.takeIf(::isWhitelistedExecutableName)
    }
    return null
}

/** A deliberately small lexer used only to derive a whitelisted command basename. */
private fun shellWords(command: String): List<String> {
    val result = mutableListOf<String>()
    var index = 0
    while (index < command.length) {
        while (index < command.length && command[index].isWhitespace()) index++
        if (index >= command.length) break
        if (command[index] in ";|&<>") break
        val token = StringBuilder()
        var quote: Char? = null
        while (index < command.length) {
            val char = command[index]
            if (quote == null && char.isWhitespace()) break
            if (quote == null && char in ";|&<>") break
            when {
                char == '\\' && index + 1 < command.length -> {
                    token.append(command[index + 1])
                    index += 2
                }
                quote != null && char == quote -> {
                    quote = null
                    index++
                }
                quote == null && (char == '\'' || char == '"') -> {
                    quote = char
                    index++
                }
                else -> {
                    token.append(char)
                    index++
                }
            }
        }
        if (token.isNotEmpty()) result += token.toString()
        while (index < command.length && command[index].isWhitespace()) index++
        if (index < command.length && command[index] in ";|&<>") break
    }
    return result
}

private fun isShellAssignment(token: String): Boolean {
    val equals = token.indexOf('=')
    if (equals <= 0) return false
    val name = token.substring(0, equals)
    return name.firstOrNull()?.let { it == '_' || it.isLetter() } == true &&
        name.all { it == '_' || it.isLetterOrDigit() }
}

private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private val ERROR_TYPE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.$]{0,127}")
