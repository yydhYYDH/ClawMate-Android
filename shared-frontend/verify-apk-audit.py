import importlib.util
from pathlib import Path
import struct
import io
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('apk_audit', Path(__file__).with_name('audit-apk.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def elf(alignment=16384, address=0, offset=0):
    data = bytearray(120)
    data[:6] = b'\x7fELF\x02\x01'
    struct.pack_into('<Q', data, 32, 64)
    struct.pack_into('<HH', data, 54, 56, 1)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, offset, address, 0, 0, 0, alignment)
    return data


class ElfAuditTest(unittest.TestCase):
    def test_load_alignment_and_congruence(self):
        self.assertTrue(audit.load_segments(elf())[0]['compatible16kb'])
        self.assertTrue(audit.load_segments(elf(65536))[0]['compatible16kb'])
        self.assertFalse(audit.load_segments(elf(4096))[0]['compatible16kb'])
        self.assertFalse(audit.load_segments(elf(address=4096))[0]['compatible16kb'])
        self.assertFalse(audit.load_segments(elf(24576))[0]['compatible16kb'])

    def test_corrupt_headers_fail_instead_of_reporting_compatible(self):
        for data in [b'not-elf', elf()[:80], elf()[:64]]:
            with self.assertRaises(ValueError):
                audit.load_segments(data)

    def test_all_load_segments_are_checked(self):
        data = elf() + bytearray(56)
        struct.pack_into('<H', data, 56, 2)
        struct.pack_into('<IIQQQQQQ', data, 120, 1, 5, 0, 0, 0, 0, 0, 4096)
        self.assertEqual([True, False], [item['compatible16kb'] for item in audit.load_segments(data)])

    def test_python_extensions_inside_nested_archives_are_audited(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, 'w') as package:
            package.writestr('numpy/module.so', elf(4096))
        with tempfile.TemporaryDirectory(prefix='clawmate-apk-') as directory:
            apk = Path(directory) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as package:
                package.writestr('lib/arm64-v8a/libgood.so', elf())
                package.writestr('assets/chaquopy/requirements-common.imy', nested.getvalue())
            report = audit.audit(apk)
            self.assertEqual(2, report['nativeLibraries'])
            self.assertEqual(['assets/chaquopy/requirements-common.imy!numpy/module.so'], report['elf16kbFailures'])


if __name__ == '__main__':
    unittest.main()
