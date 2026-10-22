package me.rerere.rikkahub.data.workspace.python

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal object PythonProtocol {
    const val TOKEN = "me.rerere.rikkahub.workspace.python"
    const val RUN = IBinder.FIRST_CALL_TRANSACTION
    const val STATUS = RUN + 1
    const val CANCEL = RUN + 2
    const val MAX_REQUEST_BYTES = 16 * 1024 * 1024
    const val MAX_RESULT_BYTES = 2 * 1024 * 1024
}

/** Same-UID worker. Cancellation intentionally destroys the entire interpreter process. */
class WorkspacePythonService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    @Volatile private var complete = false
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in PythonProtocol.RUN..PythonProtocol.CANCEL) {
                return super.onTransact(code, data, reply, flags)
            }
            check(Binder.getCallingUid() == Process.myUid())
            data.enforceInterface(PythonProtocol.TOKEN)
            when (code) {
                PythonProtocol.CANCEL -> Process.killProcess(Process.myPid())
                PythonProtocol.STATUS -> {
                    reply!!.writeNoException()
                    reply.writeInt(if (complete) 1 else 0)
                }
                PythonProtocol.RUN -> {
                    check(busy.compareAndSet(false, true)) { "Python worker already used" }
                    val input = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val output = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    executor.execute {
                        try {
                            val request = ParcelFileDescriptor.AutoCloseInputStream(input).use {
                                val bytes = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val count = it.read(buffer)
                                    if (count < 0) break
                                    require(bytes.size() + count <= PythonProtocol.MAX_REQUEST_BYTES) { "Python request too large" }
                                    bytes.write(buffer, 0, count)
                                }
                                bytes.toString("UTF-8")
                            }
                            val result = try {
                                if (!Python.isStarted()) Python.start(AndroidPlatform(this@WorkspacePythonService))
                                Python.getInstance().getModule("workspace_runner")
                                    .callAttr("run", request).toString()
                            } catch (error: Throwable) {
                                JSONObject().put("exitCode", 1).put("stdout", "")
                                    .put("stderr", error.toString().take(128 * 1024))
                                    .put("truncated", false).toString()
                            }
                            ParcelFileDescriptor.AutoCloseOutputStream(output).use {
                                it.write(result.toByteArray(Charsets.UTF_8))
                            }
                        } finally {
                            runCatching { input.close() }
                            runCatching { output.close() }
                            complete = true
                        }
                    }
                    reply!!.writeNoException()
                    reply.writeInt(Process.myPid())
                }
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
