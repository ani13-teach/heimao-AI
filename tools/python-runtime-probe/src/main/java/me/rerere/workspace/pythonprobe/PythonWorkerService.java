package me.rerere.workspace.pythonprobe;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.os.Process;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PythonWorkerService extends Service {
    public static final int PING = 1, RUN = 2, CANCEL = 3, RESULT = 4;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Messenger incoming;

    @Override public void onCreate() {
        super.onCreate();
        incoming = new Messenger(new Handler(getMainLooper(), message -> {
            if (message.what == CANCEL) {
                Process.killProcess(Process.myPid());
                return true;
            }
            Messenger reply = message.replyTo;
            int requestId = message.arg1;
            if (message.what == PING) {
                respond(reply, requestId, null, null);
                return true;
            }
            if (message.what == RUN) {
                String task = message.getData().getString("task", "packages");
                executor.execute(() -> {
                    try {
                        if (!Python.isStarted()) Python.start(new AndroidPlatform(this));
                        Runnable started = () -> respond(reply, requestId, "started", null);
                        String result = Python.getInstance().getModule("probe")
                            .callAttr("run", getFilesDir().getAbsolutePath(), task, started).toString();
                        respond(reply, requestId, result, null);
                    } catch (Throwable error) {
                        respond(reply, requestId, null, LogStackTrace(error));
                    }
                });
                return true;
            }
            return false;
        }));
    }

    private static String LogStackTrace(Throwable error) {
        return android.util.Log.getStackTraceString(error);
    }

    private void respond(Messenger reply, int requestId, String result, String error) {
        if (reply == null) return;
        Bundle data = new Bundle();
        data.putInt("pid", Process.myPid());
        data.putString("result", result);
        data.putString("error", error);
        Message response = Message.obtain(null, RESULT, requestId, 0);
        response.setData(data);
        try { reply.send(response); } catch (RemoteException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return incoming.getBinder(); }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
