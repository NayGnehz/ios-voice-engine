package com.android.appletts.compat;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.util.AtomicFile;
import java.io.*;
import java.util.UUID;

public final class TraceLog {
    public static final String FILE = "tts-readiness.log";
    private static String run;
    private static boolean diagnostic(Context context) { return context.getPackageName().equals("local.tingting.diagnostic"); }
    public static synchronized void begin(Context context) throws IOException {
        String value = System.currentTimeMillis() + "-" + UUID.randomUUID();
        AtomicFile marker = new AtomicFile(new File(context.getFilesDir(), "tts-current-run"));
        FileOutputStream out = marker.startWrite();
        try { out.write(value.getBytes("UTF-8")); marker.finishWrite(out); run = value; }
        catch (IOException e) { marker.failWrite(out); throw e; }
    }
    public static synchronized void attach(Context context) {
        if (!diagnostic(context)) return;
        try {
            run = new String(new AtomicFile(new File(context.getFilesDir(), "tts-current-run")).readFully(), "UTF-8");
            if (!run.matches("[0-9a-f-]+")) run = "historical";
        } catch (IOException e) { run = "historical"; }
    }
    private static File folder(Context context) {
        return new File(new File(context.getFilesDir(), "tts-traces"), run);
    }
    public static synchronized String snapshot(Context context) {
        if (run == null) attach(context);
        try { return ProcessJournal.snapshot(folder(context), 240000); }
        catch (IOException e) { return "读取诊断日志失败：" + e; }
    }
    public static synchronized void write(Context context, String event) {
        String line = System.currentTimeMillis() + " elapsed=" + SystemClock.elapsedRealtime()
            + " ns=" + SystemClock.elapsedRealtimeNanos()
            + " pid=" + android.os.Process.myPid() + " thread=" + Thread.currentThread().getName()
            + " " + event.replace('\n', ' ');
        Log.i("TingtingReady", event);
        // Persist full timing only in the diagnostic package. Production records no spoken text.
        if (!diagnostic(context)) return;
        if (run == null) attach(context);
        try { ProcessJournal.append(folder(context), "pid-" + android.os.Process.myPid(), line); }
        catch (Exception e) { Log.w("TingtingReady", "trace write failed", e); }
    }
}
