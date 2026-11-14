from pathlib import Path
import hashlib
import json
import struct
import zipfile
import zlib
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from elf_view import ElfView

root = Path(__file__).resolve().parent
output = root.parent.parent / 'apk'
lib = 'lib/arm64-v8a/libNuanceVocalizer.so'

def u16(data, p): return struct.unpack_from('<H', data, p)[0]
def u32(data, p): return struct.unpack_from('<I', data, p)[0]
def length(data, p, utf8):
    n, width, high = (data[p], 1, 128) if utf8 else (u16(data, p), 2, 32768)
    if n & high:
        tail = data[p + width] if utf8 else u16(data, p + width)
        return ((n & (high - 1)) << (8 if utf8 else 16)) | tail, p + 2 * width
    return n, p + width

def xml_nodes(data):
    p, strings, result = u16(data, 2), [], []
    while p < len(data):
        typ, size = u16(data, p), u32(data, p + 4)
        if typ == 1:
            utf8 = bool(u32(data, p + 16) & 256)
            for i in range(u32(data, p + 8)):
                start = p + u32(data, p + 20) + u32(data, p + u16(data, p + 2) + 4 * i)
                n, start = length(data, start, utf8)
                if utf8: n, start = length(data, start, True)
                strings.append(data[start:start + n * (1 if utf8 else 2)].decode('utf-8' if utf8 else 'utf-16le'))
        elif typ == 0x102:
            attributes = {}
            start, width, count = p + 16 + u16(data, p + 24), u16(data, p + 26), u16(data, p + 28)
            for i in range(count):
                a = start + width * i
                value = u32(data, a + 16)
                attributes[strings[u32(data, a + 4)]] = strings[value] if data[a + 15] == 3 else value
            result.append((strings[u32(data, p + 20)], attributes))
        p += size
    return result

report = {'device_runtime_test': 'PENDING: adb devices returned no attached device', 'artifacts': {}}
with zipfile.ZipFile(root.parent / 'ios-tingting-arm64-fix2.apk') as baseline:
    for variant, filename, package, version in [
        ('diagnostic', '婷婷诊断_1.4.apk', 'local.tingting.diagnostic', 5),
        ('production', 'iOS-婷婷_启动修复版_v2.apk', 'com.android.apple64ttsx', 6),
    ]:
        path = output / filename
        assert path.read_bytes() == (root / variant / 'final.apk').read_bytes()
        with zipfile.ZipFile(path) as apk:
            assert apk.testzip() is None
            nodes = xml_nodes(apk.read('AndroidManifest.xml'))
            manifest = next(a for tag, a in nodes if tag == 'manifest')
            assert manifest['package'] == package and manifest['versionCode'] == version
            app = next(a for tag, a in nodes if tag == 'application')
            assert app['extractNativeLibs'] == 0xffffffff and app['allowBackup'] == 0
            services = [a for tag, a in nodes if tag == 'service']
            assert len(services) == 1 and services[0]['name'] == 'com.android.appletts.compat.ReadyTtsService'
            assert not any(tag == 'uses-permission' for tag, a in nodes)
            if variant == 'production':
                assert app['label'] == 'iOS-婷婷'
                assert not any(tag == 'category' and a.get('name') == 'android.intent.category.LAUNCHER' for tag, a in nodes)
            for name in baseline.namelist():
                if name.startswith(('assets/', 'lib/')):
                    assert baseline.read(name) == apk.read(name), name
                    if name.endswith('.xmf'): assert apk.getinfo(name).compress_type == zipfile.ZIP_STORED
            assert len([n for n in apk.namelist() if n.startswith('assets/vocalizer/')]) == 8
            assert apk.getinfo(lib).compress_type == zipfile.ZIP_DEFLATED
            elf = ElfView(apk.read(lib))
            assert all(s[7] >= 16384 and (s[3] - s[2]) % 16384 == 0 for s in elf.segments if s[0] == 1)
            for dex in ['classes.dex', 'classes2.dex']:
                data = apk.read(dex)
                assert hashlib.sha1(data[32:]).digest() == data[12:32]
                assert zlib.adler32(data[12:]) & 0xffffffff == u32(data, 8)
            legacy = apk.read('classes2.dex' if variant == 'diagnostic' else 'classes.dex')
            assert legacy == baseline.read('classes.dex')
            report['artifacts'][filename] = {
                'package': package, 'version_code': version, 'bytes': path.stat().st_size,
                'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                'native_runtime_and_voice_assets_identical_to_working_fix2': True,
                'new_service_registered': services[0]['name'],
            }
report['host_checks'] = ['Compilation and D8', 'Signature verification', 'ZIP and ELF alignment',
                         'ReadyGate loading, failure, stop, timeout and close ordering',
                         'WAV PCM and truncation handling', 'Historical native-crash parser',
                         '3-process, 6-thread UTF-8 journal and incomplete-tail handling']
report['user_device_evidence'] = {
    'diagnostic_version': '1.3-ready',
    'natural_startup_early_samples': 0,
    'natural_startup_gated_samples': 114840,
    'installed_engine_first_request_pcm_bytes': 0,
    'installed_engine_after_4_seconds_pcm_bytes': 147840,
    'installed_native_library_matches_apk': True,
    'confirmed': 'Startup requests can be silently dropped before voice load completes',
    'still_unconfirmed': 'Historical overwrite failure cause and fixed system service cold-start result',
    'diagnostic_defects_addressed': ['Cross-process shared journal replaced with per-process files',
                                    'Context.bindService interception replaced with per-request service acknowledgement'],
}
report['device_checks_required'] = ['Installed engine immediate versus delayed request',
                                  'Natural startup old call versus readiness gate',
                                  'Fixed service through Android TTS API',
                                  'Overwrite installation without uninstalling']
(root / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
