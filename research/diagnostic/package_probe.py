from pathlib import Path
import zipfile
ROOT = Path(__file__).resolve().parent
with zipfile.ZipFile(ROOT / 'shell.apk') as shell, zipfile.ZipFile(ROOT.parent / 'ios-tingting-arm64-fix1.apk') as engine, zipfile.ZipFile(ROOT / 'probe-unsigned.apk', 'w') as output:
    for item in shell.infolist():
        output.writestr(item, shell.read(item.filename))
    output.writestr('classes.dex', (ROOT / 'dex/classes.dex').read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    output.writestr('classes2.dex', engine.read('classes.dex'), compress_type=zipfile.ZIP_DEFLATED)
    for item in engine.infolist():
        if item.filename.startswith(('assets/', 'lib/')):
            output.writestr(item, engine.read(item.filename))
print(ROOT / 'probe-unsigned.apk')
