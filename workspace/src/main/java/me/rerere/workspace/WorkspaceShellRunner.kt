package me.rerere.workspace

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

interface WorkspaceShellRunner {
    fun execute(context: WorkspaceShellContext): WorkspaceCommandResult
}

data class WorkspaceShellContext(
    val root: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val workingDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
    val accessMode: WorkspaceShellAccessMode = WorkspaceShellAccessMode.FULL_ROOTFS,
    val bindMounts: List<WorkspaceBindMount> = emptyList(),
    val rootfsPatchOptions: RootfsPatchOptions = RootfsPatchOptions(),
    /**
     * Host directory bound as the guest HOME. Null keeps the legacy behavior where HOME is the
     * rootfs's own `/root` (private rootfs) or `/workspace` (workspace-only mode).
     */
    val homeDir: File? = null,
    /** Host directory bound as guest `/tmp`. Null keeps the rootfs's own `/tmp`. */
    val guestTempDir: File? = null,
    /** Host directory bound as guest `/var/tmp`. Null keeps the rootfs's own `/var/tmp`. */
    val guestVarTempDir: File? = null,
    /**
     * Whether the runner may patch the rootfs before starting. Must be false for a shared rootfs,
     * which belongs to every workspace and is never modified by an ordinary execution.
     */
    val patchRootfs: Boolean = true,
)

class HostShellRunner : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        require(context.accessMode == WorkspaceShellAccessMode.FULL_ROOTFS) {
            "Restricted workspace commands require a PRoot shell runner"
        }
        val process = ProcessBuilder(defaultShell(), "-c", context.command)
            .directory(context.workingDir)
            .redirectErrorStream(false)
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun defaultShell(): String =
        if (File("/system/bin/sh").exists()) "/system/bin/sh" else "/bin/sh"
}

// 单个流保留的最大字符数, 防止命令疯狂输出导致 OOM 或撑爆 LLM 上下文
const val MAX_OUTPUT_CHARS = 128 * 1024

class WorkspaceProcessTerminationException(cause: Throwable? = null) : IOException(
    "The workspace process has not stopped; environment maintenance remains blocked until the app restarts.",
    cause,
)

fun Process.readResult(timeoutMillis: Long, stdin: ByteArray? = null): WorkspaceCommandResult {
    val stdout = StreamCollector(inputStream)
    val stderr = StreamCollector(errorStream)
    val stdinWriter = stdin?.let { bytes -> StreamWriter(outputStream, bytes) }
    try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        var terminated = true
        if (!finished) {
            destroyForcibly()
            // destroyForcibly() only *requests* termination. Wait for the process to actually exit
            // before returning, but never block the caller forever: if it is still alive after the
            // bounded reap window, report it so the runtime worker keeps the workspace occupied.
            terminated = awaitExit()
        }
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        return WorkspaceCommandResult(
            exitCode = if (finished) exitValue() else -1,
            stdout = stdout.text(),
            stderr = stderr.text(),
            timedOut = !finished,
            truncated = stdout.truncated || stderr.truncated,
            terminationPending = !terminated,
        )
    } catch (e: InterruptedException) {
        // 调用方线程被中断（如协程取消时的 runInterruptible），杀掉进程避免命令继续执行
        destroyForcibly()
        if (!awaitExit()) throw WorkspaceProcessTerminationException(e)
        // 进程被杀后 stdout/stderr 会关闭, 这里 join 回收两个采集线程, 避免每次取消泄漏一对线程
        stdinWriter?.join(1_000)
        stdout.join(1_000)
        stderr.join(1_000)
        throw e
    }
}

/**
 * Blocks until a destroyed process is really gone, so its handles are released before the next
 * caller reuses the same paths, but gives up after a bounded window instead of hanging the caller.
 * Returns true when the process is confirmed gone, false when it is still alive. The interrupt flag
 * is preserved: cancellation still propagates after the bounded wait.
 */
private fun Process.awaitExit(): Boolean {
    if (!isAlive) return true
    var interrupted: InterruptedException? = null
    val deadline = System.nanoTime() + DESTROY_WAIT_TIMEOUT_MS * 1_000_000L
    while (isAlive && System.nanoTime() < deadline) {
        try {
            waitFor(50, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            interrupted = e
        }
    }
    val exited = !isAlive
    if (interrupted != null) Thread.currentThread().interrupt()
    return exited
}

private const val DESTROY_WAIT_TIMEOUT_MS = 5_000L

private class StreamWriter(
    private val stream: java.io.OutputStream,
    private val bytes: ByteArray,
) {
    private val thread = Thread {
        try {
            stream.use { output ->
                output.write(bytes)
                output.flush()
            }
        } catch (_: IOException) {
            // 子进程提前退出或被强杀时 stdin 可能关闭, 忽略即可, 退出状态会由进程本身返回
        }
    }.apply {
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}

private class StreamCollector(
    stream: InputStream,
    private val maxChars: Int = MAX_OUTPUT_CHARS,
) {
    private val builder = StringBuilder()

    @Volatile
    var truncated = false
        private set

    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    // 超出上限后继续读到 EOF 并丢弃，否则管道写满会阻塞子进程导致其无法退出
                    synchronized(builder) {
                        val remaining = maxChars - builder.length
                        if (remaining > 0) {
                            builder.append(buffer, 0, minOf(read, remaining))
                        }
                        if (read > remaining) {
                            truncated = true
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // 进程被强杀（超时/取消）时流会被关闭，阻塞中的 read 会抛 InterruptedIOException 等，
            // 保留已读取的内容即可；不能让异常逃逸，否则会触发线程默认异常处理导致应用崩溃
        }
    }.apply {
        // 设为 daemon: 即使 proot grandchild 残留 fd 导致 read() 永久阻塞, 也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)

    fun text(): String = synchronized(builder) { builder.toString() }
}
