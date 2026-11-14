package com.android.appletts.compat;

/** Readiness is published only after loadVoice has returned successfully. */
public final class ReadyGate {
    public enum Status { NEW, INITIALIZING, LOADING, READY, FAILED, CLOSED }
    public interface Cancelled { boolean get(); }
    private Status status = Status.NEW;
    private String error = "";

    public synchronized Status status() { return status; }
    public synchronized String error() { return error; }
    public synchronized void initializing() { if (status == Status.NEW) status = Status.INITIALIZING; }
    public synchronized void loading() { if (status == Status.INITIALIZING) status = Status.LOADING; }
    public synchronized void ready() {
        if (status == Status.LOADING) { status = Status.READY; notifyAll(); }
    }
    public synchronized void fail(String reason) {
        if (status != Status.CLOSED) { error = reason; status = Status.FAILED; notifyAll(); }
    }
    public synchronized void close() { status = Status.CLOSED; notifyAll(); }
    public synchronized boolean await(long timeoutMillis, Cancelled cancelled) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        while (status != Status.READY) {
            if (cancelled.get() || status == Status.FAILED || status == Status.CLOSED) return false;
            long left = deadline - System.nanoTime();
            if (left <= 0) return false;
            wait(Math.min(50, Math.max(1, left / 1000000L)));
        }
        return !cancelled.get();
    }
}
