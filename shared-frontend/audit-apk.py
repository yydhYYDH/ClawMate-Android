"""Static APK size/ELF audit. Passing is necessary, not 16 KB device certification."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile


def load_segments(data):
    if data[:4] != b'\x7fELF' or len(data) < 64 or data[5] != 1:
        raise ValueError('Expected a little-endian ELF binary')
    if data[4] == 2:
        offset = struct.unpack_from('<Q', data, 32)[0]
        stride, count = struct.unpack_from('<HH', data, 54)
        fmt = '<IIQQQQQQ'
        positions = (2, 3, 7)
    elif data[4] == 1:
        offset = struct.unpack_from('<I', data, 28)[0]
        stride, count = struct.unpack_from('<HH', data, 42)
        fmt = '<IIIIIIII'
        positions = (1, 2, 7)
    else:
        raise ValueError('Unsupported ELF class')
    if stride < struct.calcsize(fmt) or offset + stride * count > len(data):
        raise ValueError('Truncated ELF program headers')
    segments = []
    for index in range(count):
        header = struct.unpack_from(fmt, data, offset + stride * index)
        if header[0] != 1:  # PT_LOAD
            continue
        file_offset, address, alignment = (header[position] for position in positions)
        segments.append({'offset': file_offset, 'vaddr': address, 'alignment': alignment,
                         'compatible16kb': alignment >= 16384 and alignment & (alignment - 1) == 0
                         and (address - file_offset) % 16384 == 0})
    if not segments:
        raise ValueError('ELF has no loadable segments')
    return segments


def audit(apk, runtime_root=None):
    arkui_files = {(file.name, hashlib.sha256(file.read_bytes()).hexdigest())
                   for file in runtime_root.rglob('*.so')} if runtime_root else set()
    native = []
    groups = {}
    def add_native(name, data, compression, python=False):
        checksum = hashlib.sha256(data).hexdigest()
        segments = load_segments(data)
        owner = 'arkui-x' if (Path(name).name, checksum) in arkui_files else 'python-embedded' if python else 'other'
        native.append({'path': name, 'owner': owner, 'sha256': checksum, 'bytes': len(data),
                       'compression': compression, 'segments': segments,
                       'compatible16kb': all(segment['compatible16kb'] for segment in segments)})
    with zipfile.ZipFile(apk) as package:
        entries = package.infolist()
        for entry in entries:
            name = entry.filename
            group = 'native' if name.startswith('lib/') else 'arkui-assets' if name.startswith('assets/arkui-x/') else \
                'python-assets' if name.startswith('assets/chaquopy/') else 'dex' if name.endswith('.dex') else 'other'
            sizes = groups.setdefault(group, {'compressedBytes': 0, 'uncompressedBytes': 0})
            sizes['compressedBytes'] += entry.compress_size
            sizes['uncompressedBytes'] += entry.file_size
            if name.startswith('lib/') and name.endswith('.so'):
                add_native(name, package.read(entry), entry.compress_type)
            # Chaquopy extracts Python extensions from nested ZIPs at runtime.
            # Auditing lib/ alone misses these native binaries.
            if name.startswith('assets/chaquopy/') and name.endswith('.imy'):
                payload = io.BytesIO(package.read(entry))
                if zipfile.is_zipfile(payload):
                    with zipfile.ZipFile(payload) as embedded:
                        for module in embedded.infolist():
                            if module.filename.endswith('.so'):
                                add_native(name + '!' + module.filename, embedded.read(module), module.compress_type, python=True)
        largest = [{'path': entry.filename, 'compressedBytes': entry.compress_size, 'uncompressedBytes': entry.file_size}
                   for entry in sorted(entries, key=lambda entry: entry.compress_size, reverse=True)[:12]]
    failures = [item['path'] for item in native if not item['compatible16kb']]
    return {'v': 1, 'apk': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'apkBytes': apk.stat().st_size,
            'groups': groups, 'nativeLibraries': len(native), 'elf16kbFailures': failures,
            'arkui16kbFailures': [item['path'] for item in native if item['owner'] == 'arkui-x' and not item['compatible16kb']],
            'largestEntries': largest, 'native': native,
            'nativeByOwner': {owner: {'count': sum(item['owner'] == owner for item in native),
                                     'uncompressedBytes': sum(item['bytes'] for item in native if item['owner'] == owner)}
                              for owner in sorted({item['owner'] for item in native})},
            'scope': 'Static ELF PT_LOAD audit including Chaquopy embedded extensions; ZIP alignment and real 16 KB execution must be verified separately'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--runtime-root', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--require-16kb', action='store_true')
    args = parser.parse_args()
    report = audit(args.apk, args.runtime_root)
    if args.output:
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({key: value for key, value in report.items() if key not in ('native', 'largestEntries')}, ensure_ascii=False))
    raise SystemExit(1 if args.require_16kb and report['elf16kbFailures'] else 0)
