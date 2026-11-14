import com.android.appletts.compat.ReadyGate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReadyGateCheck {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            ReadyGate gate = new ReadyGate(); gate.initializing(); gate.loading();
            Future<Boolean> waiting = worker.submit(() -> gate.await(3000, () -> false));
            Thread.sleep(50); check(!waiting.isDone()); gate.ready(); check(waiting.get(1, TimeUnit.SECONDS));
            gate.close(); gate.ready(); check(!gate.await(100, () -> false));
            ReadyGate failure = new ReadyGate(); failure.loading(); failure.fail("loadVoice=false");
            check(!failure.await(100, () -> false));
            ReadyGate slow = new ReadyGate(); slow.initializing(); check(!slow.await(20, () -> false));
            AtomicBoolean cancel = new AtomicBoolean();
            Future<Boolean> cancelled = worker.submit(() -> slow.await(3000, cancel::get));
            cancel.set(true); check(!cancelled.get(1, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
        System.out.println("PASS: readiness ordering, failed load, timeout, cancellation and late-ready after close");
    }
}
