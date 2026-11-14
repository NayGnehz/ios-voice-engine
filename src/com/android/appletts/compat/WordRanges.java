package com.android.appletts.compat;
import java.util.*;

/** Native source offsets are UTF-16 indices; destination positions are PCM sample frames. */
public final class WordRanges {
    public interface Consumer { void range(int frame, int start, int end); }
    public static final class Range {
        public final int frame, start, end;
        Range(int frame, int start, int end) { this.frame = frame; this.start = start; this.end = end; }
    }
    private final String text;
    private final Queue<Range> pending = new ArrayDeque<>();
    private int lastFrame = -1, lastStart = -1, lastEnd = -1;
    public int accepted, rejected, duplicates;
    public WordRanges(String text) { this.text = text; }
    public synchronized boolean add(int frame, int start, int length) {
        long end = (long)start + length;
        if (frame < 0 || start < 0 || length <= 0 || end > text.length()
            || (start > 0 && Character.isLowSurrogate(text.charAt(start)))
            || (end < text.length() && Character.isLowSurrogate(text.charAt((int)end)))
            || frame < lastFrame || start < lastStart) { rejected++; return false; }
        if (start == lastStart && end == lastEnd && frame == lastFrame) { duplicates++; return false; }
        pending.add(new Range(frame, start, (int)end));
        lastFrame = frame; lastStart = start; lastEnd = (int)end; accepted++; return true;
    }
    public void drain(Consumer consumer) {
        drainThrough(Integer.MAX_VALUE, consumer);
    }
    public void drainThrough(int availableFrames, Consumer consumer) {
        for (;;) {
            Range range;
            synchronized (this) {
                range = pending.peek();
                if (range == null || range.frame > availableFrames) return;
                pending.poll();
            }
            if (range == null) return;
            consumer.range(range.frame, range.start, range.end);
        }
    }
}
