"""Select the existing ARM64 fixed-point BET3 modules for Ting-Ting's FIX3 data."""
from pathlib import Path
import hashlib
import json
import struct
import zipfile
from elf_view import ElfView

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'ios-tingting-arm64-fix1.apk'
OUTPUT = ROOT / 'tingting-arm64-fix2-unsigned.apk'
HEADER = 'assets/vocalizer/mnc/ve_mnc_ting-ting_compact.hdr'
REPLACEMENTS = {
    b'<COMPONENT>select/bet3</COMPONENT>': b'<COMPONENT>select/bet3fxd</COMPONENT>',
    b'<COMPONENT>synth/bet3</COMPONENT>': b'<COMPONENT>synth/bet3fxd</COMPONENT>',
}

def uint16(data, p):
    return struct.unpack_from('<H', data, p)[0]

def uint32(data, p):
    return struct.unpack_from('<I', data, p)[0]

def length(data, p, utf8):
    n = data[p] if utf8 else uint16(data, p)
    high, width = (128, 1) if utf8 else (32768, 2)
    if n & high:
        tail = data[p + width] if utf8 else uint16(data, p + width)
        return ((n & (high - 1)) << (8 if utf8 else 16)) | tail, p + 2 * width
    return n, p + width

def manifest_version(data):
    result, strings, changed = bytearray(data), [], False
    p = uint16(data, 2)
    while p < len(data):
        typ, size = uint16(data, p), uint32(data, p + 4)
        if typ == 1:
            utf8, start = bool(uint32(data, p + 16) & 256), uint32(data, p + 20)
            for i in range(uint32(data, p + 8)):
                pos = p + start + uint32(data, p + uint16(data, p + 2) + 4 * i)
                n, pos = length(data, pos, utf8)
                if utf8:
                    n, pos = length(data, pos, True)
                strings.append(data[pos:pos + n * (1 if utf8 else 2)].decode('utf-8' if utf8 else 'utf-16le'))
        elif typ == 0x102 and strings[uint32(data, p + 20)] == 'manifest':
            start, width, count = p + 16 + uint16(data, p + 24), uint16(data, p + 26), uint16(data, p + 28)
            for i in range(count):
                a = start + width * i
                if strings[uint32(data, a + 4)] == 'versionCode':
                    assert not changed and uint32(data, a + 16) == 3
                    struct.pack_into('<I', result, a + 16, 4)
                    changed = True
        p += size
    assert changed
    return bytes(result)

with zipfile.ZipFile(SOURCE) as source:
    # Confirm the registration names and file formats before changing the declaration.
    elf = ElfView(source.read('lib/arm64-v8a/libNuanceVocalizer.so'))
    assert elf.string(elf.pointer(0x75f0c0)) == 'select/bet3'
    assert elf.describe(elf.pointer(0x75f0c8)) == 'tts_ve_select_float_bet3_GetInterface'
    assert elf.string(0x708fc8) == 'FLT3'
    assert elf.string(elf.pointer(0x75f100)) == 'select/bet3fxd'
    assert elf.describe(elf.pointer(0x75f108)) == 'tts_ve_select_bet3_GetInterface'
    assert elf.string(0x70a000) == 'FIX3'
    assert elf.string(elf.pointer(0x75f120)) == 'synth/bet3fxd'
    assert elf.describe(elf.pointer(0x75f128)) == 'tts_ve_synth_bet3_GetInterface'
    assert elf.string(0x70aab0) == 'HMEX'
    assert bytes(b ^ 0xa9 for b in source.read('assets/vocalizer/select_ting-ting_bet3f22.xmf')[8:12]) == b'FIX3'
    assert bytes(b ^ 0xa9 for b in source.read('assets/vocalizer/synth_med_fxd_bet3f22.xmf')[8:12]) == b'HMEX'
    with zipfile.ZipFile(OUTPUT, 'w') as output:
        for item in source.infolist():
            if item.filename.startswith('META-INF/'):
                continue
            data = source.read(item.filename)
            if item.filename == HEADER:
                for old, new in REPLACEMENTS.items():
                    assert data.count(old) == 1
                    data = data.replace(old, new)
            elif item.filename == 'AndroidManifest.xml':
                data = manifest_version(data)
            output.writestr(item, data)
print(OUTPUT)
