"""Fix the duplicate close of the fd owned by fdopen, retaining Android fdsan."""
import struct


def patch_runtime(original):
    assert original[:6] == b"\x7fELF\x02\x01"
    assert struct.unpack_from("<H", original, 18)[0] == 183
    # Locate the instruction through ELF program headers rather than assuming VA=offset.
    address = 0x134F38
    phoff = struct.unpack_from("<Q", original, 32)[0]
    phsize, count = struct.unpack_from("<HH", original, 54)
    locations = []
    for i in range(count):
        typ, flags, offset, va, _, filesz, _, _ = struct.unpack_from("<IIQQQQQQ", original, phoff + i * phsize)
        if typ == 1 and flags & 1 and va <= address < va + filesz:
            locations.append(offset + address - va)
    assert len(locations) == 1
    p = locations[0]
    # ldr fd; bl close; ldr FILE*; bl fclose. fclose already closes that fd.
    assert original[p - 4:p + 12] == bytes.fromhex("602240b94ea2ff97600e40f9a0c8ff97")
    fixed = bytearray(original)
    fixed[p:p + 4] = bytes.fromhex("1f2003d5")  # AArch64 NOP: remove only duplicate close().
    assert fixed[:p] == original[:p] and fixed[p + 4:] == original[p + 4:]
    return bytes(fixed)
