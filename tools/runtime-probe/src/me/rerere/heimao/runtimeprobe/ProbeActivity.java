package me.rerere.heimao.runtimeprobe;

import android.app.Activity;
import android.os.Bundle;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * runtimeprobe UI shell.
 *
 * Does nothing but:
 *   1. show a TextView,
 *   2. call the JNI entry point {@code nativeProbe(filesDir)} on a background thread,
 *   3. display the returned report and persist it to {@code <files>/probe-result.txt}.
 *
 * No permissions, no network, no resources, no access to any other app's data.
 * All probing logic lives in {@code native/probe.c}.
 */
public class ProbeActivity extends Activity {

    private static final String RESULT_FILE = "probe-result.txt";

    /** Non-null when libruntimeprobe could not be loaded; reported in the UI instead of crashing. */
    private static final Throwable LIB_LOAD_ERROR;

    static {
        Throwable error = null;
        try {
            System.loadLibrary("runtimeprobe");
        } catch (Throwable t) {
            error = t;
        }
        LIB_LOAD_ERROR = error;
    }

    /** Implemented in native/probe.c. Returns the full report as plain text. */
    private static native String nativeProbe(String filesDir);

    private TextView output;
    private volatile boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        output.setTextIsSelectable(true);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        output.setPadding(pad, pad, pad, pad);
        scroll.addView(output);
        setContentView(scroll);
        output.setText("runtimeprobe: running...");

        startProbe();
    }

    private void startProbe() {
        if (started) {
            return;
        }
        started = true;
        final File filesDir = getFilesDir();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runProbe(filesDir);
            }
        }, "runtimeprobe-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void runProbe(File filesDir) {
        String report;
        if (LIB_LOAD_ERROR != null) {
            report = "libruntimeprobe could not be loaded: " + LIB_LOAD_ERROR
                    + "\n(check that lib/<abi>/libruntimeprobe.so is inside the APK"
                    + " and that the APK contains the ABI of this device)";
        } else {
            try {
                String result = nativeProbe(filesDir.getAbsolutePath());
                report = (result == null) ? "(nativeProbe returned null)" : result;
            } catch (Throwable t) {
                report = "nativeProbe failed: " + t;
            }
        }

        report = "android_sdk=" + android.os.Build.VERSION.SDK_INT
                + " android_release=" + android.os.Build.VERSION.RELEASE
                + " debuggable=true\n" + report;
        String saveLine = saveResult(filesDir, report);
        postText(report + "\n\n--- result file ---\n" + saveLine);
    }

    private void postText(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && output != null) {
                    output.setText(text);
                }
            }
        });
    }

    private String saveResult(File filesDir, String report) {
        File target = new File(filesDir, RESULT_FILE);
        File temporary = new File(filesDir, RESULT_FILE + ".tmp");
        try {
            byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (!temporary.renameTo(target)) {
                throw new java.io.IOException("Could not publish the completed report");
            }
            return "OK " + target.getAbsolutePath() + " (" + bytes.length + " bytes)";
        } catch (Exception e) {
            return "FAILED " + target.getAbsolutePath() + " : " + e;
        }
    }
}
