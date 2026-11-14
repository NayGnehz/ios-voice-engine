import com.android.appletts.compat.SpeechRate;

public final class SpeechRateCheck {
    static void check(int requested, int expected) {
        int actual = SpeechRate.toNative(requested);
        if (actual != expected) throw new AssertionError(requested + " -> " + actual + ", expected " + expected);
    }
    public static void main(String[] args) {
        for (int rate : new int[]{100, 102, 104, 106, 108, 110}) check(rate, rate);
        for (int rate = 50; rate <= 400; rate++) check(rate, rate);
        for (int rate : new int[]{Integer.MIN_VALUE, -1, 0, 49}) check(rate, 50);
        for (int rate : new int[]{401, 1000, Integer.MAX_VALUE}) check(rate, 400);
        System.out.println("PASS: 100/102/104/106/108/110 preserved, every integer 50..400, continuous 100/101 boundary and clamp limits");
    }
}
