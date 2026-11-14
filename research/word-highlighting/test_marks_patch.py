"""Check the actual patched DEX loop, including null arrays and null mark entries."""
import struct
import zipfile
from pathlib import Path
from patch_marks import patch_marks

root = Path(__file__).resolve().parent
with zipfile.ZipFile(root.parent / 'ios-tingting-arm64-fix2.apk') as apk:
    original = apk.read('classes.dex')
patched = patch_marks(original)
assert original[32:0x11af0] == patched[32:0x11af0]
assert original[0x11b20:] == patched[0x11b20:]
code = struct.unpack_from('<24H', patched, 0x11af0)
for marks in [None, [], [object()], [None, object(), None, object()], [object() for _ in range(255)]]:
    registers = [None, None, object(), marks]
    received, pc, steps = [], 0, 0
    while True:
        steps += 1
        assert steps < 10000
        unit = code[pc]; op, arg = unit & 255, unit >> 8
        if op == 0x38:
            jump = struct.unpack('<h', struct.pack('<H', code[pc + 1]))[0]
            pc += jump if registers[arg] is None else 2
        elif op == 0x12: registers[arg & 15] = arg >> 4; pc += 1
        elif op == 0x21: registers[arg & 15] = len(registers[arg >> 4]); pc += 1
        elif op == 0x35: pc += code[pc + 1] if registers[arg & 15] >= registers[arg >> 4] else 2
        elif op == 0x46:
            indices = code[pc + 1]; registers[arg] = registers[indices & 255][registers[indices >> 8]]; pc += 2
        elif op == 0x70:
            assert code[pc + 1] == 0x113 and code[pc + 2] == 0x12
            received.append(registers[1]); pc += 3
        elif op == 0xd8:
            operands = code[pc + 1]; registers[arg] = registers[operands & 255] + (operands >> 8); pc += 2
        elif op == 0x28: pc += arg - 256 if arg >= 128 else arg
        elif op == 0x0e: break
        else: raise AssertionError((pc, hex(unit)))
    assert received == [m for m in marks or [] if m is not None]
print('PASS: actual DEX dispatch loop, null handling, 255 marks and unchanged surrounding bytecode')
