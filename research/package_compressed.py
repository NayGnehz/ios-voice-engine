"""Compress the native library while preserving every APK payload byte from working fix2."""
from pathlib import Path
import copy
import zipfile

root = Path(__file__).resolve().parent
source = root / 'ios-tingting-arm64-fix2.apk'
output = root / 'tingting-compressed-unsigned.apk'
with zipfile.ZipFile(source) as original, zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as packed:
    for entry in original.infolist():
        if entry.filename.startswith('META-INF/'):
            continue
        info = copy.copy(entry)
        info.extra = b''  # Discard obsolete zipalign padding; align again before signing.
        if info.filename == 'lib/arm64-v8a/libNuanceVocalizer.so':
            info.compress_type = zipfile.ZIP_DEFLATED
        packed.writestr(info, original.read(entry.filename), compresslevel=9)
with zipfile.ZipFile(source) as original, zipfile.ZipFile(output) as packed:
    names = {n for n in original.namelist() if not n.startswith('META-INF/')}
    assert names == set(packed.namelist())
    for name in names:
        assert original.read(name) == packed.read(name)
        if name.endswith('.xmf'):
            assert packed.getinfo(name).compress_type == zipfile.ZIP_STORED
    assert packed.getinfo('lib/arm64-v8a/libNuanceVocalizer.so').compress_type == zipfile.ZIP_DEFLATED
print(output)
