package me.rerere.rikkahub.data.log

import android.content.Context
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import java.io.File
import java.io.FileOutputStream
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.utils.JsonInstant

/** 普通文字字段（工具名、报错原因）的长度上限。 */
private const val MAX_TEXT_CHARS = 500

/** 崩溃堆栈的长度上限。 */
private const val MAX_STACK_CHARS = 4_000

/**
 * 脱敏用的模式。报错文字里可能回显 key、token 或口令，落盘前先抹掉。
 * 只认常见形状，宁可少抹也不把正常文字整段吃掉。
 */
private val SECRET_PATTERNS = listOf(
    Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{8,}"""),
    Regex(
        """(?i)(?:api[_-]?key|apikey|access[_-]?token|auth[_-]?token|refresh[_-]?token|token|password|passwd|secret)""" +
            """["']?\s*[:=]\s*["']?[A-Za-z0-9._\-]{8,}"""
    ),
    Regex("""\b(?:sk|pk|rk|ghp|gho|ghu|ghs|glpat|xox[abprs])[-_A-Za-z0-9]{12,}"""),
    Regex("""\bAIza[A-Za-z0-9_\-]{20,}"""),
)

/** 上游报错码：MCP 自带 code，其他情况从报错文字里认 HTTP 状态码。 */
private val HTTP_STATUS_PATTERN = Regex("""(?i)\b(?:http|status|code)[^\d]{0,4}(\d{3})\b""")

private const val REDACTED = "[REDACTED]"

/**
 * 先脱敏再截断。顺序不能反：先截断会把 key 切成半截，模式就认不出来了。
 */
internal fun sanitizeLogText(text: String, maxChars: Int = MAX_TEXT_CHARS): String {
    val redacted = SECRET_PATTERNS.fold(text) { acc, pattern -> pattern.replace(acc, REDACTED) }
    return if (redacted.length <= maxChars) redacted else redacted.take(maxChars)
}

/** 从异常里取上游报错码，拿不到返回 null。 */
internal fun extractUpstreamCode(error: Throwable): String? {
    generateSequence(error) { it.cause }.forEach { cause ->
        if (cause is McpException) return cause.code.toString()
    }
    val message = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
    return HTTP_STATUS_PATTERN.find(message)?.groupValues?.get(1)
}

/**
 * 日志文件的落盘原语。刻意不做依赖注入：崩溃线程也要能写，拿不到 Koin 图。
 *
 * 追加用单次 `write`（O_APPEND），不加锁：崩溃分支如果卡在别人持有的锁上，
 * 崩溃处理本身就会挂住。读取侧容忍坏行，所以并发追加不会把日志读崩。
 */
object ChatLogFile {
    private const val FILE_NAME = "chat_log.jsonl"

    fun file(context: Context): File = File(File(context.filesDir, FileFolders.LOGS), FILE_NAME)

    /** 追加一条日志。任何失败都吞掉：日志不能反过来影响调用方。 */
    fun append(context: Context, entry: ChatLogEntry) {
        val encoded = JsonInstant.encodeToString<ChatLogEntry>(entry)
        runCatching {
            val file = file(context)
            file.parentFile?.mkdirs()
            FileOutputStream(file, /* append = */ true).use { output ->
                output.write((encoded + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
        }
    }

    /** 崩溃时同步写入。等级设为「关」也照写：崩溃是最该留痕的一类。 */
    fun appendCrash(context: Context, threadName: String, error: Throwable) {
        append(
            context,
            ChatLogEntry.Crash(
                timestamp = System.currentTimeMillis(),
                threadName = threadName,
                errorType = error.javaClass.name,
                stackTrace = sanitizeLogText(error.stackTraceToString(), MAX_STACK_CHARS),
            )
        )
    }

    /** 读出全部日志。坏行直接跳过，不让一行损坏毁掉整份日志。 */
    fun read(context: Context): List<ChatLogEntry> {
        val file = file(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines(Charsets.UTF_8) { lines ->
                lines.mapNotNull { line ->
                    if (line.isBlank()) {
                        null
                    } else {
                        runCatching { JsonInstant.decodeFromString<ChatLogEntry>(line) }.getOrNull()
                    }
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    /** 整体重写。先写临时文件再改名，避免重写中途失败留下半份日志。 */
    fun writeAll(context: Context, entries: List<ChatLogEntry>) {
        runCatching {
            val file = file(context)
            val parent = file.parentFile ?: return
            parent.mkdirs()
            val temp = File(parent, "$FILE_NAME.tmp")
            temp.writeText(
                entries.joinToString(separator = "\n", postfix = if (entries.isEmpty()) "" else "\n") {
                    JsonInstant.encodeToString<ChatLogEntry>(it)
                },
                Charsets.UTF_8,
            )
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        }
    }

    fun delete(context: Context) {
        runCatching { file(context).delete() }
    }
}
