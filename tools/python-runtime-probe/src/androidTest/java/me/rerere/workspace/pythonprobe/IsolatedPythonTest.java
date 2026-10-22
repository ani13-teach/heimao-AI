package me.rerere.workspace.pythonprobe;

import android.content.*;
import android.os.*;
import android.os.Process;
import android.system.OsConstants;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class IsolatedPythonTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private final class Client implements AutoCloseable {
        final BlockingQueue<Bundle> responses = new LinkedBlockingQueue<>();
        final CountDownLatch connected = new CountDownLatch(1), died = new CountDownLatch(1);
        final HandlerThread thread = new HandlerThread("isolated-probe-replies");
        final ServiceConnection connection;
        Messenger remote, reply;
        boolean bound;
        volatile String lastStage = "connected";
        Client() throws Exception {
            thread.start();
            reply = new Messenger(new Handler(thread.getLooper(), msg -> {
                if (msg.what == IsolatedPythonService.PROGRESS) lastStage = msg.getData().getString("stage");
                else responses.add(msg.getData());
                return true;
            }));
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    remote = new Messenger(binder);
                    try { binder.linkToDeath(died::countDown, 0); } catch (RemoteException e) { died.countDown(); }
                    connected.countDown();
                }
                @Override public void onServiceDisconnected(ComponentName name) { }
            };
            bound = context.bindService(new Intent(context, IsolatedPythonService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound || !connected.await(15, TimeUnit.SECONDS)) { close(); fail("Isolated service bind timeout"); }
        }
        Bundle request(int operation, Bundle args) throws Exception {
            Message msg = Message.obtain(null, operation);
            msg.setData(args); msg.replyTo = reply; remote.send(msg);
            Bundle result = null;
            long deadline = SystemClock.elapsedRealtime() + 25000;
            while (result == null && SystemClock.elapsedRealtime() < deadline) {
                result = responses.poll(100, TimeUnit.MILLISECONDS);
                if (result == null && died.getCount() == 0) break;
            }
            if (result == null) {
                result = new Bundle();
                result.putString("error", "No final response; binder dead=" + (died.getCount() == 0));
                result.putString("stage", lastStage);
            }
            result.putBoolean("binderDead", died.getCount() == 0);
            return result;
        }
        @Override public void close() {
            try {
                if (remote != null) {
                    remote.send(Message.obtain(null, IsolatedPythonService.STOP));
                    if (!died.await(5, TimeUnit.SECONDS)) throw new AssertionError("Isolated process did not exit");
                }
            } catch (RemoteException ignored) { }
              catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { if (bound) { context.unbindService(connection); bound = false; } thread.quitSafely(); }
        }
    }

    private File fixture(String name, String value) throws Exception {
        File file = new File(context.getFilesDir(), "isolation-fixtures/" + name);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (OutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(StandardCharsets.UTF_8)); }
        return file;
    }
    private ParcelFileDescriptor readOnly(File file) throws Exception {
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    private File archive(String name) throws Exception {
        File file = new File(context.getFilesDir(), "isolation-fixtures/" + name);
        try (InputStream in = context.getAssets().open("chaquopy/" + name);
             OutputStream out = new FileOutputStream(file)) { in.transferTo(out); }
        return file;
    }
    private void save(String name, Bundle report) throws Exception {
        org.json.JSONObject json = new org.json.JSONObject();
        json.put("hostUid", Process.myUid());
        for (String key : report.keySet()) json.put(key, report.get(key));
        String text = json.toString();
        android.util.Log.i("IsolatedPythonProbe", text);
        try (OutputStream out = new FileOutputStream(new File(context.getFilesDir(), name))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test(timeout = 100000) public void isolatedArchiveFdDiagnostics() throws Exception {
        File diagnostic = fixture("fd-access-native.txt", "");
        try (Client client = new Client();
             ParcelFileDescriptor diagnosticFd = ParcelFileDescriptor.open(diagnostic, ParcelFileDescriptor.MODE_WRITE_ONLY);
             ParcelFileDescriptor stdlib = readOnly(archive("stdlib-common.imy"));
             ParcelFileDescriptor bootstrap = readOnly(archive("bootstrap.imy"))) {
            Bundle args = new Bundle();
            args.putParcelable("stdlib", stdlib);
            args.putParcelable("bootstrap", bootstrap);
            args.putParcelable("diagnostic", diagnosticFd);
            args.putBoolean("diagnosticOnly", true);
            Bundle result = client.request(IsolatedPythonService.BOOT, args);
            result.putString("nativeStderr", new String(java.nio.file.Files.readAllBytes(diagnostic.toPath()), StandardCharsets.UTF_8));
            save("fd-access-python.json", result);
            assertNotEquals(Process.myUid(), result.getInt("uid"));
            assertNull(result.getString("error"), result.getString("error"));
            assertFalse(result.getBoolean("binderDead"));
            assertEquals("fd-diagnostic-complete", result.getString("stage"));
            for (String name : new String[]{"stdlib", "bootstrap"}) {
                for (String check : new String[]{"fstat", "directRead", "rewind", "procReopen", "zipFile"})
                    assertNotNull(name + "." + check, result.getString(name + "." + check));
            }
        }
    }

    @Test(timeout = 100000) public void kernelBoundaryThenFreshPythonBoot() throws Exception {
        File chat = fixture("chat.db", "chat-db-substitute");
        File other = fixture("other-workspace/private.txt", "other-workspace-substitute");
        File current = fixture("current-workspace/input.txt", "current-workspace-via-fd");
        assertEquals("chat-db-substitute", new String(java.nio.file.Files.readAllBytes(chat.toPath()), StandardCharsets.UTF_8));
        assertEquals("other-workspace-substitute", new String(java.nio.file.Files.readAllBytes(other.toPath()), StandardCharsets.UTF_8));
        Bundle paths = new Bundle();
        paths.putString("chat", chat.getAbsolutePath());
        paths.putString("other", other.getAbsolutePath());
        int boundaryPid;
        try (Client client = new Client(); ParcelFileDescriptor fd = readOnly(current)) {
            Bundle args = new Bundle(paths); args.putParcelable("current", fd);
            Bundle result = client.request(IsolatedPythonService.BOUNDARY, args);
            save("isolated-boundary.json", result);
            assertNotEquals("Service must have a different UID", Process.myUid(), result.getInt("uid"));
            assertNull(result.getString("error"), result.getString("error"));
            for (String key : new String[]{"chat", "other"}) {
                int errno = result.getInt(key + "Errno");
                // Android may hide app data in the isolated mount namespace (ENOENT).
                assertTrue(key + ": " + result.getString(key + "Error"),
                        errno == OsConstants.EACCES || errno == OsConstants.ENOENT);
            }
            assertEquals("current-workspace-via-fd", result.getString("current"));
            boundaryPid = result.getInt("pid");
        }
        File diagnostic = fixture("native-stderr.txt", "");
        try (Client client = new Client();
             ParcelFileDescriptor diagnosticFd = ParcelFileDescriptor.open(diagnostic, ParcelFileDescriptor.MODE_WRITE_ONLY);
             ParcelFileDescriptor stdlib = readOnly(archive("stdlib-common.imy"));
             ParcelFileDescriptor bootstrap = readOnly(archive("bootstrap.imy"))) {
            Bundle args = new Bundle(paths);
            args.putParcelable("stdlib", stdlib); args.putParcelable("bootstrap", bootstrap);
            args.putParcelable("diagnostic", diagnosticFd);
            Bundle result = new Bundle();
            try {
                result = client.request(IsolatedPythonService.BOOT, args);
            } catch (Exception error) {
                result.putString("error", android.util.Log.getStackTraceString(error));
                result.putString("stage", client.lastStage);
            } finally {
                result.putString("nativeStderr", new String(java.nio.file.Files.readAllBytes(diagnostic.toPath()), StandardCharsets.UTF_8));
                save("isolated-python.json", result);
            }
            assertNotEquals(boundaryPid, result.getInt("pid"));
            assertNotEquals(Process.myUid(), result.getInt("uid"));
            assertNull("Stage " + result.getString("stage") + ": " + result.getString("error"), result.getString("error"));
            assertEquals(2, result.getInt("sum"));
            for (String key : new String[]{"chat", "other"}) {
                String error = result.getString(key + "Python", "");
                assertTrue(error, error.contains("PermissionError: [Errno 13]")
                        || error.contains("FileNotFoundError: [Errno 2]"));
            }
        }
    }
}
