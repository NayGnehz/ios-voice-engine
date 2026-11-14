from pathlib import Path
import hashlib
import json
import struct
import zipfile

root = Path(__file__).resolve().parent
libname = 'lib/arm64-v8a/libNuanceVocalizer.so'
with zipfile.ZipFile(root / 'ios-tingting-arm64.apk') as old, zipfile.ZipFile(root / 'ios-tingting-arm64-fix1.apk') as fixed, zipfile.ZipFile(root / 'tingting-diagnostic-fix1.apk') as probe, zipfile.ZipFile(root.parent / '苹果中文语音库_clone.apk') as original:
    before, after = old.read(libname), fixed.read(libname)
    assert len(before) == len(after)
    diffs = [p for p, (a, b) in enumerate(zip(before, after)) if a != b]
    assert diffs == list(range(0x134f38, 0x134f3c)), diffs
    assert after[0x134f38:0x134f3c] == bytes.fromhex('1f2003d5')
    assert probe.read(libname) == after
    assert fixed.read('classes.dex') == old.read('classes.dex') == probe.read('classes2.dex')
    assets = [n for n in fixed.namelist() if n.startswith('assets/vocalizer/')]
    assert len(assets) == 8
    for n in assets:
        assert original.read(n) == fixed.read(n) == probe.read(n)
    assert [n for n in fixed.namelist() if n.endswith('.so')] == [libname]
    phoff = struct.unpack_from('<Q', after, 32)[0]
    entsize, count = struct.unpack_from('<HH', after, 54)
    alignment = []
    for i in range(count):
        typ, flags, offset, va, _, size, _, align = struct.unpack_from('<IIQQQQQQ', after, phoff + i * entsize)
        if typ == 1:
            assert align >= 16384 and (va - offset) % 16384 == 0
            alignment.append(align)
    (root / 'candidate-libs/libNuanceVocalizer-fix1.so').write_bytes(after)
    report = {
        'fix': 'Remove duplicate close(fd) at VA 0x134f38; retain fclose(FILE*) and fdsan',
        'native_changed_bytes': diffs,
        'runtime_sha256': hashlib.sha256(after).hexdigest(),
        'voice_assets_identical_to_original': len(assets),
        'production_and_diagnostic_native_code_identical': True,
        'elf_load_alignments': alignment,
        'device_audio_test': 'PENDING: no connected Android device',
        'outputs': {name: {'bytes': (root / name).stat().st_size, 'sha256': hashlib.sha256((root / name).read_bytes()).hexdigest()} for name in ['ios-tingting-arm64-fix1.apk', 'tingting-diagnostic-fix1.apk']},
    }
    (root / 'fix1-evidence.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(report, indent=2, ensure_ascii=False))
