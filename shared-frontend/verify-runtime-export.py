"""Filesystem/ZIP fixtures for runtime replacement; not native execution evidence."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

repo = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('runtime_export', repo / 'experiments/arkui-x/package-android.py')
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)


class ExportTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp(prefix='clawmate-export-')).resolve()
        self.scratch = self.root / '.arkui-x-validation'
        self.project = self.scratch / 'application/project'
        self.app = self.scratch / 'application/runtime'
        self.sdk = self.root / 'sdk'
        self.hap = self.project / 'entry/build/default/outputs/default/entry-default-unsigned.hap'
        self.hap.parent.mkdir(parents=True)
        for folder in ['engine/lib/android-arm64-release', 'plugins/api/lib', 'engine/systemres']:
            (self.sdk / folder).mkdir(parents=True)
        (self.sdk / 'arkui-x.json').write_text(json.dumps({'version': 'fixture'}))
        (self.sdk / 'engine/lib/android-arm64-release/libtest.so').write_bytes(b'fixture library')
        (self.sdk / 'engine/lib/test.jar').write_bytes(b'fixture jar')
        (self.sdk / 'engine/systemres/resource.txt').write_bytes(b'fixture system resource')
        self.archive({'current.txt': b'current'})

    def tearDown(self):
        assert self.root.is_relative_to(Path(tempfile.gettempdir()).resolve())
        shutil.rmtree(self.root)

    def archive(self, files):
        with zipfile.ZipFile(self.hap, 'w') as archive:
            for name, value in files.items():
                archive.writestr(name, value)

    def export(self):
        return exporter.export_runtime(self.project, self.sdk, self.app)

    def test_replacement_removes_stale_assets_and_libraries_and_accepts_sdk_updates(self):
        self.export()
        assets = self.app / 'src/main/assets/arkui-x'
        (assets / 'entry/stale-secret-config.json').write_text('synthetic obsolete state')
        (self.app / 'libs/stale.jar').write_text('obsolete')
        (self.sdk / 'engine/lib/android-arm64-release/libtest.so').write_bytes(b'updated fixture')
        self.archive({'next.txt': b'next'})
        self.assertEqual(self.export()['exportedLibraries'], 2)
        self.assertEqual((assets / 'entry/next.txt').read_bytes(), b'next')
        self.assertFalse((assets / 'entry/current.txt').exists())
        self.assertFalse((assets / 'entry/stale-secret-config.json').exists())
        self.assertFalse((self.app / 'libs/stale.jar').exists())
        self.assertEqual((self.app / 'libs/arm64-v8a/libtest.so').read_bytes(), b'updated fixture')

    def test_invalid_hap_paths_preserve_existing_runtime(self):
        self.export()
        for name in ['../../outside.txt', '/outside.txt', '..\\outside.txt', 'file:stream']:
            self.archive({name: b'bad'})
            with self.assertRaises(ValueError):
                self.export()
            self.assertEqual((self.app / 'src/main/assets/arkui-x/entry/current.txt').read_bytes(), b'current')
            self.assertFalse((self.scratch / 'outside.txt').exists())

    def test_conflicting_sdk_names_preserve_old_libraries(self):
        self.export()
        conflict = self.sdk / 'plugins/api/lib/android-arm64-release'
        conflict.mkdir()
        (conflict / 'libtest.so').write_bytes(b'conflicting')
        with self.assertRaisesRegex(ValueError, 'conflicting'):
            self.export()
        self.assertEqual((self.app / 'libs/arm64-v8a/libtest.so').read_bytes(), b'fixture library')

    def test_output_outside_workspace_or_overlapping_sources_is_rejected(self):
        for target in [self.root / 'outside', self.scratch, self.project, self.sdk]:
            with self.assertRaises(ValueError):
                exporter.export_runtime(self.project, self.sdk, target)
        self.assertFalse((self.root / 'outside').exists())

    def test_linked_owned_destination_cannot_delete_or_write_external_files(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'keep.txt').write_text('keep')
        link = self.app / 'libs'
        link.parent.mkdir(parents=True)
        if os.name == 'nt':
            subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(outside)], capture_output=True, check=True)
        else:
            link.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.export()
        self.assertEqual((outside / 'keep.txt').read_text(), 'keep')

    def test_missing_sdk_component_is_rejected_before_replacement(self):
        self.export()
        directory = self.sdk / 'plugins/api/lib'
        assert directory.resolve().is_relative_to(self.root)
        shutil.rmtree(directory)
        with self.assertRaisesRegex(ValueError, 'missing'):
            self.export()
        self.assertTrue((self.app / 'src/main/assets/arkui-x/entry/current.txt').is_file())

    def test_linked_asset_parent_is_rejected_before_creating_descendants(self):
        outside = self.root / 'outside'
        outside.mkdir()
        self.app.mkdir(parents=True)
        link = self.app / 'src'
        if os.name == 'nt':
            subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(outside)], capture_output=True, check=True)
        else:
            link.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.export()
        self.assertEqual(list(outside.iterdir()), [])

    def test_original_probe_default_output_remains_supported(self):
        target = self.project / '.arkui-x/android/app'
        exporter.export_runtime(self.project, self.sdk, target)
        self.assertEqual((target / 'src/main/assets/arkui-x/entry/current.txt').read_bytes(), b'current')


if __name__ == '__main__':
    unittest.main(verbosity=2)
