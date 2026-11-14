"""Run host regressions without any proprietary APK or SDK input."""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser()
p.add_argument('--jdk', required=True, type=Path)
p.add_argument('--work', required=True, type=Path)
a = p.parse_args()
if a.work.resolve().is_relative_to(ROOT):
    raise ValueError('test work directory must be outside source tree')
a.work.mkdir(parents=True, exist_ok=True)
suffix = '.exe' if os.name == 'nt' else ''
java = a.jdk / 'bin' / ('java' + suffix)
javac = a.jdk / 'bin' / ('javac' + suffix)
sources = [ROOT / 'src/com/android/appletts/compat' / n for n in ['WordRanges.java', 'SpeechRate.java', 'ReadyGate.java']]
sources += sorted((ROOT / 'tests').glob('*.java'))
subprocess.run([str(javac), '-encoding', 'UTF-8', '-d', str(a.work), *map(str, sources)], check=True)
for name in ['SpeechRateCheck', 'WordRangesCheck', 'ReadyGateCheck']:
    subprocess.run([str(java), '-cp', str(a.work), name], check=True)
