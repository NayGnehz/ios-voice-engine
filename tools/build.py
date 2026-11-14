"""Build five standalone ARM64 engines from explicitly supplied, pinned APK inputs.

No dependency downloads, private keys, or third-party binaries are stored here.
All generated files must go to the explicit work/output directories.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape
import zipfile
import zlib

from elf_view import ElfView
from patch_fd_close import patch_runtime
from patch_marks import patch_marks

ROOT = Path(__file__).resolve().parent.parent
VERSION = '1.5.0'
VERSION_CODE = 9
ORIGINAL_SHA = '3768e8f7f1c7cefc169cc5fbb5354dbd8a7cd8efdddfe4b45f8486e4d767946f'
RUNTIME_APK_SHA = '8660625f39035c668a55c37e4c5c6086d499d1c3b2c8ca3c29465c54b537a4ed'
NATIVE_PATH = 'lib/arm64-v8a/libNuanceVocalizer.so'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(args):
    subprocess.run([str(a) for a in args], check=True)


def patched_sdk(original):
    data = bytearray(patch_marks(original))
    require(data[0xEBC4:0xEBCA] == bytes.fromhex('7100b1000000'), 'unexpected ARM32 utility call')
    data[0xEBC4:0xEBCA] = bytes(6)
    data[12:32] = hashlib.sha1(data[32:]).digest()
    struct.pack_into('<I', data, 8, zlib.adler32(data[12:]) & 0xffffffff)
    return bytes(data)


def voice_assets(original, voice):
    key, cfg = voice['sdk_name'].lower(), voice['cfg']
    names = ['clc_mnc_' + cfg + '.xmf', 'clc_mnc_' + key + '_' + cfg + '.xmf',
             'clm.xmf', 'lid.xmf', 'sysdct.xmf', 'synth_med_fxd_bet3f22.xmf',
             'select_' + key + '_bet3f22.xmf', voice['header']]
    assets = {n: original.read('assets/vocalizer/' + n) for n in names}
    header = ET.fromstring(assets[voice['header']])
    params = header.find('./HEADER/PARAMETERS')
    require(params.findtext('voice') == voice['sdk_name'], 'voice/header mismatch')
    require(params.findtext('frequencyhz') == '22050', 'unexpected voice sample rate')
    require(bytes(b ^ 0xa9 for b in assets['select_' + key + '_bet3f22.xmf'][8:12]) == b'FIX3', 'not a FIX3 selector')
    require(bytes(b ^ 0xa9 for b in assets['synth_med_fxd_bet3f22.xmf'][8:12]) == b'HMEX', 'not an HMEX synthesizer')
    # Bind the old fixed-point models to matching modules already in the ARM64 runtime.
    data = assets[voice['header']]
    for component in ('select', 'synth'):
        old = ('<COMPONENT>' + component + '/bet3</COMPONENT>').encode()
        new = ('<COMPONENT>' + component + '/bet3fxd</COMPONENT>').encode()
        require(data.count(old) == 1, 'unexpected BET3 component declaration')
        data = data.replace(old, new)
    assets[voice['header']] = data
    return assets


def manifest(voice):
    return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
 package="{voice['package']}" android:versionCode="{VERSION_CODE}" android:versionName="{VERSION}">
 <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="32" />
 <application android:label="iOS-{escape(voice['name'])}" android:allowBackup="false" android:extractNativeLibs="true">
  <activity android:name="com.android.appletts.compat.CheckVoiceDataActivity" android:exported="true">
   <intent-filter><action android:name="android.speech.tts.engine.CHECK_TTS_DATA"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>
  </activity>
  <service android:name="com.android.appletts.compat.ReadyTtsService" android:exported="true" android:label="iOS-{escape(voice['name'])}">
   <intent-filter><action android:name="android.intent.action.TTS_SERVICE"/><category android:name="android.intent.category.DEFAULT"/></intent-filter>
   <meta-data android:name="android.speech.tts" android:resource="@xml/tts_engine"/>
  </service>
 </application>
</manifest>'''


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for arg in ('original-apk', 'runtime-apk', 'sdk', 'jdk', 'work', 'output', 'keystore'):
        p.add_argument('--' + arg, required=True, type=Path)
    p.add_argument('--alias', default='trial')
    p.add_argument('--build-tools', default='36.0.0')
    p.add_argument('--platform', default='android-37.0')
    a = p.parse_args()
    for directory in (a.work, a.output):
        directory.mkdir(parents=True, exist_ok=True)
        require(not directory.resolve().is_relative_to(ROOT), 'generated files must be outside the source tree')
    require(sha(a.original_apk.read_bytes()) == ORIGINAL_SHA, 'original APK SHA-256 mismatch')
    require(sha(a.runtime_apk.read_bytes()) == RUNTIME_APK_SHA, 'runtime APK SHA-256 mismatch')
    require('VOICE_STORE_PASSWORD' in os.environ, 'set VOICE_STORE_PASSWORD for the existing signing key')
    bt = a.sdk / 'build-tools' / a.build_tools
    jar = a.sdk / 'platforms' / a.platform / 'android.jar'
    suffix = '.exe' if os.name == 'nt' else ''
    tool = lambda name: bt / (name + suffix)
    jdk = lambda name: a.jdk / 'bin' / (name + suffix)
    sources = sorted((ROOT / 'src').rglob('*.java'))
    voices = json.loads((ROOT / 'voices.json').read_text(encoding='utf-8'))
    report = {'project': 'iOS 声音引擎', 'version': VERSION, 'version_code': VERSION_CODE,
              'original_apk_sha256': ORIGINAL_SHA, 'runtime_apk_sha256': RUNTIME_APK_SHA,
              'device_audio_test': 'NOT PERFORMED for this release; four added voices are experimental',
              'word_timing_device_test': 'NOT VERIFIED', 'artifacts': []}
    with zipfile.ZipFile(a.original_apk) as original, zipfile.ZipFile(a.runtime_apk) as runtime:
        native = patch_runtime(runtime.read(NATIVE_PATH))
        elf = ElfView(native)
        require(all(s[7] >= 16384 and (s[3] - s[2]) % 16384 == 0 for s in elf.segments if s[0] == 1), 'native ELF is not 16K aligned')
        require(elf.string(elf.pointer(0x75f100)) == 'select/bet3fxd', 'fixed-point selector module missing')
        require(elf.string(elf.pointer(0x75f120)) == 'synth/bet3fxd', 'fixed-point synth module missing')
        legacy = patched_sdk(original.read('classes.dex'))
        for voice in voices:
            work = a.work / voice['id']
            classes, dex, res = work / 'classes', work / 'dex', work / 'res'
            for directory in (classes, dex, res / 'xml'):
                directory.mkdir(parents=True, exist_ok=True)
            profile = work / 'EngineProfile.java'
            profile.write_text('package com.android.appletts.compat; public final class EngineProfile {'
                               f' public static final String SDK_NAME="{voice["sdk_name"]}";'
                               f' public static final String VOICE_ID="{voice["id"]}_zh_CN"; }}', encoding='utf-8')
            (res / 'xml/tts_engine.xml').write_text('<tts-engine xmlns:android="http://schemas.android.com/apk/res/android"/>', encoding='utf-8')
            (work / 'AndroidManifest.xml').write_text(manifest(voice), encoding='utf-8')
            run([jdk('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options',
                 '-classpath', jar, '-d', classes, *sources, profile])
            run([jdk('jar'), 'cf', work / 'code.jar', '-C', classes, '.'])
            run([jdk('java'), '-Xmx512m', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8',
                 '--min-api', '21', '--lib', jar, '--output', dex, work / 'code.jar'])
            run([tool('aapt2'), 'compile', '--dir', res, '-o', work / 'res.zip'])
            run([tool('aapt2'), 'link', '-I', jar, '--manifest', work / 'AndroidManifest.xml',
                 '-o', work / 'shell.apk', work / 'res.zip'])
            assets = voice_assets(original, voice)
            with zipfile.ZipFile(work / 'shell.apk') as shell, zipfile.ZipFile(work / 'unsigned.apk', 'w') as out:
                for item in shell.infolist():
                    out.writestr(item.filename, shell.read(item.filename), compress_type=item.compress_type)
                out.writestr('classes.dex', legacy, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
                out.writestr('classes2.dex', (dex / 'classes.dex').read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
                out.writestr(NATIVE_PATH, native, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
                for name, data in assets.items():
                    out.writestr('assets/vocalizer/' + name, data, compress_type=zipfile.ZIP_STORED)
                if 'assets/emoji/zho.xml' in original.namelist():
                    out.writestr('assets/emoji/zho.xml', original.read('assets/emoji/zho.xml'), compress_type=zipfile.ZIP_DEFLATED)
            output = a.output / f'ios-voice-engine-{voice["id"]}-{VERSION}-arm64.apk'
            run([tool('zipalign'), '-P', '16', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk'])
            run([jdk('java'), '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', a.keystore,
                 '--ks-pass', 'env:VOICE_STORE_PASSWORD', '--ks-key-alias', a.alias, '--out', output, work / 'aligned.apk'])
            run([jdk('java'), '-jar', bt / 'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', output])
            run([tool('zipalign'), '-c', '-P', '16', '4', output])
            with zipfile.ZipFile(output) as built:
                require(built.testzip() is None, 'corrupt APK')
                require(built.read('classes.dex') == legacy, 'SDK changed during packaging')
                require(built.read('classes2.dex') == (dex / 'classes.dex').read_bytes(), 'wrong wrapper DEX')
                require(built.read(NATIVE_PATH) == native, 'wrong native runtime')
                actual = {n.removeprefix('assets/vocalizer/') for n in built.namelist() if n.startswith('assets/vocalizer/')}
                require(actual == set(assets), 'unexpected voice resources')
                for name, data in assets.items():
                    require(built.read('assets/vocalizer/' + name) == data, 'voice asset mismatch')
                    if name.endswith('.xmf'):
                        require(data == original.read('assets/vocalizer/' + name), 'voice model was altered')
                        require(built.getinfo('assets/vocalizer/' + name).compress_type == zipfile.ZIP_STORED, 'voice asset must support FD access')
            report['artifacts'].append({**voice, 'filename': output.name, 'bytes': output.stat().st_size,
                                        'sha256': sha(output.read_bytes()), 'native_sha256': sha(native),
                                        'model_bytes_unchanged': True, 'voice_header_count': 1,
                                        'header_component_fix': 'select/bet3fxd + synth/bet3fxd'})
    (a.output / 'build-evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (a.output / 'SHA256SUMS.txt').write_text(''.join(f'{v["sha256"]}  {v["filename"]}\n' for v in report['artifacts']), encoding='utf-8')
    shutil.copy2(ROOT / 'THIRD_PARTY.md', a.output / 'THIRD_PARTY.md')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
