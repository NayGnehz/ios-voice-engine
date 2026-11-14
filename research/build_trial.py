"""Build an ARM64 compatibility experiment without running the APK.

This is a runtime replacement experiment, not a port of the proprietary engine.
Voice assets are copied byte-for-byte from the user's original APK.
"""
from pathlib import Path
import hashlib
import json
import struct
import zipfile
import zlib

ROOT = Path(__file__).resolve().parent
ORIGINAL = ROOT.parent / "苹果中文语音库_clone.apk"
CANDIDATE = ROOT / "SmartVoice-4.1.16.apk"
OUTPUT = ROOT / "tingting-arm64-trial-unsigned.apk"


def replace_equal_size(data, old, new, expected=1):
    assert len(old) == len(new), (len(old), len(new))
    count = data.count(old)
    assert count == expected, (old, count, expected)
    return data.replace(old, new)


def replace_pool_text(data, old, new):
    assert len(old) == len(new)
    encodings = [e for e in ("utf-8", "utf-16le") if old.encode(e) in data]
    assert len(encodings) == 1, encodings
    enc = encodings[0]
    return replace_equal_size(data, old.encode(enc), new.encode(enc))


with zipfile.ZipFile(ORIGINAL) as original, zipfile.ZipFile(CANDIDATE) as candidate:
    manifest = original.read("AndroidManifest.xml")
    manifest = replace_pool_text(manifest, "com.android.appletttext", "com.android.apple64ttsx")
    # This label is also a literal on the service node.
    manifest = replace_pool_text(manifest, "苹果中文语音库", "婷婷语音测试版")
    resources = replace_pool_text(original.read("resources.arsc"), "苹果中文语音库", "婷婷语音测试版")

    dex = bytearray(original.read("classes.dex"))
    # The old optional utility is ARM32-only. Do not load it in the ARM64 process.
    # Remove the exact call to FDSANUtil.disableFDSAN; leave system checks enabled.
    assert dex[0xEBC4:0xEBCA] == bytes.fromhex("7100b1000000")
    dex[0xEBC4:0xEBCA] = b"\x00" * 6
    dex[12:32] = hashlib.sha1(dex[32:]).digest()
    struct.pack_into("<I", dex, 8, zlib.adler32(dex[12:]) & 0xFFFFFFFF)

    runtime = candidate.read("lib/arm64-v8a/libNuanceVocalizer.so")
    assert runtime[:5] == b"\x7fELF\x02"
    assert struct.unpack_from("<H", runtime, 18)[0] == 183

    voice_hashes = {}
    with zipfile.ZipFile(OUTPUT, "w") as output:
        for entry in original.infolist():
            if entry.filename.startswith(("META-INF/", "lib/")):
                continue
            data = original.read(entry.filename)
            if entry.filename == "AndroidManifest.xml":
                data = manifest
            elif entry.filename == "resources.arsc":
                data = resources
            elif entry.filename == "classes.dex":
                data = bytes(dex)
            if entry.filename.startswith("assets/vocalizer/"):
                voice_hashes[entry.filename] = hashlib.sha256(data).hexdigest()
            output.writestr(entry, data)
        output.writestr("lib/arm64-v8a/libNuanceVocalizer.so", runtime, compress_type=zipfile.ZIP_STORED)

    with zipfile.ZipFile(OUTPUT) as output:
        for name, digest in voice_hashes.items():
            assert hashlib.sha256(output.read(name)).hexdigest() == digest
        assert not any(n.startswith("lib/armeabi-v7a/") for n in output.namelist())

    evidence = {
        "original_sha256": hashlib.sha256(ORIGINAL.read_bytes()).hexdigest(),
        "runtime_source": "https://github.com/poretsky/poretsky.github.io/releases/download/android/SmartVoice-4.1.16.apk",
        "candidate_apk_sha256": hashlib.sha256(CANDIDATE.read_bytes()).hexdigest(),
        "arm64_runtime_sha256": hashlib.sha256(runtime).hexdigest(),
        "package": "com.android.apple64ttsx",
        "label": "婷婷语音测试版",
        "voice_assets": voice_hashes,
        "voice_assets_unchanged": True,
        "native_architectures": ["arm64-v8a"],
        "dex_patch": "Removed ARM32-only FDSAN utility invocation at 0xEBC4; repaired DEX hashes",
        "target_sdk_unchanged": 32,
        "runtime_audio_test": "NOT PERFORMED: old voice compatibility is unverified",
    }
    (ROOT / "trial-evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"output": str(OUTPUT), "voice_assets_preserved": len(voice_hashes)}, ensure_ascii=False))
