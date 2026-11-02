"""Check pinned source, packaged ABI, notices and unchanged SDK native inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VENDOR = ROOT / 'third_party/android-complex-math'
LIBRARY = 'libandroid-complex-math.so'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def readelf(tool, path, flag):
    return subprocess.check_output([str(tool), flag, '--wide', str(path)], text=True)


def exports(tool, path):
    result = set()
    for line in readelf(tool, path, '--dyn-syms').splitlines():
        fields = line.split()
        if len(fields) >= 8 and fields[4] in ('GLOBAL', 'WEAK') and fields[6] != 'UND':
            result.add(fields[7])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk-root', required=True, type=Path)
    parser.add_argument('--ndk', required=True, type=Path)
    parser.add_argument('--apk', required=True, type=Path)
    args = parser.parse_args()
    hosts = list((args.ndk / 'toolchains/llvm/prebuilt').glob('*/bin/llvm-readelf*'))
    tools = [tool for tool in hosts if tool.name in ('llvm-readelf', 'llvm-readelf.exe')]
    if len(tools) != 1:
        raise ValueError('Select an NDK with exactly one host llvm-readelf executable')
    tool = tools[0]
    lock = json.loads((VENDOR / 'source-lock.json').read_text(encoding='utf-8'))
    for entry in lock['files']:
        data = (VENDOR / entry['path']).read_bytes()
        assert sha(data) == entry['sha256'], 'Source drift: ' + entry['path']
        blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        assert blob == entry['gitBlob'], 'Upstream blob drift: ' + entry['path']
    print(f"PASS {len(lock['files'])} vendored inputs match pinned Git blobs and SHA256")

    original_dir = args.sdk_root / 'android/meeting-sdk/src/main/jniLibs/arm64-v8a'
    original = original_dir / LIBRARY
    old_exports = exports(tool, original)
    assert len(old_exports) == 22, 'Reassess changed upstream ABI before replacing it'
    with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory(prefix='clawmate-native-') as temporary:
        names = apk.namelist()
        member = 'lib/arm64-v8a/' + LIBRARY
        assert names.count(member) == 1, 'Exactly one replacement must be packaged'
        data = apk.read(member)
        assert data != original.read_bytes(), 'The legacy prebuilt was packaged'
        packaged = Path(temporary) / LIBRARY
        packaged.write_bytes(data)
        assert exports(tool, packaged) == old_exports, 'Replacement changed the exported ABI'
        print('PASS packaged replacement retains all 22 exports, with no additional exports')
        dynamic = readelf(tool, packaged, '-d')
        assert set(re.findall(r'Shared library: \[([^\]]+)\]', dynamic)) == {'libc.so', 'libm.so'}
        assert 'RPATH' not in dynamic and 'RUNPATH' not in dynamic, 'Host/Termux search path leaked'
        print('PASS only Android libc/libm are needed, without a Termux runpath')

        retained = 0
        for library in original_dir.glob('*.so'):
            if library.name == LIBRARY:
                continue
            member = 'lib/arm64-v8a/' + library.name
            assert names.count(member) == 1 and apk.read(member) == library.read_bytes(), library.name
            retained += 1
        print(f'PASS other {retained} meeting JNI libraries are byte-identical to the SDK inputs')
        notice = apk.read('assets/notices/android-complex-math.txt').decode('utf-8')
        assert (VENDOR / 'LICENSE').read_text(encoding='utf-8').strip() in notice
        for source in VENDOR.glob('upstream-netbsd/lib/libm/complex/*.c'):
            header = source.read_text(encoding='utf-8').split('#include', 1)[0].strip()
            assert header in notice, 'Missing source copyright: ' + source.name
        print('PASS the package contains the license and every per-source copyright notice')
    print(json.dumps({'passed': 5, 'apkSha256': sha(args.apk.read_bytes()),
                      'scope': 'Source/ABI/package integrity; not numerical or 16 KB kernel execution'}))


if __name__ == '__main__':
    main()
