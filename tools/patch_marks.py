"""Restore the original Java SDK's native mark-array dispatch; no native engine/model changes."""
import struct
import hashlib
import zlib

def patch_marks(data):
    dex = bytearray(data)
    def string(index):
        table = struct.unpack_from('<I', dex, 60)[0]
        pos = struct.unpack_from('<I', dex, table + index * 4)[0]
        while dex[pos] & 128: pos += 1
        pos += 1
        return dex[pos:dex.index(0, pos)].decode('utf-8', errors='replace')
    methods = struct.unpack_from('<I', dex, 92)[0]
    class_index, proto, name_index = struct.unpack_from('<HHI', dex, methods + 0x113 * 8)
    types = struct.unpack_from('<I', dex, 68)[0]
    assert string(struct.unpack_from('<I', dex, types + class_index * 4)[0]) == 'Lcom/nuance/android/vocalizer/VocalizerEngine;'
    assert string(name_index) == 'processSpeechMark'
    p = 0x11ae0
    registers, ins, outs, tries, debug, size = struct.unpack_from('<HHHHII', dex, p)
    assert (registers, ins, outs, tries, size) == (4, 2, 2, 0, 24)
    assert dex[p + 16:p + 20] == bytes.fromhex('2200b100')  # original log-only stub
    # v2=this, v3=marks, v0=index, v1=length/mark. Skip null arrays/elements.
    # if-null v3,end; index=0; loop: length; if index>=length,end; mark=marks[index];
    # if-null mark,next; invoke-direct processSpeechMark(this,mark); next: index++; goto loop; end:return.
    code = bytes.fromhex('380310001200213135100c004601030038010500702013011200d800000128f40e00')
    assert len(code) == 34
    dex[p + 16:p + 64] = code + bytes(48 - len(code))
    dex[12:32] = hashlib.sha1(dex[32:]).digest()
    struct.pack_into('<I', dex, 8, zlib.adler32(dex[12:]) & 0xffffffff)
    return bytes(dex)
