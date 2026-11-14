package local.tingting.diagnostic;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.media.*;
import android.os.*;
import android.widget.*;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import com.android.appletts.compat.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.*;

public final class DiagnosticActivity extends Activity {
    static final String INSTALLED = "com.android.apple64ttsx";
    static final String HEADER = "assets/vocalizer/mnc/ve_mnc_ting-ting_compact.hdr";
    final Handler main = new Handler(Looper.getMainLooper());
    final AtomicBoolean cancelled = new AtomicBoolean();
    TextView output;
    TextView preview;
    Button start, delayed;
    volatile boolean running, destroyed;
    volatile TtsProbe activeProbe;
    volatile EngineSession direct;
    long began;

    void log(String message) {
        TraceLog.write(getApplicationContext(), "PROBE " + message);
        main.post(() -> { if (!destroyed) output.setText(readTrace()); });
    }
    String readTrace() {
        return TraceLog.snapshot(getApplicationContext());
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(1); layout.setPadding(24, 24, 24, 24);
        setContentView(layout);
        TextView intro = new TextView(this);
        intro.setText("启动与覆盖安装诊断 1.6（倍速修复、词边界）\n保留现有 iOS-婷婷，点击“开始诊断”。完成后复制结果。不会更改默认语音引擎。\n“延迟启动对照”是人为延迟两秒的模拟测试，会明确标记。");
        layout.addView(intro);
        preview = new TextView(this); preview.setText(TtsProbe.TEXT); preview.setTextSize(20); layout.addView(preview);
        start = new Button(this); start.setText("开始诊断"); layout.addView(start);
        delayed = new Button(this); delayed.setText("延迟启动对照"); layout.addView(delayed);
        Button copy = new Button(this); copy.setText("复制结果"); layout.addView(copy);
        ScrollView scroll = new ScrollView(this); output = new TextView(this); output.setTextIsSelectable(true);
        scroll.addView(output); layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!readTrace().isEmpty()) output.setText("上次记录（不是本次诊断）：\n" + readTrace());
        else output.setText("等待开始诊断。旧版诊断结果不会作为本次结果显示。");
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("婷婷诊断", output.getText()));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        start.setOnClickListener(v -> begin(false)); delayed.setOnClickListener(v -> begin(true));
        new Thread(this::previousCrash, "PreviousCrash").start();
    }
    void begin(boolean simulated) {
        if (running) return;
        running = true; cancelled.set(false); start.setEnabled(false); delayed.setEnabled(false);
        began = SystemClock.elapsedRealtime();
        try { TraceLog.begin(getApplicationContext()); }
        catch (Exception e) { output.setText("无法创建诊断日志：" + e); return; }
        new Thread(() -> {
            try {
                log("诊断版=1.6-rate；本次模式=" + (simulated ? "人为延迟2秒对照，不能作为自然故障证据" : "自然启动，无人工延迟"));
                log("手机=" + Build.MANUFACTURER + " " + Build.MODEL + " Android=" + Build.VERSION.RELEASE
                    + " API=" + Build.VERSION.SDK_INT + " ABIs=" + Arrays.toString(Build.SUPPORTED_ABIS)
                    + " 64位=" + android.os.Process.is64Bit());
                metadata(getPackageName());
                if (installed()) {
                    metadata(INSTALLED);
                    framework(INSTALLED, false);
                } else log("未找到已安装的iOS-婷婷：跳过现有引擎链路。");
                if (cancelled.get()) return;
                direct(simulated);
                if (cancelled.get()) return;
                framework(getPackageName(), true);
                previousCrash();
                captureLogs();
                log("诊断结束。总耗时=" + (SystemClock.elapsedRealtime() - began) + "ms。请复制全部结果。");
            } catch (Throwable e) { log("DIAGNOSTIC_ERROR=" + e); captureLogs(); log("诊断结束（有错误），请复制结果。"); }
            finally {
                if (direct != null) { direct.close(); direct = null; }
                if (activeProbe != null) { activeProbe.close(); activeProbe = null; }
                running = false;
                main.post(() -> { if (!destroyed) { start.setEnabled(true); delayed.setEnabled(true); } });
            }
        }, "TingtingDiagnosis").start();
    }
    boolean installed() {
        try { getPackageManager().getPackageInfo(INSTALLED, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
    void metadata(String pkg) throws Exception {
        PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
        long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        log("PACKAGE=" + pkg + " version=" + info.versionName + " code=" + code
            + " firstInstall=" + new Date(info.firstInstallTime) + " lastUpdate=" + new Date(info.lastUpdateTime));
        android.content.SharedPreferences prefs = getSharedPreferences("upgrade-probe", MODE_PRIVATE);
        long previous = prefs.getLong(pkg + ".code", -1), lastUpdate = prefs.getLong(pkg + ".update", -1);
        log("PREVIOUS_SEEN=" + pkg + " code=" + previous + " update=" + lastUpdate
            + " changed=" + (previous >= 0 && (previous != code || lastUpdate != info.lastUpdateTime)));
        prefs.edit().putLong(pkg + ".code", code).putLong(pkg + ".update", info.lastUpdateTime).commit();
        log("APK=" + info.applicationInfo.sourceDir + " nativeLibraryDir=" + info.applicationInfo.nativeLibraryDir);
        try (ZipFile apk = new ZipFile(info.applicationInfo.sourceDir)) {
            ZipEntry h = apk.getEntry(HEADER);
            if (h != null) {
                byte[] raw = read(apk.getInputStream(h), 100000);
                String xml = new String(raw, "UTF-8");
                log("HEADER_SHA256=" + hex(MessageDigest.getInstance("SHA-256").digest(raw))
                    + " fixedSelect=" + xml.contains("<COMPONENT>select/bet3fxd</COMPONENT>")
                    + " fixedSynth=" + xml.contains("<COMPONENT>synth/bet3fxd</COMPONENT>"));
            }
            ZipEntry lib = apk.getEntry("lib/arm64-v8a/libNuanceVocalizer.so");
            if (lib != null) {
                String expected = hex(MessageDigest.getInstance("SHA-256").digest(read(apk.getInputStream(lib), 12000000)));
                log("NATIVE_APK size=" + lib.getSize() + " compressed=" + lib.getCompressedSize()
                    + " method=" + lib.getMethod() + " sha256=" + expected);
                File extracted = new File(info.applicationInfo.nativeLibraryDir, "libNuanceVocalizer.so");
                if (extracted.isFile()) {
                    try {
                        String actual = hex(MessageDigest.getInstance("SHA-256").digest(read(new FileInputStream(extracted), 12000000)));
                        log("NATIVE_EXTRACTED sha256=" + actual + " matchesAPK=" + expected.equals(actual));
                    } catch (Exception e) { log("NATIVE_EXTRACTED_UNREADABLE=" + e); }
                } else log("NATIVE_EXTRACTED_NOT_PRESENT");
            }
        } catch (Exception e) { log("APK_INSPECT_UNAVAILABLE=" + e); }
        if (pkg.equals(getPackageName())) {
            File old = new File(getFilesDir(), "last-diagnostic.txt");
            log("LEGACY_DIAGNOSTIC_DATA_PRESENT=" + old.isFile() + "；旧日志不作为本次结果。");
        }
    }
    void framework(String pkg, boolean fixed) {
        log("=== 系统TTS链路 target=" + pkg + " fixed=" + fixed + " ===");
        try (TtsProbe probe = new TtsProbe(this, pkg, this::log)) {
            probe.rangeObserver = (begin, end) -> main.post(() -> {
                if (!destroyed) {
                    SpannableString shown = new SpannableString(TtsProbe.TEXT);
                    shown.setSpan(new BackgroundColorSpan(0xffffdd66), begin, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    preview.setText(shown);
                }
            });
            activeProbe = probe;
            probe.connect();
            TtsProbe.Result first = probe.finishFirst();
            Thread.sleep(4000);
            if (cancelled.get()) return;
            TtsProbe.Result warm = probe.request("after4s");
            String voice = probe.voice();
            boolean verified = probe.verifiedTarget();
            if (fixed || (voice != null && voice.startsWith("tingting_"))) probe.playback();
            if (fixed && (!verified || !ReadyTtsService.VOICE.equals(voice))) {
                log("FIXED_FRAMEWORK_INCONCLUSIVE：缺少修复服务的本轮请求回执或音色标识。音频结果已单独记录。");
            } else if (first.hasAudio() && warm.hasAudio()) {
                log((fixed ? "FIXED_FRAMEWORK_PASS" : "INSTALLED_FRAMEWORK_PASS") + "：立即请求和延迟请求均生成非零音频。");
            } else if (!first.hasAudio() && warm.hasAudio()) {
                log((fixed ? "FIXED_FRAMEWORK_FIRST_FAILED" : "INSTALLED_FIRST_REQUEST_FAILED")
                    + "：首轮无有效音频，延迟后正常。需结合初始化时间线判断。");
            } else {
                log("FRAMEWORK_BOTH_OR_WARM_FAILED：追加显式中文选择测试。");
                probe.selectChinese();
                TtsProbe.Result selected = probe.request("explicitChinese");
                log("EXPLICIT_CHINESE_RECOVERED=" + selected.hasAudio());
            }
        } catch (Throwable e) { log("FRAMEWORK_PROBE_ERROR target=" + pkg + " " + e); }
        finally { activeProbe = null; }
    }
    void direct(boolean simulated) throws Exception {
        log("=== 同一原生引擎：旧调用与等待就绪对照 ===");
        EngineSession session = new EngineSession(this, this::log); direct = session;
        Thread starter = null;
        if (simulated) {
            log("SIMULATION：人为延后initialize两秒，仅验证等待逻辑。");
            starter = new Thread(() -> {
                try { Thread.sleep(2000); if (!cancelled.get()) session.start(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "DelayedInitialize");
            starter.start();
        } else session.start();
        ReadyGate.Status before = session.gate.status();
        long early = session.speakWithoutGate("旧调用方式启动测试。", pcm -> {});
        log("LEGACY_EARLY_RESULT gateBefore=" + before + " samples=" + early);
        AudioTrack audio = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(22050).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(44100).setTransferMode(AudioTrack.MODE_STREAM).build();
        long gated;
        try {
            audio.play();
            gated = session.speakReady("你好，婷婷已经加载完成，现在可以正常朗读。", pcm -> {
                int offset = 0;
                while (offset < pcm.length && !cancelled.get()) {
                    int written = audio.write(pcm, offset, pcm.length - offset);
                    if (written <= 0) throw new IllegalStateException("AudioTrack write=" + written);
                    offset += written;
                }
            }, 100, 100, cancelled::get);
            long end = SystemClock.elapsedRealtime() + 3000;
            while (!cancelled.get() && Integer.toUnsignedLong(audio.getPlaybackHeadPosition()) < gated
                && SystemClock.elapsedRealtime() < end) Thread.sleep(20);
        } finally { audio.stop(); audio.release(); session.close(); direct = null; }
        if (starter != null) starter.join(3000);
        if (early == 0 && gated > 0 && before != ReadyGate.Status.READY) {
            log((simulated ? "SIMULATED_RACE_REPRODUCED" : "NATURAL_STARTUP_RACE_REPRODUCED")
                + "：旧调用0采样，等待后=" + gated + "。这验证启动时序问题；不单独证明历史覆盖故障。 ");
        } else log("DIRECT_RESULT early=" + early + " gated=" + gated + "；本轮是否复现以采样数和时间线为准。");
    }
    void previousCrash() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager manager = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(getPackageName(), 0, 2)) {
                log("HISTORICAL_EXIT time=" + new Date(exit.getTimestamp()) + " reason=" + exit.getReason()
                    + " status=" + exit.getStatus() + " " + exit.getDescription());
                if (Build.VERSION.SDK_INT >= 31 && exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    try (InputStream in = exit.getTraceInputStream()) {
                        log("HISTORICAL_NATIVE_TRACE=" + (in == null ? "系统未保留堆栈" : ExitTrace.summarize(in)));
                    } catch (Exception error) { log("HISTORICAL_NATIVE_TRACE_ERROR=" + error); }
                }
            }
        } catch (Exception e) { log("HISTORICAL_EXIT_ERROR=" + e); }
    }
    void captureLogs() {
        try {
            java.lang.Process proc = new ProcessBuilder("logcat", "-d", "-t", "400", "-v", "brief").redirectErrorStream(true).start();
            byte[] bytes = read(proc.getInputStream(), 300000);
            for (String line : new String(bytes, "UTF-8").split("\n"))
                if (line.contains("NUANCE") || line.contains("TextToSpeech") || line.contains("AndroidRuntime") || line.contains("fdsan")) log("LOGCAT " + line);
        } catch (Exception e) { log("LOGCAT_ERROR=" + e); }
    }
    static byte[] read(InputStream stream, int limit) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream all = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (all.size() + n > limit) throw new IOException("input exceeds limit");
                all.write(buffer, 0, n);
            }
            return all.toByteArray();
        }
    }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    @Override public void onDestroy() {
        destroyed = true; cancelled.set(true);
        if (direct != null) direct.close();
        if (activeProbe != null) activeProbe.close();
        super.onDestroy();
    }
}
