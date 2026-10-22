package me.rerere.rikkahub.data.workspace.python

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceProcessTerminationException
import me.rerere.workspace.WorkspacePythonExecutor
import me.rerere.workspace.WorkspaceShellContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class AndroidWorkspacePythonExecutor(context: Context) : WorkspacePythonExecutor {
    private val application = context.applicationContext

    override fun execute(
        context: WorkspaceShellContext,
        args: List<String>,
        stdin: ByteArray?,
        timeoutMillis: Long,
    ): WorkspaceCommandResult {
        require(timeoutMillis > 0)
        require((stdin?.size ?: 0) <= 12 * 1024 * 1024) { "Python stdin exceeds 12 MiB" }
        require(args.sumOf { it.length.toLong() } <= 8 * 1024 * 1024) { "Python arguments too large" }
        serial.lockInterruptibly()
        try {
            pending?.let {
                if (!it.confirmDeath()) throw WorkspaceProcessTerminationException()
                it.dispose()
                pending = null
            }
            val directory = File(application.noBackupFilesDir, "workspace-python/${UUID.randomUUID()}")
            check(directory.mkdirs())
            val session = Session(application, directory)
            pending = session
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong())
            try {
                val request = JSONObject()
                    .put("args", JSONArray(args))
                    .put("stdin", stdin?.let { Base64.getEncoder().encodeToString(it) } ?: JSONObject.NULL)
                    .put("workspace", context.filesDir.canonicalPath)
                    .put("cwd", context.workingDir.canonicalPath)
                    .put("home", (context.homeDir ?: File(context.filesDir.parentFile, "home")).canonicalPath)
                    .put("tmp", (context.guestTempDir ?: context.tempDir).canonicalPath)
                val bytes = request.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= PythonProtocol.MAX_REQUEST_BYTES) { "Python request exceeds 16 MiB" }
                File(directory, "request.json").writeBytes(bytes)
                session.bind()
                if (!session.connected.await(remaining(deadline), TimeUnit.MILLISECONDS)) {
                    return timeout(session)
                }
                session.failure?.let { throw it }
                session.start()
                if (!session.finished.await(remaining(deadline), TimeUnit.MILLISECONDS)) {
                    return timeout(session)
                }
                session.failure?.let { throw it }
                val resultFile = File(directory, "result.json")
                require(resultFile.length() in 1..PythonProtocol.MAX_RESULT_BYTES.toLong()) {
                    "Invalid Python result size"
                }
                val result = JSONObject(resultFile.readText())
                // Even a successful script can leave native or Python threads running. Reap the
                // process before releasing the workspace and before admitting another request.
                val stopped = session.terminate()
                return WorkspaceCommandResult(
                    exitCode = result.getInt("exitCode"),
                    stdout = result.getString("stdout").take(128 * 1024),
                    stderr = result.getString("stderr").take(128 * 1024),
                    truncated = result.optBoolean("truncated") ||
                        result.getString("stdout").length > 128 * 1024 ||
                        result.getString("stderr").length > 128 * 1024,
                    terminationPending = !stopped,
                )
            } catch (interrupted: InterruptedException) {
                if (!session.terminate()) throw WorkspaceProcessTerminationException(interrupted)
                throw interrupted
            } catch (error: Throwable) {
                if (!session.terminate()) throw WorkspaceProcessTerminationException(error)
                throw error
            } finally {
                if (session.confirmDeath()) {
                    session.dispose()
                    pending = null
                }
            }
        } finally {
            serial.unlock()
        }
    }

    private fun timeout(session: Session): WorkspaceCommandResult = WorkspaceCommandResult(
        exitCode = -1, stdout = "", stderr = "Python execution timed out.\n",
        timedOut = true, terminationPending = !session.terminate(),
    )

    private class Session(private val context: Context, private val directory: File) : ServiceConnection {
        val connected = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private val died = CountDownLatch(1)
        @Volatile var failure: Throwable? = null
        @Volatile private var binder: IBinder? = null
        @Volatile private var cancelling = false
        @Volatile private var bound = false
        private var bindAttempted = false

        fun bind() {
            bindAttempted = true
            bound = context.bindService(
                Intent(context, WorkspacePythonService::class.java), this, Context.BIND_AUTO_CREATE,
            )
            check(bound) { "Unable to bind Python worker" }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            try {
                service.linkToDeath({ died.countDown(); finished.countDown() }, 0)
            } catch (error: android.os.RemoteException) {
                died.countDown()
                failure = error
            }
            connected.countDown()
            if (cancelling) cancel()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            // Only linkToDeath (or an explicit dead Binder) confirms termination.
            if (binder?.isBinderAlive == false) died.countDown()
            finished.countDown()
        }

        override fun onNullBinding(name: ComponentName) {
            failure = IllegalStateException("Python worker returned a null binding")
            connected.countDown()
        }

        override fun onBindingDied(name: ComponentName) {
            failure = IllegalStateException("Python worker binding died")
            connected.countDown()
            finished.countDown()
        }

        fun start() {
            Thread({
                try {
                    val remote = checkNotNull(binder)
                    ParcelFileDescriptor.open(File(directory, "request.json"), ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                        ParcelFileDescriptor.open(File(directory, "result.json"),
                            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                                ParcelFileDescriptor.MODE_READ_WRITE).use { output ->
                            transact(remote, PythonProtocol.RUN) { data ->
                                input.writeToParcel(data, 0)
                                output.writeToParcel(data, 0)
                            }
                        }
                    }
                    while (!cancelling) {
                        if (transact(remote, PythonProtocol.STATUS) {} == 1) break
                        Thread.sleep(20)
                    }
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    finished.countDown()
                }
            }, "workspace-python-binder").apply { isDaemon = true; start() }
        }

        private fun cancel() {
            val remote = binder ?: return
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(PythonProtocol.TOKEN)
                remote.transact(PythonProtocol.CANCEL, data, null, IBinder.FLAG_ONEWAY)
            } catch (_: android.os.RemoteException) {
                if (!remote.isBinderAlive) died.countDown()
            } finally { data.recycle() }
        }

        fun confirmDeath(): Boolean = (!bindAttempted || !bound) || died.count == 0L

        fun terminate(): Boolean {
            cancelling = true
            cancel()
            if (confirmDeath()) return true
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var interrupted = false
            try {
                while (!confirmDeath() && remaining(deadline) > 0) {
                    try { died.await(remaining(deadline), TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            return confirmDeath()
        }

        fun dispose() {
            if (bound) runCatching { context.unbindService(this) }
            directory.deleteRecursively()
        }
    }

    companion object {
        private val serial = ReentrantLock(true)
        // A live/unconfirmed worker keeps all executor instances fail-closed, even after timeout.
        private var pending: Session? = null
        private fun remaining(deadline: Long) = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)

        private fun transact(remote: IBinder, code: Int, write: (Parcel) -> Unit): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(PythonProtocol.TOKEN)
                write(data)
                check(remote.transact(code, data, reply, 0)) { "Unsupported Python worker transaction" }
                reply.readException()
                return reply.readInt()
            } finally { data.recycle(); reply.recycle() }
        }
    }
}
