package local.tingting.diagnostic;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.*;
import android.content.*;
import android.media.*;
import java.lang.reflect.*;
import java.util.*;
import java.io.*;

public class DiagnosticActivity extends Activity {
    final Handler ui = new Handler(Looper.getMainLooper());
    final StringBuilder trace = new StringBuilder();
    TextView output;
    Object engine, listener, audioListener, voice;
    Class<?> engineClass;
    AudioTrack track;
    volatile boolean running;
    volatile long samples;
    int attempts;

    void log(String message) {
        synchronized (trace) {
            trace.append(message).append('\n');
            try (FileOutputStream f = openFileOutput("last-diagnostic.txt", 0)) {
                f.write(trace.toString().getBytes("UTF-8"));
            } catch (Exception ignored) {}
        }
        ui.post(() -> output.setText(trace.toString()));
    }

    void fail(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        log(w.toString());
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(1);
        layout.setPadding(24, 24, 24, 24);
        setContentView(layout);
        TextView intro = new TextView(this);
        intro.setText("修复2：为婷婷的FIX3声库选择BET3定点模块，保留重复关闭修复。仅检查本应用，不更改系统TTS，不上传数据。\n点击“开始诊断”，等待“诊断结束”后再复制结果。若闪退，重新打开后复制。");
        layout.addView(intro);
        Button start = new Button(this); start.setText("开始诊断"); layout.addView(start);
        Button copy = new Button(this); copy.setText("复制诊断结果"); layout.addView(copy);
        ScrollView scroll = new ScrollView(this); output = new TextView(this);
        output.setTextIsSelectable(true); scroll.addView(output);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        try (FileInputStream f = openFileInput("last-diagnostic.txt")) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096]; int n;
            while ((n = f.read(chunk)) > 0) all.write(chunk, 0, n);
            trace.append("上次诊断记录：\n").append(all.toString("UTF-8"));
            output.setText(trace.toString());
        } catch (Exception ignored) { output.setText("等待开始。"); }
        new Thread(this::readPreviousExits, "PreviousCrash").start();
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("TTS diagnostic", output.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        start.setOnClickListener(v -> {
            if (running) return;
            running = true; start.setEnabled(false); trace.setLength(0);
            log("诊断版=1.2-bet3fxd；配置=BET3定点模块修复2；保留重复close修复；时间=" + new Date());
            log("手机=" + Build.MANUFACTURER + " " + Build.MODEL + " Android=" + Build.VERSION.RELEASE + " API=" + Build.VERSION.SDK_INT);
            log("ABIs=" + Arrays.toString(Build.SUPPORTED_ABIS));
            log("进程64位=" + android.os.Process.is64Bit());
            try {
                log("STEP 1 创建VocalizerEngine");
                engineClass = Class.forName("com.nuance.android.vocalizer.VocalizerEngine");
                engine = engineClass.getConstructor(Context.class).newInstance(this);
                Class<?> lc = Class.forName("com.nuance.android.vocalizer.VocalizerEngineListener");
                listener = Proxy.newProxyInstance(lc.getClassLoader(), new Class<?>[]{lc}, (p, m, a) -> {
                    log("事件=" + m.getName() + " " + Arrays.toString(a));
                    return null;
                });
                engineClass.getMethod("setListener", lc).invoke(engine, listener);
                Class<?> ac = Class.forName("com.nuance.android.vocalizer.VocalizerAudioOutputListener");
                audioListener = Proxy.newProxyInstance(ac.getClassLoader(), new Class<?>[]{ac}, (p, m, a) -> {
                    if (m.getName().equals("onAudioData") && a != null && a.length > 1 && a[1] instanceof short[]) {
                        short[] pcm = (short[])a[1]; samples += pcm.length;
                        if (track != null) track.write(pcm, 0, pcm.length);
                    }
                    return null;
                });
                engineClass.getMethod("setAudioOutputListener", ac, boolean.class).invoke(engine, audioListener, true);
                log("STEP 2 initialize()");
                engineClass.getMethod("initialize").invoke(engine);
                ui.postDelayed(this::inspectVoices, 4000);
                ui.postDelayed(() -> { log("音频采样数=" + samples); captureLogs(); }, 20000);
            } catch (Throwable t) { fail(t); captureLogs(); }
        });
    }

    void readPreviousExits() {
        if (Build.VERSION.SDK_INT < 30) return;
        StringBuilder report = new StringBuilder("系统保存的本应用历史退出记录：\n");
        try {
            ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(getPackageName(), 0, 3);
            if (exits.isEmpty()) report.append("暂无记录。\n");
            for (ApplicationExitInfo e : exits) {
                report.append(new Date(e.getTimestamp())).append(" reason=").append(e.getReason())
                    .append(" status=").append(e.getStatus()).append(' ').append(e.getDescription()).append('\n');
                if (Build.VERSION.SDK_INT >= 31 && e.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try (InputStream in = e.getTraceInputStream()) {
                        report.append(in == null ? "系统未保留原生堆栈。\n" : ExitTrace.summarize(in));
                    } catch (Exception error) { report.append("读取堆栈失败：").append(error).append('\n'); }
                }
            }
        } catch (Exception e) { report.append("读取退出记录失败：").append(e).append('\n'); }
        ui.post(() -> log(report.toString()));
    }

    void inspectVoices() {
        try {
            Object state = engineClass.getMethod("getState").invoke(engine);
            log("STEP 3 引擎状态=" + state);
            Object list = engineClass.getMethod("getVoiceList").invoke(engine);
            int count = list == null ? 0 : Array.getLength(list);
            log("识别到的音色数量=" + count);
            for (int i = 0; i < count; i++) {
                Object v = Array.get(list, i);
                String name = String.valueOf(v.getClass().getMethod("getVoiceName").invoke(v));
                log("音色[" + i + "]=" + name);
                if (name.toLowerCase(Locale.ROOT).replace("-", "").contains("tingting")) voice = v;
            }
            if (voice == null) {
                if (!Integer.valueOf(6).equals(state) && attempts++ < 10) {
                    ui.postDelayed(this::inspectVoices, 1000);
                    return;
                }
                log("未识别到婷婷：停止合成测试。"); return;
            }
            new Thread(() -> {
                try {
                    log("STEP 4 加载婷婷");
                    Object result = engineClass.getMethod("loadVoice", voice.getClass()).invoke(engine, voice);
                    log("loadVoice返回=" + result);
                    if (!Boolean.TRUE.equals(result)) { captureLogs(); return; }
                    log("STEP 5 合成固定短句");
                    track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(22050).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(22050 * 4).setTransferMode(AudioTrack.MODE_STREAM).build();
                    track.play();
                    engineClass.getMethod("speak", String.class).invoke(engine, "你好，这是婷婷离线语音测试。");
                    log("speak已返回，累计采样数=" + samples);
                } catch (Throwable t) { fail(t); }
            }, "TingtingProbe").start();
        } catch (Throwable t) { fail(t); }
    }

    void captureLogs() {
        new Thread(() -> {
            try {
                Process proc = new ProcessBuilder("logcat", "-d", "--pid=" + android.os.Process.myPid(), "-t", "250", "-v", "brief").redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                StringBuilder selected = new StringBuilder(); String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains("NUANCE") || line.contains("Vocalizer") || line.contains("JNI") || line.contains("Exception") || line.contains("denied") || line.contains("dlopen")) selected.append(line).append('\n');
                }
                log("本应用引擎日志：\n" + (selected.length() == 0 ? "未读取到相关日志" : selected));
                log("诊断结束，请复制以上结果。");
            } catch (Throwable t) { fail(t); }
        }, "DiagnosticLogs").start();
    }

    @Override public void onDestroy() {
        if (engine != null) try { engineClass.getMethod("release").invoke(engine); } catch (Throwable ignored) {}
        if (track != null) { try { track.stop(); track.release(); } catch (Throwable ignored) {} }
        super.onDestroy();
    }
}
