package com.android.appletts.compat;

import java.io.*;
import java.util.*;

/** A different file per process; no cross-process shared append position or file lock. */
public final class ProcessJournal {
    public static synchronized void append(File folder, String writer, String line) throws IOException {
        if (!writer.matches("[a-zA-Z0-9_-]+")) throw new IOException("invalid writer id");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("cannot create journal");
        try (FileOutputStream out = new FileOutputStream(new File(folder, writer + ".log"), true)) {
            out.write((line.replace('\n', ' ') + "\n").getBytes("UTF-8"));
        }
    }
    static long order(String line) {
        try {
            int ns = line.indexOf(" ns=");
            if (ns >= 0) { int end = line.indexOf(' ', ns + 4); return Long.parseLong(line.substring(ns + 4, end)); }
            return Long.parseLong(line.substring(0, line.indexOf(' ')));
        } catch (RuntimeException e) { return 0; }
    }
    public static String snapshot(File folder, int limit) throws IOException {
        List<String> lines = new ArrayList<>();
        File[] files = folder.listFiles((dir, name) -> name.matches("pid-[0-9]+\\.log"));
        if (files == null) return "";
        Arrays.sort(files);
        for (File file : files) {
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                long start = Math.max(0, in.length() - limit);
                in.seek(start); byte[] bytes = new byte[(int)(in.length() - start)]; in.readFully(bytes);
                String text = new String(bytes, "UTF-8");
                int begin = start == 0 ? 0 : text.indexOf('\n') + 1;
                int end = text.lastIndexOf('\n');
                if (begin >= 0 && end >= begin) Collections.addAll(lines, text.substring(begin, end).split("\n"));
            }
        }
        lines.sort(Comparator.comparingLong(ProcessJournal::order));
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        if (text.length() > limit) {
            int begin = text.indexOf("\n", text.length() - limit);
            return begin < 0 ? "" : text.substring(begin + 1);
        }
        return text.toString();
    }
}
