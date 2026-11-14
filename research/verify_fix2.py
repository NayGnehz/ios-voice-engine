from pathlib import Path
import hashlib
import json
import xml.etree.ElementTree as ET
import zipfile
from elf_view import ElfView

root = Path(__file__).resolve().parent
header = 'assets/vocalizer/mnc/ve_mnc_ting-ting_compact.hdr'
lib = 'lib/arm64-v8a/libNuanceVocalizer.so'
with zipfile.ZipFile(root / 'ios-tingting-arm64-fix1.apk') as before, zipfile.ZipFile(root / 'ios-tingting-arm64-fix2.apk') as after, zipfile.ZipFile(root / 'tingting-diagnostic-fix2.apk') as probe:
    names = {n for n in before.namelist() if not n.startswith('META-INF/')}
    assert names == {n for n in after.namelist() if not n.startswith('META-INF/')}
    changed = sorted(n for n in names if before.read(n) != after.read(n))
    assert changed == ['AndroidManifest.xml', header], changed
    old, new = ET.fromstring(before.read(header)), ET.fromstring(after.read(header))
    old_components = [n.text for n in old.findall('./HEADER/COMPONENTS/COMPONENT')]
    components = [n.text for n in new.findall('./HEADER/COMPONENTS/COMPONENT')]
    assert len(old_components) == len(components)
    assert [(a, b) for a, b in zip(old_components, components) if a != b] == [('select/bet3', 'select/bet3fxd'), ('synth/bet3', 'synth/bet3fxd')]
    # All voice parameters (name, sample rate, gender, model, resources) are unchanged.
    for node in new.findall('./HEADER/COMPONENTS/COMPONENT'):
        node.text = node.text.replace('bet3fxd', 'bet3')
    assert ET.tostring(new) == ET.tostring(old)
    elf = ElfView(after.read(lib))
    bindings = {}
    for slot in (0x75f0c0, 0x75f0e0, 0x75f100, 0x75f120):
        bindings[elf.string(elf.pointer(slot))] = elf.describe(elf.pointer(slot + 8))
    assert bindings['select/bet3fxd'] == 'tts_ve_select_bet3_GetInterface'
    assert bindings['synth/bet3fxd'] == 'tts_ve_synth_bet3_GetInterface'
    for name in names:
        if name.startswith(('assets/', 'lib/')):
            assert after.read(name) == probe.read(name)
    assert before.read('classes.dex') == after.read('classes.dex') == probe.read('classes2.dex')
    assert after.read(lib)[0x134f38:0x134f3c] == bytes.fromhex('1f2003d5')
    report = {
        'change': 'Voice declaration selects existing fixed-point BET3 modules matching FIX3/HMEX data',
        'files_changed_from_fix1_excluding_signature': changed,
        'bet3_component_bindings': bindings,
        'voice_parameters_and_audio_model_bytes_unchanged': True,
        'runtime_and_dex_identical_to_fix1': True,
        'diagnostic_matches_production_assets_and_runtime': True,
        'device_audio_test': 'PENDING',
        'outputs': {n: {'bytes': (root / n).stat().st_size, 'sha256': hashlib.sha256((root / n).read_bytes()).hexdigest()} for n in ['ios-tingting-arm64-fix2.apk', 'tingting-diagnostic-fix2.apk']},
    }
    (root / 'fix2-evidence.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(report, indent=2, ensure_ascii=False))
