package com.android.appletts.compat;

import android.media.AudioFormat;
import android.os.SystemClock;
import android.os.Build;
import android.speech.tts.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** The diagnostic and production builds use this same system TTS implementation. */
public final class ReadyTtsService extends TextToSpeechService {
    public static final String VOICE = "tingting_zh_CN";
    public static final String PROBE_KEY = "com.android.appletts.compat.probe";
    private EngineSession session;
    private String initializationError = "";
    private final AtomicInteger cancellation = new AtomicInteger();

    private void log(String text) { TraceLog.write(this, "SERVICE " + text); }
    @Override public void onCreate() {
        super.onCreate();
        TraceLog.attach(this);
        log("CREATE");
        try {
            session = new EngineSession(this, text -> log(text));
            session.start();
        } catch (Throwable e) { initializationError = e.toString(); log("CREATE_FAILED=" + e); }
    }
    private boolean awaitReady(int token) {
        if (session == null) { log("NO_SESSION=" + initializationError); return false; }
        long start = SystemClock.elapsedRealtime();
        try {
            boolean ok = session.gate.await(15000, () -> cancellation.get() != token);
            log("WAIT_READY result=" + ok + " ms=" + (SystemClock.elapsedRealtime() - start)
                + " gate=" + session.gate.status() + " error=" + session.gate.error());
            return ok;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    private static boolean chinese(String language) {
        return "zh".equalsIgnoreCase(language) || "zho".equalsIgnoreCase(language) || "chi".equalsIgnoreCase(language);
    }
    @Override protected String[] onGetLanguage() { return new String[]{"zho", "CHN", ""}; }
    @Override protected int onIsLanguageAvailable(String language, String country, String variant) {
        if (!chinese(language)) return TextToSpeech.LANG_NOT_SUPPORTED;
        return country == null || country.isEmpty() ? TextToSpeech.LANG_AVAILABLE : TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }
    @Override protected int onLoadLanguage(String language, String country, String variant) {
        int available = onIsLanguageAvailable(language, country, variant);
        if (available < 0) return available;
        return awaitReady(cancellation.get()) ? available : TextToSpeech.LANG_MISSING_DATA;
    }
    @Override public List<Voice> onGetVoices() {
        return Collections.singletonList(new Voice(VOICE, Locale.SIMPLIFIED_CHINESE,
            Voice.QUALITY_NORMAL, Voice.LATENCY_VERY_LOW, false, Collections.emptySet()));
    }
    @Override public String onGetDefaultVoiceNameFor(String language, String country, String variant) {
        return chinese(language) ? VOICE : null;
    }
    @Override public int onIsValidVoiceName(String name) {
        return VOICE.equals(name) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }
    @Override public int onLoadVoice(String name) {
        log("LOAD_VOICE_REQUEST=" + name);
        return VOICE.equals(name) && awaitReady(cancellation.get()) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }
    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        final int token = cancellation.get();
        long start = SystemClock.elapsedRealtime();
        CharSequence text = request.getCharSequenceText();
        if (text == null || text.length() == 0) { callback.error(TextToSpeech.ERROR_INVALID_REQUEST); return; }
        String nonce = request.getParams().getString(PROBE_KEY, "");
        if (nonce.matches("TTD-[0-9a-f-]{36}-[0-9]+")) {
            TraceLog.attach(this);
            log("REQUEST_PROBE=" + nonce);
        }
        log("REQUEST chars=" + text.length() + " gate="
            + (session == null ? "NO_SESSION" : session.gate.status()));
        if (!awaitReady(token)) {
            if (cancellation.get() == token) callback.error(TextToSpeech.ERROR_SERVICE);
            log("REQUEST_ERROR_NOT_READY");
            return;
        }
        try {
            if (cancellation.get() != token) return;
            // The Ting-Ting header specifies 22050 Hz, PCM16 mono.
            if (callback.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                log("CALLBACK_START_FAILED"); return;
            }
            int maxBytes = callback.getMaxBufferSize() & ~1;
            if (maxBytes < 2) throw new IllegalStateException("invalid callback buffer size");
            int rate = request.getSpeechRate();
            if (rate > 100) rate = rate / 2 + 100;  // Retain the original wrapper's rate curve.
            int pitch = request.getPitch();
            if (pitch > 80) pitch = 80 + pitch * 90 / 400;
            final long[] bytes = {0};
            final int[] published = {0};
            final Thread synthesisThread = Thread.currentThread();
            final WordRanges words = new WordRanges(text.toString());
            WordRanges.Consumer publish = (frame, begin, end) -> {
                if (Build.VERSION.SDK_INT >= 26 && cancellation.get() == token) {
                    callback.rangeStart(frame, begin, end);
                    published[0]++;
                }
            };
            long samples = session.speakReady(text.toString(), pcm -> {
                if (cancellation.get() != token) throw new IllegalStateException("request stopped");
                if (Thread.currentThread() == synthesisThread)
                    words.drainThrough((int)Math.min(Integer.MAX_VALUE, bytes[0] / 2 + pcm.length), publish);
                int index = 0;
                while (index < pcm.length) {
                    int count = Math.min(pcm.length - index, maxBytes / 2);
                    byte[] data = new byte[count * 2];
                    for (int i = 0; i < count; i++) {
                        short v = pcm[index + i]; data[2 * i] = (byte)v; data[2 * i + 1] = (byte)(v >>> 8);
                    }
                    if (callback.audioAvailable(data, 0, data.length) != TextToSpeech.SUCCESS)
                        throw new IllegalStateException("audio callback rejected");
                    bytes[0] += data.length; index += count;
                }
            }, (frame, begin, length) -> words.add(frame, begin, length), rate, pitch, () -> cancellation.get() != token);
            if (cancellation.get() != token) return;
            words.drainThrough((int)Math.min(Integer.MAX_VALUE, samples), publish);
            log("WORD_RANGES accepted=" + words.accepted + " published=" + published[0]
                + " rejected=" + words.rejected + " duplicates=" + words.duplicates);
            if (samples <= 0 || bytes[0] == 0) {
                callback.error(TextToSpeech.ERROR_SYNTHESIS);
                log("REQUEST_ERROR_ZERO_PCM");
            } else {
                callback.done();
                log("REQUEST_DONE samples=" + samples + " bytes=" + bytes[0]
                    + " ms=" + (SystemClock.elapsedRealtime() - start));
            }
        } catch (Throwable e) {
            log("REQUEST_FAILED=" + e);
            if (cancellation.get() == token) callback.error(TextToSpeech.ERROR_SYNTHESIS);
        }
    }
    @Override protected void onStop() {
        cancellation.incrementAndGet();
        if (session != null) session.stop();
        log("STOP");
    }
    @Override public void onDestroy() {
        cancellation.incrementAndGet();
        if (session != null) session.close();
        log("DESTROY");
        super.onDestroy();
    }
}
