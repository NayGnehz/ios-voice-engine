package local.tingting.diagnostic;

import android.content.*;
import android.os.*;
import android.speech.tts.*;
import com.android.appletts.compat.EngineSession;
import com.android.appletts.compat.ReadyTtsService;
import com.android.appletts.compat.TraceLog;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Uses the public system TTS API; the existing default engine setting is never changed. */
final class TtsProbe implements AutoCloseable {
    static final String TEXT = "你好，这是婷婷启动诊断测试。";
    static final class Result {
        final String id;
        final File file;
        final String nonce;
        final CountDownLatch done = new CountDownLatch(1);
        volatile int error = Integer.MIN_VALUE, submit = Integer.MIN_VALUE;
        volatile long callbackBytes;
        volatile boolean timedOut;
        volatile int sampleRate;
        long wavBytes; int peak;
        Result(String id, File file, String nonce) { this.id = id; this.file = file; this.nonce = nonce; }
        boolean hasAudio() { return !timedOut && error == Integer.MIN_VALUE && wavBytes > 0 && peak > 0; }
        String summary() {
            return "submit=" + submit + " error=" + (error == Integer.MIN_VALUE ? "none" : error)
                + " timeout=" + timedOut + " callbackBytes=" + callbackBytes
                + " WAV音频字节=" + wavBytes + " peak=" + peak;
        }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final EngineSession.Logger logger;
    private final Map<String, Result> pending = new ConcurrentHashMap<>();
    private final CountDownLatch initialized = new CountDownLatch(1);
    private volatile TextToSpeech tts;
    private volatile Result first;
    private volatile int initStatus = Integer.MIN_VALUE;
    private volatile boolean closed;
    private final String probeId = "TTD-" + UUID.randomUUID();
    private int requestNumber;
    final String target;

    TtsProbe(Context context, String target, EngineSession.Logger logger) {
        this.context = context.getApplicationContext(); this.target = target; this.logger = logger;
    }
    void connect() throws InterruptedException {
        logger.log("FRAMEWORK_CONNECT target=" + target);
        main.post(() -> {
            if (closed) return;
            tts = new TextToSpeech(context, status -> {
                if (closed) return;
                initStatus = status;
                logger.log("FRAMEWORK_ON_INIT target=" + target + " status=" + status);
                if (status == TextToSpeech.SUCCESS) {
                    tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String id) { logger.log("FRAMEWORK_START=" + id); }
                        @Override public void onDone(String id) {
                            Result r = pending.get(id); if (r != null) r.done.countDown();
                            logger.log("FRAMEWORK_DONE=" + id);
                        }
                        @Override public void onError(String id) { onError(id, TextToSpeech.ERROR); }
                        @Override public void onError(String id, int code) {
                            Result r = pending.get(id); if (r != null) { r.error = code; r.done.countDown(); }
                            logger.log("FRAMEWORK_ERROR=" + id + " code=" + code);
                        }
                        @Override public void onStop(String id, boolean interrupted) { onError(id, TextToSpeech.STOPPED); }
                        @Override public void onBeginSynthesis(String id, int rate, int format, int channels) {
                            Result r = pending.get(id); if (r != null) r.sampleRate = rate;
                            logger.log("FRAMEWORK_FORMAT=" + id + " rate=" + rate + " format=" + format + " channels=" + channels);
                        }
                        @Override public void onAudioAvailable(String id, byte[] bytes) {
                            Result r = pending.get(id); if (r != null) r.callbackBytes += bytes.length;
                        }
                    });
                    // Deliberately issue the first request before extra language/voice queries can warm up the engine.
                    first = submit("immediate");
                }
                initialized.countDown();
            }, target);
        });
        if (!initialized.await(25, TimeUnit.SECONDS)) throw new IllegalStateException("TTS connection timed out: " + target);
        if (initStatus != TextToSpeech.SUCCESS) throw new IllegalStateException("TTS onInit failed: " + initStatus);
    }
    private Result submit(String label) {
        String id = target + ":" + label + ":" + SystemClock.elapsedRealtime();
        File file = new File(context.getCacheDir(), "probe-" + label + ".wav");
        if (file.exists() && !file.delete()) throw new IllegalStateException("cannot reset probe WAV");
        String nonce = probeId + "-" + (++requestNumber);
        Result result = new Result(id, file, nonce); pending.put(id, result);
        logger.log("FRAMEWORK_SUBMIT=" + id);
        Bundle params = new Bundle(); params.putString(ReadyTtsService.PROBE_KEY, nonce);
        result.submit = tts.synthesizeToFile(TEXT, params, file, id);
        if (result.submit != TextToSpeech.SUCCESS) { result.error = result.submit; result.done.countDown(); }
        return result;
    }
    Result finishFirst() throws Exception { return finish(first); }
    Result request(String label) throws Exception {
        CountDownLatch submitted = new CountDownLatch(1);
        final Result[] box = {null}; final Throwable[] error = {null};
        main.post(() -> {
            try { if (!closed) box[0] = submit(label); }
            catch (Throwable e) { error[0] = e; }
            finally { submitted.countDown(); }
        });
        if (!submitted.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("submit timed out");
        if (error[0] != null) throw new IllegalStateException(error[0]);
        return finish(box[0]);
    }
    private Result finish(Result result) throws Exception {
        if (result == null) throw new IllegalStateException("no synthesis request");
        if (!result.done.await(20, TimeUnit.SECONDS)) { result.timedOut = true; tts.stop(); }
        if (result.file.isFile()) {
            try { WavInfo wav = WavInfo.read(result.file); result.wavBytes = wav.audioBytes; result.peak = wav.peak; }
            catch (IOException e) { logger.log("WAV_PARSE=" + e); }
        }
        logger.log("FRAMEWORK_RESULT=" + result.id + " " + result.summary());
        return result;
    }
    String voice() {
        Voice v = tts == null ? null : tts.getVoice();
        String result = v == null ? "null" : v.getName();
        logger.log("FRAMEWORK_VOICE target=" + target + " voice=" + result);
        return result;
    }
    boolean verifiedTarget() {
        if (target.equals(context.getPackageName())) {
            String trace = TraceLog.snapshot(context);
            boolean verified = first != null && trace.contains("SERVICE REQUEST_PROBE=" + first.nonce + "\n");
            logger.log("FRAMEWORK_SERVICE_ACK_VERIFIED=" + verified + " nonce=" + (first == null ? "none" : first.nonce));
            return verified;
        }
        logger.log("FRAMEWORK_TARGET_EVIDENCE=指定包名的公开TTS接口；不使用Context绑定拦截来判定失败。首轮采样率="
            + (first == null ? 0 : first.sampleRate));
        return false;
    }
    void selectChinese() {
        logger.log("EXPLICIT_CHINESE_SELECT result=" + tts.setLanguage(Locale.SIMPLIFIED_CHINESE));
    }
    @Override public void close() {
        closed = true;
        TextToSpeech current = tts;
        if (current != null) current.shutdown();
        initialized.countDown();
        for (Result r : pending.values()) r.done.countDown();
    }
}
