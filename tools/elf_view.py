"""Minimal read-only ELF64 inspection for this runtime's module registration."""
import struct

class ElfView:
    def __init__(self, data):
        self.data = data
        assert data[:6] == b'\x7fELF\x02\x01'
        phoff = self.unpack('<Q', 32)[0]
        phsize, count = self.unpack('<HH', 54)
        self.segments = [self.unpack('<IIQQQQQQ', phoff + i * phsize) for i in range(count)]
        shoff = self.unpack('<Q', 40)[0]
        shsize, count = self.unpack('<HH', 58)
        sections = [self.unpack('<IIQQQQIIQQ', shoff + i * shsize) for i in range(count)]
        self.symbols = {}
        for section in sections:
            if section[1] != 11:
                continue
            strings = sections[section[6]]
            self.symtab = []
            for p in range(section[4], section[4] + section[5], section[9]):
                name, info, other, index, value, size = self.unpack('<IBBHQQ', p)
                text = self.string_offset(strings[4] + name)
                sym = dict(name=text, value=value, size=size, index=index)
                self.symtab.append(sym)
                if text:
                    self.symbols[text] = sym
        self.relocations = {}
        for section in sections:
            if section[1] != 4:
                continue
            for p in range(section[4], section[4] + section[5], section[9]):
                target, info, addend = self.unpack('<QQq', p)
                typ, index = info & 0xffffffff, info >> 32
                if typ == 1027:
                    value, name = addend, ''
                elif typ in (257, 1025, 1026):
                    sym = self.symtab[index]
                    value, name = sym['value'] + addend, sym['name']
                else:
                    continue
                self.relocations[target] = dict(value=value, name=name, type=typ, file_offset=p)

    def unpack(self, fmt, offset):
        return struct.unpack_from(fmt, self.data, offset)

    def offset(self, va):
        for typ, flags, off, start, _, size, _, align in self.segments:
            if typ == 1 and start <= va < start + size:
                return off + va - start
        raise ValueError(hex(va))

    def pointer(self, va):
        return self.relocations.get(va, {}).get('value', self.unpack('<Q', self.offset(va))[0])

    def string_offset(self, off):
        return self.data[off:self.data.index(b'\0', off)].decode('utf-8', errors='replace')

    def string(self, va):
        return self.string_offset(self.offset(va))

    def describe(self, va):
        names = [name for name, sym in self.symbols.items() if sym['value'] == va and sym['index']]
        if names:
            return '/'.join(names)
        try:
            value = self.string(va)
            if value and all(32 <= ord(ch) < 127 for ch in value[:140]):
                return repr(value[:140])
        except (ValueError, IndexError):
            pass
        return hex(va)
