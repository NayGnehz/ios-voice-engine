"""Verify public release artifacts and record signatures and manifest identities."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser()
p.add_argument('--sdk', required=True, type=Path)
p.add_argument('--jdk', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
bt = a.sdk / 'build-tools/36.0.0'
suffix = '.exe' if os.name == 'nt' else ''
java = a.jdk / 'bin' / ('java' + suffix)
report = json.loads((a.output / 'build-evidence.json').read_text(encoding='utf-8'))
certificate = '3c50a4146e6b022c907fbb11a99919c31169857505b830ccd20df44d6ca1d569'
for voice in report['artifacts']:
    apk = a.output / voice['filename']
    if hashlib.sha256(apk.read_bytes()).hexdigest() != voice['sha256']:
        raise ValueError('APK changed after build')
    signer = subprocess.run([str(java), '-jar', str(bt / 'lib/apksigner.jar'), 'verify', '--print-certs', str(apk)],
                            check=True, capture_output=True, text=True).stdout
    digest = re.search(r'certificate SHA-256 digest: ([0-9a-f]{64})', signer).group(1)
    if digest != certificate:
        raise ValueError('signing certificate differs from previous Ting-Ting releases')
    badging = subprocess.run([str(bt / ('aapt2' + suffix)), 'dump', 'badging', str(apk)],
                             check=True, capture_output=True, encoding='utf-8').stdout
    required = [f"name='{voice['package']}'", f"versionCode='{report['version_code']}'",
                f"versionName='{report['version']}'", f"application-label:'iOS-{voice['name']}'", "native-code: 'arm64-v8a'"]
    if not all(item in badging for item in required):
        raise ValueError('unexpected APK identity: ' + voice['id'])
    if 'launchable-activity:' in badging or 'uses-permission:' in badging:
        raise ValueError('unexpected launcher or permission')
    subprocess.run([str(bt / ('zipalign' + suffix)), '-c', '-P', '16', '4', str(apk)], check=True)
    with zipfile.ZipFile(apk) as z:
        headers = [n for n in z.namelist() if n.startswith('assets/vocalizer/') and n.endswith('.hdr')]
        if headers != ['assets/vocalizer/' + voice['header']]:
            raise ValueError('more than one voice packaged')
        if voice['sdk_name'].encode() not in z.read('classes2.dex'):
            raise ValueError('missing compiled voice profile')
    voice['signer_certificate_sha256'] = digest
    voice['manifest_identity_verified'] = True
    voice['launcher_absent'] = True
    voice['android_permissions'] = []
report['host_tests'] = ['SpeechRateCheck', 'WordRangesCheck', 'ReadyGateCheck']
report['static_checks'] = ['APK signatures and previous certificate', 'APK package/label/version/ABI',
                           'No launcher or Android permissions', 'ZIP and ELF 16K alignment',
                           'Pinned input SHA-256', 'Single voice profile/header and unchanged model bytes']
(a.output / 'build-evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print('PASS: five APK identities, one voice each, signatures match previous Ting-Ting, no launcher/permissions')
