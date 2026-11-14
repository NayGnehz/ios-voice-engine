import com.android.appletts.compat.WordRanges;
public final class WordRangesCheck {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        WordRanges words = new WordRanges("你好🙂2026年。");
        check(words.add(0, 0, 2)); check(!words.add(0, 0, 2));
        check(!words.add(1, 3, 1)); // no middle of a surrogate pair
        check(words.add(22050, 2, 2)); check(words.add(44100, 4, 4));
        check(!words.add(10, 8, 1)); check(!words.add(50000, 8, Integer.MAX_VALUE));
        StringBuilder got = new StringBuilder();
        words.drainThrough(22049, (f,s,e) -> got.append(f+":"+s+":"+e+";"));
        check(got.toString().equals("0:0:2;"));
        words.drainThrough(44100, (f,s,e) -> got.append(f+":"+s+":"+e+";"));
        check(got.toString().equals("0:0:2;22050:2:4;44100:4:8;"));
        check(words.accepted == 3 && words.rejected == 3 && words.duplicates == 1);
        System.out.println("PASS: native frame preservation, UTF-16 ranges, numerals, future frames, duplicates and invalid marks");
    }
}
