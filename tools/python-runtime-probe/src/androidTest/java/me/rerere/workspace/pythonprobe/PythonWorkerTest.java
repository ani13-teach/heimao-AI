package me.rerere.workspace.pythonprobe;

import android.content.*;
import android.os.*;
import android.os.Process;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PythonWorkerTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private final class Client implements AutoCloseable {
        final BlockingQueue<Bundle> responses = new LinkedBlockingQueue<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch died = new CountDownLatch(1);
        final HandlerThread callbacks = new HandlerThread("probe-replies");
        final ServiceConnection connection;
        Messenger remote;
        Messenger reply;
        boolean bound;

        Client() throws Exception {
            callbacks.start();
            reply = new Messenger(new Handler(callbacks.getLooper(), message -> {
                responses.add(message.getData());
                return true;
            }));
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    remote = new Messenger(binder);
                    try { binder.linkToDeath(died::countDown, 0); }
                    catch (RemoteException error) { died.countDown(); }
                    connected.countDown();
                }
                @Override public void onServiceDisconnected(ComponentName name) { }
            };
            bound = context.bindService(new Intent(context, PythonWorkerService.class),
                    connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(20, TimeUnit.SECONDS)) {
                close();
                fail("Worker did not bind within 20 seconds");
            }
        }

        void send(int operation, String task) throws Exception {
            Message message = Message.obtain(null, operation);
            Bundle arguments = new Bundle();
            arguments.putString("task", task);
            message.setData(arguments);
            message.replyTo = reply;
            remote.send(message);
        }

        Bundle receive(int timeoutSeconds) throws Exception {
            Bundle result = responses.poll(timeoutSeconds, TimeUnit.SECONDS);
            assertNotNull("Worker response timed out", result);
            assertNull(result.getString("error"), result.getString("error"));
            assertTrue(result.getInt("pid") > 0);
            assertNotEquals(Process.myPid(), result.getInt("pid"));
            return result;
        }

        @Override public void close() {
            if (bound) { context.unbindService(connection); bound = false; }
            callbacks.quitSafely();
        }
    }

    @Test(timeout = 450000) public void packagesCancelAndRestart() throws Exception {
        int previousPid;
        try (Client client = new Client()) {
            client.send(PythonWorkerService.PING, "");
            previousPid = client.receive(10).getInt("pid");
            client.send(PythonWorkerService.RUN, "invalid-task");
            Bundle failure = client.responses.poll(30, TimeUnit.SECONDS);
            assertNotNull("Python exception response timed out", failure);
            assertTrue(failure.getString("error", "").contains("Unknown task: invalid-task"));
            assertEquals(previousPid, failure.getInt("pid"));
            client.send(PythonWorkerService.PING, "");
            assertEquals(previousPid, client.receive(10).getInt("pid"));
            client.send(PythonWorkerService.RUN, "packages");
            Bundle result = client.receive(180);
            assertEquals(previousPid, result.getInt("pid"));
            org.json.JSONObject report = new org.json.JSONObject(result.getString("result"));
            assertEquals(111, report.getInt("matrix_result"));
            assertEquals(12, report.getInt("sum"));
            for (String packageName : new String[]{"numpy", "pandas", "matplotlib", "openpyxl"}) {
                assertFalse(report.getString(packageName).isEmpty());
            }
            assertTrue(new java.io.File(report.getString("png")).length() > 1000);
            client.send(PythonWorkerService.RUN, "infinite");
            Bundle started = client.receive(20);
            assertEquals("started", started.getString("result"));
            assertEquals(previousPid, started.getInt("pid"));
            // Python acknowledges entry before PING checks main-thread responsiveness.
            client.send(PythonWorkerService.PING, "");
            assertEquals(previousPid, client.receive(10).getInt("pid"));
            client.send(PythonWorkerService.CANCEL, "");
            assertTrue("Worker binder stayed alive after cancel", client.died.await(15, TimeUnit.SECONDS));
        }
        try (Client restarted = new Client()) {
            restarted.send(PythonWorkerService.PING, "");
            assertNotEquals(previousPid, restarted.receive(20).getInt("pid"));
            restarted.send(PythonWorkerService.RUN, "packages");
            assertNotNull(restarted.receive(180).getString("result"));
            restarted.send(PythonWorkerService.CANCEL, "");
            assertTrue(restarted.died.await(15, TimeUnit.SECONDS));
        }
    }
}
