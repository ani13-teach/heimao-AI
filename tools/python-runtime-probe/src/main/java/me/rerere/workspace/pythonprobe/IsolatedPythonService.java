package me.rerere.workspace.pythonprobe;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.os.Process;
import android.system.*;
import com.chaquo.python.Python;
import java.io.*;
import java.util.concurrent.*;

public final class IsolatedPythonService extends Service {
    public static final int BOUNDARY = 1, BOOT = 2, STOP = 3, PROGRESS = 4;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Messenger incoming;
    private boolean attempted;

    @Override public void onCreate() {
        super.onCreate();
        incoming = new Messenger(new Handler(getMainLooper(), message -> {
            if (message.what == STOP) { Process.killProcess(Process.myPid()); return true; }
            Bundle args = message.getData();
            args.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
            Messenger reply = message.replyTo;
            int operation = message.what;
            executor.execute(() -> run(operation, args, reply));
            return true;
        }));
    }

    private void run(int operation, Bundle args, Messenger reply) {
        Bundle report = new Bundle();
        report.putInt("uid", Process.myUid());
        report.putInt("pid", Process.myPid());
        String stage = "boundary";
        try {
            if (operation == BOUNDARY) {
                for (String key : new String[]{"chat", "other"}) {
                    try {
                        java.io.FileDescriptor fd = Os.open(args.getString(key), OsConstants.O_RDONLY, 0);
                        Os.close(fd);
                        report.putInt(key + "Errno", 0);
                    } catch (ErrnoException error) {
                        report.putInt(key + "Errno", error.errno);
                        report.putString(key + "Error", error.toString());
                    }
                }
                try (ParcelFileDescriptor fd = args.getParcelable("current");
                     InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
                    report.putString("current", new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            } else if (operation == BOOT) {
                if (attempted) throw new IllegalStateException("Boot already attempted: start a fresh process");
                attempted = true;
                try (ParcelFileDescriptor diagnostic = args.getParcelable("diagnostic");
                     FileOutputStream diagnosticOut = new FileOutputStream(diagnostic.getFileDescriptor());
                     ParcelFileDescriptor stdlib = args.getParcelable("stdlib");
                     ParcelFileDescriptor bootstrap = args.getParcelable("bootstrap")) {
                    Os.dup2(diagnostic.getFileDescriptor(), OsConstants.STDERR_FILENO);
                    Os.dup2(diagnostic.getFileDescriptor(), OsConstants.STDOUT_FILENO);
                    mark(diagnosticOut, reply, "diagnostic:redirected");
                    for (String name : new String[]{"crypto_chaquopy", "ssl_chaquopy", "sqlite3_chaquopy",
                            "crypto_python", "ssl_python", "sqlite3_python", "python3.13", "python3", "chaquopy_java"}) {
                        stage = "loadLibrary:" + name;
                        report.putString("stage", stage);
                        mark(diagnosticOut, reply, stage);
                        System.loadLibrary(name);
                    }
                    inspectArchive("stdlib", stdlib, report, diagnosticOut, reply);
                    inspectArchive("bootstrap", bootstrap, report, diagnosticOut, reply);
                    String path = "/proc/self/fd/" + stdlib.getFd() + ":/proc/self/fd/" + bootstrap.getFd();
                    report.putString("pythonPath", path);
                    if (args.getBoolean("diagnosticOnly")) {
                        stage = "fd-diagnostic-complete";
                        mark(diagnosticOut, reply, stage);
                    } else {
                    stage = "Python.start";
                    mark(diagnosticOut, reply, stage + " path=" + path);
                    Python.start(new Python.Platform() {
                        @Override public String getPath() { return path; }
                        @Override public void onStart(Python python) { }
                    });
                    stage = "eval:1+1";
                    mark(diagnosticOut, reply, stage);
                    report.putInt("sum", Python.getInstance().getBuiltins().callAttr("eval", "1+1").toInt());
                    for (String key : new String[]{"chat", "other"}) {
                        stage = "python.open:" + key;
                        try {
                            Python.getInstance().getBuiltins().callAttr("open", args.getString(key), "rb");
                            report.putString(key + "Python", "UNEXPECTED_OPEN");
                        } catch (com.chaquo.python.PyException error) {
                            report.putString(key + "Python", error.toString());
                        }
                    }
                    stage = "complete";
                    mark(diagnosticOut, reply, stage);
                    }
                }
            } else {
                throw new IllegalArgumentException("Unknown operation " + operation);
            }
        } catch (Throwable error) {
            report.putString("error", android.util.Log.getStackTraceString(error));
        }
        report.putString("stage", stage);
        Message response = Message.obtain(null, operation);
        response.setData(report);
        try { reply.send(response); } catch (RemoteException ignored) { }
    }

    private interface Observation { String read() throws Exception; }

    private void observe(String key, Observation observation, Bundle report,
                         FileOutputStream out, Messenger reply) throws IOException {
        String result;
        try { result = observation.read(); }
        catch (Throwable error) { result = "ERROR " + android.util.Log.getStackTraceString(error); }
        report.putString(key, result);
        mark(out, reply, key + "=" + result);
    }

    private String magic(byte[] bytes, int count) {
        StringBuilder result = new StringBuilder("bytes=" + count + " hex=");
        for (int i = 0; i < Math.max(0, count); i++) result.append(String.format("%02x", bytes[i] & 255));
        return result.toString();
    }

    private void inspectArchive(String name, ParcelFileDescriptor archive, Bundle report,
                                FileOutputStream out, Messenger reply) throws IOException {
        String path = "/proc/self/fd/" + archive.getFd();
        observe(name + ".fstat", () -> {
            StructStat stat = Os.fstat(archive.getFileDescriptor());
            return "fd=" + archive.getFd() + " size=" + stat.st_size + " mode=" + Integer.toOctalString(stat.st_mode);
        }, report, out, reply);
        try {
            observe(name + ".directRead", () -> {
                byte[] bytes = new byte[4];
                return magic(bytes, Os.read(archive.getFileDescriptor(), bytes, 0, bytes.length));
            }, report, out, reply);
        } finally {
            observe(name + ".rewind", () -> "offset=" + Os.lseek(archive.getFileDescriptor(), 0, OsConstants.SEEK_SET),
                    report, out, reply);
        }
        observe(name + ".procReopen", () -> {
            try (FileInputStream in = new FileInputStream(path)) {
                byte[] bytes = new byte[4];
                return magic(bytes, in.read(bytes));
            }
        }, report, out, reply);
        observe(name + ".zipFile", () -> {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path)) {
                int count = 0;
                StringBuilder encodings = new StringBuilder();
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    count++;
                    if (entry.getName().startsWith("encodings/")) {
                        encodings.append(entry.getName()).append(" method=").append(entry.getMethod())
                                .append(" size=").append(entry.getSize()).append(';');
                    }
                }
                return "entries=" + count + " encodings=" + encodings;
            }
        }, report, out, reply);
    }

    private void mark(FileOutputStream out, Messenger reply, String stage) throws IOException {
        out.write(("stage=" + stage + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
        System.err.flush();
        System.out.flush();
        out.getFD().sync();
        Message progress = Message.obtain(null, PROGRESS);
        Bundle data = new Bundle();
        data.putString("stage", stage);
        progress.setData(data);
        try { reply.send(progress); } catch (RemoteException ignored) { }
    }

    @Override public IBinder onBind(Intent intent) { return incoming.getBinder(); }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
