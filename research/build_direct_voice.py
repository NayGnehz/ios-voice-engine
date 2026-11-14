"""Make the ARM64 trial a launcher-free, fixed Ting-Ting system TTS engine."""
from pathlib import Path
import hashlib
import struct
import zipfile
import zlib
from patch_fd_close import patch_runtime

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / "tingting-arm64-trial.apk"
OUTPUT = ROOT / "tingting-arm64-fix1-unsigned.apk"
LABEL = "iOS-婷婷"
VOICE_FILES = {
    "clc_mnc_cfg1.xmf", "clc_mnc_ting-ting_cfg1.xmf", "clm.xmf", "lid.xmf",
    "mnc/ve_mnc_ting-ting_compact.hdr", "select_ting-ting_bet3f22.xmf",
    "synth_med_fxd_bet3f22.xmf", "sysdct.xmf",
}


def u16(b, p):
    return struct.unpack_from("<H", b, p)[0]


def u32(b, p):
    return struct.unpack_from("<I", b, p)[0]


def read_len(b, p, utf8):
    if utf8:
        n = b[p]
        return (((n & 127) << 8) | b[p + 1], p + 2) if n & 128 else (n, p + 1)
    n = u16(b, p)
    return (((n & 32767) << 16) | u16(b, p + 2), p + 4) if n & 32768 else (n, p + 2)


def enc_len(n, utf8):
    if utf8:
        return bytes([n]) if n < 128 else bytes([(n >> 8) | 128, n & 255])
    return struct.pack("<H", n) if n < 32768 else struct.pack("<HH", (n >> 16) | 32768, n & 65535)


def pool_strings(pool):
    count, start, utf8 = u32(pool, 8), u32(pool, 20), bool(u32(pool, 16) & 256)
    result = []
    for i in range(count):
        p = start + u32(pool, u16(pool, 2) + i * 4)
        n, p = read_len(pool, p, utf8)
        if utf8:
            n, p = read_len(pool, p, True)
        result.append(pool[p:p + n * (1 if utf8 else 2)].decode("utf-8" if utf8 else "utf-16le"))
    return result


def rename_pool(pool):
    strings = pool_strings(pool)
    if "婷婷语音测试版" not in strings:
        return pool
    assert u32(pool, 12) == 0, "Styled pools are not handled"
    strings = [LABEL if s == "婷婷语音测试版" else s for s in strings]
    utf8 = bool(u32(pool, 16) & 256)
    payload, offsets = bytearray(), []
    for s in strings:
        offsets.append(len(payload))
        n16 = len(s.encode("utf-16le")) // 2
        if utf8:
            raw = s.encode("utf-8")
            payload += enc_len(n16, True) + enc_len(len(raw), True) + raw + b"\0"
        else:
            payload += enc_len(n16, False) + s.encode("utf-16le") + b"\0\0"
    payload += b"\0" * (-len(payload) % 4)
    header = bytearray(pool[:u16(pool, 2)])
    start = len(header) + 4 * len(offsets)
    struct.pack_into("I", header, 4, start + len(payload))
    struct.pack_into("I", header, 16, u32(header, 16) & ~1)
    struct.pack_into("I", header, 20, start)
    return bytes(header) + struct.pack("<" + "I" * len(offsets), *offsets) + payload


def transform(data, xml=False):
    header = bytearray(data[:u16(data, 2)])
    p, parts, strings, skip = len(header), [], [], 0
    while p < len(data):
        typ, size = u16(data, p), u32(data, p + 4)
        chunk = data[p:p + size]
        p += size
        if typ == 1:
            chunk = rename_pool(chunk)
            strings = pool_strings(chunk)
        if xml and typ == 0x102:
            tag = strings[u32(chunk, 20)]
            attr_size, count = u16(chunk, 26), u16(chunk, 28)
            start = 16 + u16(chunk, 24)
            attrs = [chunk[start + i * attr_size:start + (i + 1) * attr_size] for i in range(count)]
            names = {strings[u32(a, 4)]: a for a in attrs}
            name_attr = names.get("name")
            component = strings[u32(name_attr, 8)] if name_attr and u32(name_attr, 8) != 0xFFFFFFFF else ""
            if skip or (tag == "activity" and component == "com.android.appletts.EngineSettings"):
                skip += 1
                continue
            # Remove the extra voice-picker route, both from the service and tts-engine metadata.
            filtered = [a for a in attrs if strings[u32(a, 4)] != "settingsActivity"]
            if tag == "manifest":
                for i, a in enumerate(filtered):
                    if strings[u32(a, 4)] == "versionCode":
                        a = bytearray(a)
                        struct.pack_into("<I", a, 16, 3)
                        filtered[i] = bytes(a)
            changed = bytearray(chunk[:start])
            struct.pack_into("<H", changed, 28, len(filtered))
            # None of these XML nodes use id/class/style special attribute indices.
            assert not any(u16(chunk, off) for off in (30, 32, 34))
            chunk = changed + b"".join(filtered)
            struct.pack_into("<I", chunk, 4, len(chunk))
        elif xml and typ == 0x103 and skip:
            skip -= 1
            continue
        elif xml and skip:
            continue
        parts.append(chunk)
    assert skip == 0
    result = header + b"".join(parts)
    struct.pack_into("<I", result, 4, len(result))
    return bytes(result)


with zipfile.ZipFile(SOURCE) as source, zipfile.ZipFile(OUTPUT, "w") as output:
    for entry in source.infolist():
        if entry.filename.startswith("META-INF/"):
            continue
        if entry.filename.startswith("assets/vocalizer/") and entry.filename.removeprefix("assets/vocalizer/") not in VOICE_FILES:
            continue
        if entry.filename.startswith("assets/emoji/") and entry.filename != "assets/emoji/zho.xml":
            continue
        data = source.read(entry.filename)
        if entry.filename == "AndroidManifest.xml":
            data = transform(data, xml=True)
        elif entry.filename == "resources.arsc":
            data = transform(data)
        elif entry.filename.startswith("res/") and data[:4] == b"\x03\x00\x08\x00":
            data = transform(data, xml=True)
        elif entry.filename == "classes.dex":
            dex = bytearray(data)
            # Advertise only Chinese in CHECK_TTS_DATA; remove the English entry.
            assert dex[0xE0BC:0xE0C2] == bytes.fromhex("2420de001000")
            dex[0xE0BC:0xE0C2] = bytes.fromhex("2410de000000")
            # The only remaining voice is Ting-Ting at index 0. Ignore persisted selections.
            assert dex[0xED18:0xED1A] == bytes.fromhex("0a01")
            assert dex[0xED1A:0xED1E] == bytes.fromhex("46050501")
            # Replace getInt + move-result with const/16 v1,0 and two nops.
            assert dex[0xED12:0xED18] == bytes.fromhex("723023003102")
            dex[0xED12:0xED1A] = bytes.fromhex("1301000000000000")
            dex[12:32] = hashlib.sha1(dex[32:]).digest()
            struct.pack_into("<I", dex, 8, zlib.adler32(dex[12:]) & 0xFFFFFFFF)
            data = bytes(dex)
            (ROOT / "direct-classes.dex").write_bytes(data)
        elif entry.filename == "lib/arm64-v8a/libNuanceVocalizer.so":
            data = patch_runtime(data)
        output.writestr(entry, data)
print(OUTPUT)
