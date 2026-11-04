"""Ensure business configuration code is bound without including private build keys."""
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import release_gate


class FingerprintTest(unittest.TestCase):
    def setUp(self):
        self.area = tempfile.TemporaryDirectory()
        self.addCleanup(self.area.cleanup)
        self.root = Path(self.area.name)
        self.binding = patch.object(release_gate, 'ROOT', self.root)
        self.binding.start()
        self.addCleanup(self.binding.stop)
        for name in ['Backend/pom.xml', 'Frontend/package.json',
                     'Frontend/package-lock.json', 'release-version.json']:
            self.write(name, '{}')

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def test_relative_names_and_order_are_portable(self):
        self.write('Input/Z-file', 'one')
        self.write('Input/a-file', 'two')
        expected = hashlib.sha256()
        for name, content in [('Input/Z-file', b'one'), ('Input/a-file', b'two')]:
            expected.update(name.encode() + b'\0' + hashlib.sha256(content).digest())
        paths = release_gate.inputs(['Input'], [])
        self.assertEqual(expected.hexdigest(), release_gate.digest_inputs(paths))

    def test_business_config_is_bound(self):
        name = 'Backend/src/main/java/com/oadig/syncsrv/modules/config/ConfigurationService.java'
        self.write(name, 'before')
        before = release_gate.fingerprint()
        self.write(name, 'changed')
        self.assertNotEqual(before, release_gate.fingerprint())

    def test_frontend_config_code_is_bound(self):
        before = release_gate.fingerprint()
        self.write('Frontend/src/config/index.ts', 'export const value = 1')
        self.assertNotEqual(before, release_gate.fingerprint())

    def test_public_assets_and_build_configs_change_both_identities(self):
        for name in ['Frontend/public/favicon.svg', 'Frontend/vite.config.ts', 'Frontend/tsconfig.json']:
            before = (release_gate.fingerprint(), release_gate.frontend_fingerprint())
            self.write(name, 'changed asset or configuration')
            after = (release_gate.fingerprint(), release_gate.frontend_fingerprint())
            self.assertNotEqual(before[0], after[0])
            self.assertNotEqual(before[1], after[1])

    def test_license_material_changes_release_identity(self):
        before = release_gate.fingerprint()
        self.write('licenses/third-party.txt', 'new license text')
        self.assertNotEqual(before, release_gate.fingerprint())

    def test_symlink_release_input_is_rejected(self):
        source = self.root / 'Frontend/public'
        source.mkdir(parents=True)
        (source / 'secret-link').symlink_to(self.root / 'release-version.json')
        with self.assertRaises(ValueError):
            release_gate.fingerprint()

    def test_private_root_configuration_is_not_read(self):
        before = release_gate.fingerprint()
        self.write('config/release-signing-private.pem', 'test-only private material')
        self.assertEqual(before, release_gate.fingerprint())

    def test_generated_outputs_do_not_change_source_identity(self):
        before = release_gate.fingerprint()
        self.write('Backend/src/main/resources/static/assets/app.js', 'compiled')
        self.write('scripts/__pycache__/module.pyc', 'generated')
        self.write(release_gate.IDENTITY, '{}')
        self.assertEqual(before, release_gate.fingerprint())


if __name__ == '__main__':
    unittest.main()
