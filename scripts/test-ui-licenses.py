#!/usr/bin/env python3
import hashlib, importlib.util, json, tempfile, unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('ui_licenses',Path(__file__).with_name('ui-licenses.py'))
u=importlib.util.module_from_spec(spec);spec.loader.exec_module(u)
MIT='Copyright test fixture\nPermission is hereby granted, free of charge.\nThe above copyright notice shall be included.\n'
class UiLicensesTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.addCleanup(self.tmp.cleanup)
  (self.root/'Frontend/public/licenses').mkdir(parents=True);(self.root/'Frontend/src/modules/auth').mkdir(parents=True)
  (self.root/'Frontend/public/licenses/react-bits.txt').write_text('Commons Clause\nCopyright (c) 2026 David Haz')
  for name in u.BITS:(self.root/'Frontend/src/modules/auth'/name).write_text('// LicenseRef-ReactBits-MIT-Commons-Clause')
  self.folder=self.root/'Frontend/node_modules/fixture';self.folder.mkdir(parents=True)
  self.package={'name':'fixture','version':'1.0.0','license':'MIT'};self.lock={'packages':{'node_modules/fixture':{'version':'1.0.0','license':'MIT'}}};self.save()
  (self.folder/'LICENSE').write_text(MIT)
  (self.root/'licenses').mkdir()
  review={'schema':1,'reviewedAt':'2026-11-04','npmPackages':[{'name':'fixture','version':'1.0.0','license':'MIT','licenseSha256':hashlib.sha256(MIT.encode()).hexdigest()}],
    'reactBits':{'files':u.BITS,'licenseUrl':'https://example.invalid/test-only','licenseSha256':hashlib.sha256((self.root/'Frontend/public/licenses/react-bits.txt').read_text().encode()).hexdigest()}}
  (self.root/'licenses/ui-reviewed.json').write_text(json.dumps(review))
 def save(self):
  (self.folder/'package.json').write_text(json.dumps(self.package));(self.root/'Frontend/package-lock.json').write_text(json.dumps(self.lock))
 def test_full_text_and_application_exception_are_kept(self):
  inventory,notice=u.collect(self.root);self.assertIn(MIT,notice);self.assertEqual('MIT',inventory['npmPackages'][0]['license']);self.assertIn('React Bits Pro is not covered.',inventory['applicationComponents'][0]['conditions'])
 def test_windows_line_endings_preserve_full_license_terms(self):
  bits=self.root/'Frontend/public/licenses/react-bits.txt'
  bits.write_bytes(bits.read_text().replace('\n','\r\n').encode())
  (self.folder/'LICENSE').write_bytes(MIT.replace('\n','\r\n').encode())
  inventory,notice=u.collect(self.root)
  self.assertIn(MIT,notice);self.assertEqual('MIT',inventory['npmPackages'][0]['license'])
 def test_unknown_or_changed_license_is_not_auto_approved(self):
  self.package['license']='Commercial';self.save()
  with self.assertRaises(ValueError):u.collect(self.root)
 def test_lock_and_installed_version_must_match(self):
  self.package['version']='2.0.0';self.save()
  with self.assertRaises(ValueError):u.collect(self.root)
 def test_missing_license_text_is_not_treated_as_free_by_metadata(self):
  (self.folder/'LICENSE').unlink()
  with self.assertRaises(ValueError):u.collect(self.root)
 def test_readme_embedded_license_is_collected(self):
  (self.folder/'LICENSE').unlink();(self.folder/'README.md').write_text('Readme\n## License\n'+MIT)
  path=self.root/'licenses/ui-reviewed.json';review=json.loads(path.read_text())
  review['npmPackages'][0]['licenseSha256']=hashlib.sha256(MIT.strip().encode()).hexdigest();path.write_text(json.dumps(review))
  inventory,notice=u.collect(self.root);self.assertIn(MIT.strip(),notice);self.assertIn('README',inventory['npmPackages'][0]['evidence'])
 def test_added_dependency_needs_review_even_if_it_declares_mit(self):
  extra=self.root/'Frontend/node_modules/new-package';extra.mkdir()
  (extra/'package.json').write_text(json.dumps({'name':'new-package','version':'1.0.0','license':'MIT'}));(extra/'LICENSE').write_text(MIT)
  self.lock['packages']['node_modules/new-package']={'version':'1.0.0','license':'MIT'};self.save()
  with self.assertRaisesRegex(ValueError,'needs license review'):u.collect(self.root)
 def test_license_text_change_cannot_be_silently_regenerated(self):
  (self.folder/'LICENSE').write_text(MIT+'Additional restriction: commercial use prohibited.\n')
  with self.assertRaisesRegex(ValueError,'needs license review'):u.collect(self.root)
 def test_react_bits_license_change_requires_review(self):
  path=self.root/'Frontend/public/licenses/react-bits.txt';path.write_text(path.read_text()+'\nAdditional restriction')
  with self.assertRaisesRegex(ValueError,'React Bits license/source scope changed'):u.collect(self.root)
if __name__=='__main__':unittest.main()
