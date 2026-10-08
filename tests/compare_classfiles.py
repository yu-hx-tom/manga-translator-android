"""Compare every class byte except Code.LineNumberTable; fail closed on all other changes."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess


def u2(n):
    return struct.pack('>H', n)


def u4(n):
    return struct.pack('>I', n)


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, n):
        result = self.data[self.pos:self.pos + n]
        if len(result) != n:
            raise ValueError('Truncated class file')
        self.pos += n
        return result

    def number(self, n):
        return int.from_bytes(self.take(n), 'big')


def without_line_numbers(data):
    r = Reader(data)
    header = r.take(8)
    if header[:4] != b'\xca\xfe\xba\xbe':
        raise ValueError('Not a class file')
    count = r.number(2)
    utf8 = {}
    i = 1
    while i < count:
        tag = r.number(1)
        if tag == 1:
            utf8[i] = r.take(r.number(2))
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            r.take(4)
        elif tag in (5, 6):
            r.take(8)
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            r.take(2)
        elif tag == 15:
            r.take(3)
        else:
            raise ValueError(f'Unknown constant pool tag {tag}')
        i += 1
    result = data[:r.pos]  # Preserve the entire constant pool, including order.

    def attributes(reader, inside_code=False):
        rows = []
        for _ in range(reader.number(2)):
            name = reader.number(2)
            body = reader.take(reader.number(4))
            label = utf8.get(name)
            if inside_code and label == b'LineNumberTable':
                continue
            if label == b'Code':
                code = Reader(body)
                prefix = code.take(4)
                instructions = code.take(code.number(4))
                exceptions = code.take(8 * code.number(2))
                body = prefix + u4(len(instructions)) + instructions
                body += u2(len(exceptions) // 8) + exceptions + attributes(code, True)
                if code.pos != len(code.data):
                    raise ValueError('Code attribute trailing bytes')
            rows.append(u2(name) + u4(len(body)) + body)
        return u2(len(rows)) + b''.join(rows)

    result += r.take(6)  # Access flags, this_class, super_class.
    interfaces = r.take(2 * r.number(2))
    result += u2(len(interfaces) // 2) + interfaces
    for _ in range(2):  # fields and methods
        members = r.number(2)
        result += u2(members)
        for _ in range(members):
            result += r.take(6) + attributes(r)
    result += attributes(r)
    if r.pos != len(data):
        raise ValueError('Class trailing bytes')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('before', type=Path)
    p.add_argument('after', type=Path)
    p.add_argument('report', type=Path)
    p.add_argument('--javap', type=Path, required=True)
    a = p.parse_args()
    old = {p.relative_to(a.before).as_posix(): p for p in a.before.rglob('*.class')}
    new = {p.relative_to(a.after).as_posix(): p for p in a.after.rglob('*.class')}
    if not old or not new:
        raise ValueError('Empty class snapshot')
    mismatches, hashes = [], {}
    for name in sorted(old.keys() & new.keys()):
        first = without_line_numbers(old[name].read_bytes())
        second = without_line_numbers(new[name].read_bytes())
        if first != second:
            mismatches.append(name)
        hashes[name] = {'before': hashlib.sha256(first).hexdigest(), 'after': hashlib.sha256(second).hexdigest()}
    # Disassemble batches in the same order. Do not suppress errors or skip classes.
    disassembly = []
    names = sorted(old.keys() & new.keys())
    for files in (old, new):
        rows = []
        for start in range(0, len(names), 20):
            command = [str(a.javap), '-J-Dfile.encoding=UTF-8', '-c', '-p', '-constants']
            command += [str(files[n]) for n in names[start:start + 20]]
            r = subprocess.run(command, capture_output=True, check=True)
            rows.append(r.stdout)
        disassembly.append(b''.join(rows))
    a.report.mkdir(parents=True, exist_ok=True)
    result = {'beforeClasses': len(old), 'afterClasses': len(new), 'added': sorted(new.keys() - old.keys()),
              'removed': sorted(old.keys() - new.keys()), 'different': mismatches,
              'identicalClasses': len(hashes) - len(mismatches),
              'javapIdentical': disassembly[0] == disassembly[1], 'ignoredAttributes': ['Code.LineNumberTable'],
              'preserved': 'All remaining bytes including constant pool, annotations, access flags, bootstrap methods, exception tables and local-variable metadata', 'classHashes': hashes}
    result['passed'] = not (result['added'] or result['removed'] or mismatches) and result['javapIdentical']
    (a.report / '字节码比对.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    summary = {k: v for k, v in result.items() if k != 'classHashes'}
    (a.report / '字节码比对.txt').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    for side, content in zip(['before', 'after'], disassembly):
        (a.report / (side + '-javap.txt')).write_bytes(content)
    print(json.dumps(summary, ensure_ascii=True))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
