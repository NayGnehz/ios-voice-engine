package com.android.appletts.compat;

import android.content.Context;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EngineSession {
    public interface Logger { void log(String text); }
    public interface AudioSink { void accept(short[] pcm); }
    public interface MarkSink { void word(int frame, int start, int length); }
    public final ReadyGate gate = new ReadyGate();
    private final Logger logger;
    private final Object engine;
    private final Class<?> engineClass;
    private final Object listener, audioListener;
    private final AtomicBoolean loading = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Object speechLock = new Object();
    private volatile AudioSink sink;
    private volatile MarkSink markSink;
    private Object markListener;
    private int markLogCount;
    private volatile RuntimeException audioError;
    private volatile boolean closed, speaking;
    private volatile long pcmSamples;

    public EngineSession(Context context, Logger logger) throws Exception {
        this.logger = logger;
        engineClass = Class.forName("com.nuance.android.vocalizer.VocalizerEngine");
        engine = engineClass.getConstructor(Context.class).newInstance(context.getApplicationContext());
        Class<?> lc = Class.forName("com.nuance.android.vocalizer.VocalizerEngineListener");
        listener = Proxy.newProxyInstance(lc.getClassLoader(), new Class<?>[]{lc}, (p, m, args) -> {
            if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, args);
            if (m.getName().equals("onStateChanged")) {
                int state = (Integer)args[0];
                logger.log("SDK_STATE=" + state + " gate=" + gate.status());
                if (state == 2 && !closed && loading.compareAndSet(false, true)) loadVoice();
                if (state == 6) gate.fail("SDK initialization failed, state=6");
            }
            return null;
        });
        engineClass.getMethod("setListener", lc).invoke(engine, listener);
        Class<?> ac = Class.forName("com.nuance.android.vocalizer.VocalizerAudioOutputListener");
        audioListener = Proxy.newProxyInstance(ac.getClassLoader(), new Class<?>[]{ac}, (p, m, args) -> {
            if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, args);
            if (m.getName().equals("onAudioData") && args != null && args.length > 1 && args[1] instanceof short[]) {
                short[] pcm = (short[])args[1];
                pcmSamples += pcm.length;
                AudioSink current = sink;
                if (current != null && !closed && audioError == null) {
                    try { current.accept(pcm); }
                    catch (RuntimeException e) { audioError = e; }
                }
            }
            return null;
        });
        engineClass.getMethod("setAudioOutputListener", ac, boolean.class).invoke(engine, audioListener, true);
        try {
            Class<?> mc = Class.forName("com.nuance.android.vocalizer.VocalizerSpeechMarkListener");
            markListener = Proxy.newProxyInstance(mc.getClassLoader(), new Class<?>[]{mc}, (p, m, args) -> {
                if (m.getDeclaringClass() == Object.class) return objectMethod(p, m, args);
                if (m.getName().equals("onSpeechMarkReceived") && args != null && args[0] != null) {
                    try {
                        Object mark = args[0]; Class<?> type = mark.getClass();
                        int kind = (Integer)type.getMethod("getType").invoke(mark);
                        if (kind == 2) {
                            int frame = (Integer)type.getMethod("getDestPos").invoke(mark);
                            int start = (Integer)type.getMethod("getSrcPos").invoke(mark);
                            int length = (Integer)type.getMethod("getSrcTextLen").invoke(mark);
                            if (markLogCount++ < 32) logger.log("NATIVE_WORD frame=" + frame + " start=" + start + " length=" + length);
                            MarkSink current = markSink;
                            if (current != null && !closed) current.word(frame, start, length);
                        }
                    } catch (Exception e) { logger.log("MARK_ERROR=" + e); }
                }
                return null;
            });
            Field field = engineClass.getDeclaredField("mSpeechMarkListener"); field.setAccessible(true); field.set(engine, markListener);
            logger.log("WORD_MARK_LISTENER_ATTACHED");
        } catch (Exception e) { logger.log("WORD_MARKS_UNAVAILABLE=" + e); }
        logger.log("ENGINE_CREATED state=" + sdkState());
    }

    static Object objectMethod(Object proxy, Method m, Object[] args) {
        if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (m.getName().equals("equals")) return proxy == args[0];
        return "TingtingListener";
    }
    public int sdkState() {
        try { return (Integer)engineClass.getMethod("getState").invoke(engine); }
        catch (Exception e) { return -1; }
    }
    public void start() {
        if (closed || !started.compareAndSet(false, true)) return;
        gate.initializing();
        logger.log("INITIALIZE_REQUEST");
        try { engineClass.getMethod("initialize").invoke(engine); }
        catch (Throwable e) { failed("initialize", e); }
    }
    private void loadVoice() {
        gate.loading();
        try {
            Object voices = engineClass.getMethod("getVoiceList").invoke(engine);
            int count = voices == null ? 0 : Array.getLength(voices);
            logger.log("VOICE_COUNT=" + count);
            Object selected = null;
            for (int i = 0; i < count; i++) {
                Object voice = Array.get(voices, i);
                String name = String.valueOf(voice.getClass().getMethod("getVoiceName").invoke(voice));
                logger.log("VOICE[" + i + "]=" + name);
                if (EngineProfile.SDK_NAME.equalsIgnoreCase(name)) selected = voice;
            }
            if (selected == null) { gate.fail(EngineProfile.SDK_NAME + " not found"); return; }
            if (closed) return;
            logger.log("LOAD_BEGIN");
            boolean ok = Boolean.TRUE.equals(engineClass.getMethod("loadVoice", selected.getClass()).invoke(engine, selected));
            logger.log("LOAD_RETURN=" + ok + " sdkState=" + sdkState());
            if (ok) { gate.ready(); logger.log("VOICE_READY gate=" + gate.status()); }
            else gate.fail("loadVoice=false");
        } catch (Throwable e) { failed("loadVoice", e); }
    }
    private void failed(String operation, Throwable e) {
        while (e instanceof InvocationTargetException && e.getCause() != null) e = e.getCause();
        gate.fail(operation + ": " + e);
        logger.log("ENGINE_ERROR=" + gate.error());
    }
    /** Diagnostic control: reproduce the original wrapper's immediate speak call. */
    public long speakWithoutGate(String text, AudioSink output) throws Exception {
        synchronized (speechLock) { return perform(text, output, "LEGACY_REQUEST"); }
    }
    public long speakReady(String text, AudioSink output, int rate, int pitch, ReadyGate.Cancelled cancelled) throws Exception {
        return speakReady(text, output, null, rate, pitch, cancelled);
    }
    public long speakReady(String text, AudioSink output, MarkSink marks, int rate, int pitch, ReadyGate.Cancelled cancelled) throws Exception {
        if (!gate.await(15000, cancelled)) throw new IllegalStateException("engine not ready: " + gate.status() + " " + gate.error());
        synchronized (speechLock) {
            if (closed || cancelled.get()) throw new InterruptedException("synthesis cancelled");
            if (rate > 0) engineClass.getMethod("setSpeechRate", int.class).invoke(engine, rate);
            if (pitch > 0) engineClass.getMethod("setSpeechPitch", int.class).invoke(engine, pitch);
            markSink = marks; markLogCount = 0;
            try { return perform(text, output, "READY_REQUEST"); }
            finally { markSink = null; }
        }
    }
    private long perform(String text, AudioSink output, String event) throws Exception {
        if (closed) throw new IllegalStateException("session closed");
        long before = pcmSamples;
        logger.log(event + " gate=" + gate.status() + " sdkState=" + sdkState() + " chars=" + text.length());
        audioError = null; sink = output; speaking = true;
        try { engineClass.getMethod("speak", String.class).invoke(engine, text); }
        finally { sink = null; speaking = false; }
        if (audioError != null) throw audioError;  // Never propagate an audio-sink exception through JNI callbacks.
        long result = pcmSamples - before;
        logger.log(event + "_RETURN samples=" + result + " gate=" + gate.status());
        return result;
    }
    public void stop() {
        if (!speaking) return;
        try { engineClass.getMethod("stop").invoke(engine); }
        catch (Exception e) { logger.log("STOP_ERROR=" + e); }
    }
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        closed = true; gate.close(); stop();
        new Thread(() -> {
            synchronized (speechLock) {
                try { engineClass.getMethod("release").invoke(engine); logger.log("ENGINE_RELEASED"); }
                catch (Exception e) { logger.log("RELEASE_ERROR=" + e); }
            }
        }, "TingtingRelease").start();
    }
}
