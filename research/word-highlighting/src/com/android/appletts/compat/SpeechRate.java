package com.android.appletts.compat;

/** Preserve Android's percentage rate; match the native engine's supported limits. */
public final class SpeechRate {
    private SpeechRate() {}
    public static int toNative(int requested) {
        return Math.max(50, Math.min(400, requested));
    }
}
