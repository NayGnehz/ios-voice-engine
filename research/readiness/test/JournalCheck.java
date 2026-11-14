import com.android.appletts.compat.ProcessJournal;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class JournalCheck {
    static String record(int writer, int thread, int i) {
        return "1000 ns=" + (writer * 10000 + thread * 100 + i) + " pid=" + writer
            + " 中文🙂记录=" + thread + "-" + i;
    }
    public static void main(String[] args) throws Exception {
        File folder = new File(args[0]);
        if (args.length == 2) {
            int writer = Integer.parseInt(args[1]);
            Thread[] threads = new Thread[2];
            for (int t = 0; t < 2; t++) {
                final int thread = t;
                threads[t] = new Thread(() -> {
                    try { for (int i = 0; i < 100; i++) ProcessJournal.append(folder, "pid-" + writer, record(writer, thread, i)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                threads[t].start();
            }
            for (Thread thread : threads) thread.join();
            return;
        }
        File run = new File(folder, "journal-" + UUID.randomUUID());
        if (!run.mkdirs()) throw new IOException("cannot create test directory");
        String java = new File(System.getProperty("java.home"), "bin/java.exe").getAbsolutePath();
        List<Process> children = new ArrayList<>();
        for (int i = 1; i <= 3; i++) children.add(new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            "JournalCheck", run.getAbsolutePath(), String.valueOf(i)).inheritIO().start());
        for (Process process : children) {
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) throw new AssertionError("writer process failed");
        }
        StringBuilder expected = new StringBuilder();
        for (int writer = 1; writer <= 3; writer++) for (int thread = 0; thread < 2; thread++) for (int i = 0; i < 100; i++)
            expected.append(record(writer, thread, i)).append('\n');
        String result = ProcessJournal.snapshot(run, 1000000);
        if (!result.equals(expected.toString())) throw new AssertionError("cross-process UTF-8 records corrupted or misordered");
        try (FileOutputStream partial = new FileOutputStream(new File(run, "pid-999.log"))) {
            partial.write("9999 ns=9999 unfinished".getBytes("UTF-8"));
        }
        if (!ProcessJournal.snapshot(run, 1000000).equals(result)) throw new AssertionError("incomplete tail exposed");
        String nonce = "TTD-12345678-1234-1234-1234-123456789abc-1";
        ProcessJournal.append(run, "pid-1000", "1100 ns=1100 pid=1000 SERVICE REQUEST_PROBE=" + nonce);
        if (!ProcessJournal.snapshot(run, 1000000).contains("SERVICE REQUEST_PROBE=" + nonce + "\n"))
            throw new AssertionError("service receipt cannot be matched exactly");
        System.out.println("PASS: 3 processes, 6 threads, 600 UTF-8 records merge exactly; incomplete tail ignored");
    }
}
