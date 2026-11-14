package local.tingting.diagnostic;
import java.io.*;

public final class WavInfo {
    public long audioBytes;
    public int peak;
    public static WavInfo read(File file) throws IOException {
        WavInfo result = new WavInfo();
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 12 || in.readInt() != 0x52494646) throw new IOException("not a RIFF WAV");
            in.skipBytes(4);
            if (in.readInt() != 0x57415645) throw new IOException("not WAVE");
            while (in.getFilePointer() + 8 <= in.length()) {
                int tag = in.readInt();
                long size = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
                long begin = in.getFilePointer();
                if (size > in.length() - begin) throw new IOException("truncated WAV chunk");
                if (tag == 0x64617461) {
                    result.audioBytes += size;
                    for (long i = 0; i + 1 < size; i += 2) {
                        int sample = Short.reverseBytes(in.readShort());
                        result.peak = Math.max(result.peak, Math.abs(sample));
                    }
                }
                in.seek(begin + size + (size & 1));
            }
        }
        return result;
    }
}
