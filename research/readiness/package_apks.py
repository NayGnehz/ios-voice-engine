from pathlib import Path
import zipfile

root = Path(__file__).resolve().parent
source = root.parent / 'ios-tingting-arm64-fix2.apk'
with zipfile.ZipFile(source) as engine:
    for variant in ['diagnostic', 'production']:
        folder = root / variant
        with zipfile.ZipFile(folder / 'shell.apk') as shell, zipfile.ZipFile(folder / 'unsigned.apk', 'w', compresslevel=9) as output:
            for item in shell.infolist():
                output.writestr(item.filename, shell.read(item.filename), compress_type=item.compress_type)
            new_dex = (folder / 'dex/classes.dex').read_bytes()
            legacy_dex = engine.read('classes.dex')
            output.writestr('classes.dex', new_dex if variant == 'diagnostic' else legacy_dex, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
            output.writestr('classes2.dex', legacy_dex if variant == 'diagnostic' else new_dex, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
            for item in engine.infolist():
                if not item.filename.startswith(('assets/', 'lib/')):
                    continue
                method = zipfile.ZIP_DEFLATED if item.filename.endswith('.so') else item.compress_type
                output.writestr(item.filename, engine.read(item.filename), compress_type=method, compresslevel=9)
        print(folder / 'unsigned.apk')
