import com.android.appletts.compat.ReadyGate;
import local.tingting.diagnostic.WavInfo;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class ReadinessCheck {
    static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    static void le32(DataOutputStream out, int value) throws IOException { out.writeInt(Integer.reverseBytes(value)); }
    public static void main(String[] args) throws Exception {
        ReadyGate gate = new ReadyGate(); gate.initializing(); gate.loading();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> waiting = worker.submit(() -> gate.await(3000, () -> false));
            Thread.sleep(80); check(!waiting.isDone(), "request passed before loadVoice returned");
            gate.ready(); check(waiting.get(1, TimeUnit.SECONDS), "successful load did not release waiter");
            gate.close(); gate.ready(); check(gate.status() == ReadyGate.Status.CLOSED, "late callback reopened closed session");
            check(!gate.await(100, () -> false), "closed engine accepted request");
            ReadyGate closing = new ReadyGate(); closing.initializing();
            Future<Boolean> closed = worker.submit(() -> closing.await(3000, () -> false));
            closing.close(); check(!closed.get(1, TimeUnit.SECONDS), "close did not release pending request");
            ReadyGate failure = new ReadyGate(); failure.initializing(); failure.loading();
            Future<Boolean> failed = worker.submit(() -> failure.await(3000, () -> false));
            failure.fail("loadVoice=false"); check(!failed.get(1, TimeUnit.SECONDS), "failed load accepted request");
            ReadyGate slow = new ReadyGate(); slow.initializing();
            long begin = System.nanoTime(); check(!slow.await(50, () -> false), "timeout passed");
            check((System.nanoTime() - begin) / 1000000L < 1000, "timeout exceeded bound");
            AtomicBoolean cancel = new AtomicBoolean();
            Future<Boolean> cancelled = worker.submit(() -> slow.await(3000, cancel::get));
            cancel.set(true); check(!cancelled.get(1, TimeUnit.SECONDS), "stop did not cancel readiness wait");
            ReadyGate loaded = new ReadyGate(); loaded.initializing(); loaded.loading(); loaded.ready();
            check(!loaded.await(100, () -> true), "cancelled request passed ready engine");
        } finally { worker.shutdownNow(); }
        File wav = File.createTempFile("tingting-check-", ".wav", new File(args[0]));
        try {
            try (DataOutputStream out = new DataOutputStream(new FileOutputStream(wav))) {
                out.writeBytes("RIFF"); le32(out, 40); out.writeBytes("WAVEfmt "); le32(out, 16);
                out.write(new byte[]{1,0,1,0,34,86,0,0,68,(byte)172,0,0,2,0,16,0});
                out.writeBytes("data"); le32(out, 4); out.write(new byte[]{0,0,(byte)255,127});
            }
            WavInfo info = WavInfo.read(wav);
            check(info.audioBytes == 4 && info.peak == 32767, "valid PCM not recognized");
            try (RandomAccessFile out = new RandomAccessFile(wav, "rw")) { out.setLength(46); }
            try { WavInfo.read(wav); throw new AssertionError("truncated audio accepted"); }
            catch (IOException expected) {}
        } finally { if (!wav.delete()) wav.deleteOnExit(); }
        System.out.println("PASS: readiness ordering, failure, cancellation, timeout, close, PCM and truncated WAV checks");
    }
}
