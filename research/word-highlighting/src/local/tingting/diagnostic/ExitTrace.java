package local.tingting.diagnostic;

import java.io.*;
import java.util.*;

/** Reads only abort, signal, causes and the crashing thread from Android's tombstone proto. */
final class ExitTrace {
    static final class Field {
        final int id;
        final Object value;
        Field(int id, Object value) { this.id = id; this.value = value; }
    }

    static long varint(byte[] data, int[] cursor) throws IOException {
        long n = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            if (cursor[0] >= data.length) throw new IOException("truncated varint");
            int b = data[cursor[0]++] & 255;
            if (shift == 63 && (b & 254) != 0) throw new IOException("varint overflow");
            n |= (long)(b & 127) << shift;
            if ((b & 128) == 0) return n;
        }
        throw new IOException("invalid varint");
    }

    static List<Field> fields(byte[] data) throws IOException {
        ArrayList<Field> result = new ArrayList<>();
        int[] p = {0};
        while (p[0] < data.length) {
            long tag = varint(data, p);
            int id = (int)(tag >>> 3), wire = (int)(tag & 7);
            if (id <= 0 || result.size() > 65536) throw new IOException("invalid proto tag");
            if (wire == 0) result.add(new Field(id, varint(data, p)));
            else if (wire == 2) {
                long len = varint(data, p);
                if (len < 0 || len > data.length - p[0]) throw new IOException("invalid proto size");
                result.add(new Field(id, Arrays.copyOfRange(data, p[0], p[0] + (int)len)));
                p[0] += (int)len;
            } else if (wire == 1 || wire == 5) {
                int size = wire == 1 ? 8 : 4;
                if (size > data.length - p[0]) throw new IOException("truncated fixed field");
                p[0] += size;
            } else throw new IOException("unsupported proto wire type " + wire);
        }
        return result;
    }

    static long number(List<Field> f, int id) {
        for (Field v : f) if (v.id == id && v.value instanceof Long) return (Long)v.value;
        return 0;
    }
    static byte[] bytes(List<Field> f, int id) {
        for (Field v : f) if (v.id == id && v.value instanceof byte[]) return (byte[])v.value;
        return new byte[0];
    }
    static String string(List<Field> f, int id) throws IOException {
        return new String(bytes(f, id), "UTF-8");
    }

    static String summarize(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192]; int n;
        while ((n = stream.read(chunk)) != -1) {
            if (buffer.size() + n > 4 * 1024 * 1024) throw new IOException("tombstone exceeds 4 MiB");
            buffer.write(chunk, 0, n);
        }
        List<Field> root = fields(buffer.toByteArray());
        StringBuilder out = new StringBuilder();
        List<Field> signal = fields(bytes(root, 10));
        out.append("信号=").append(string(signal, 2)).append(" code=").append(string(signal, 4)).append('\n');
        out.append("Abort=").append(string(root, 14)).append('\n');
        for (Field f : root) if (f.id == 15 && f.value instanceof byte[]) {
            out.append("原因=").append(string(fields((byte[])f.value), 1)).append('\n');
        }
        long tid = number(root, 6);
        for (Field f : root) if (f.id == 16 && f.value instanceof byte[]) {
            List<Field> entry = fields((byte[])f.value);
            if (number(entry, 1) != tid) continue;
            List<Field> thread = fields(bytes(entry, 2));
            out.append("崩溃线程=").append(tid).append(' ').append(string(thread, 2)).append('\n');
            int count = 0;
            for (Field frame : thread) if (frame.id == 4 && frame.value instanceof byte[] && count++ < 16) {
                List<Field> v = fields((byte[])frame.value);
                out.append("#").append(count - 1).append(" pc ").append(Long.toHexString(number(v, 1)))
                    .append(' ').append(string(v, 6)).append(' ').append(string(v, 4))
                    .append('+').append(number(v, 5)).append('\n');
            }
        }
        return out.toString();
    }
}
