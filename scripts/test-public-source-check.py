import importlib.util,unittest
from pathlib import Path
s=importlib.util.spec_from_file_location('scan',Path(__file__).with_name('public-source-check.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class PublicTreeTest(unittest.TestCase):
 def test_runtime_keys_and_binaries_are_never_source(self):
  for path in ('data/metadata.mv.db','config/publisher.key','Agent/bin/agent','Backend/target/app.jar','Frontend/node_modules/a/index.js'):
   self.assertTrue(m.scan(path,b'fixture'),path)
 def test_tokens_and_private_key_headers_are_blocked(self):
  self.assertTrue(m.scan('source.txt',('ghp_'+'a'*36).encode()))
  self.assertTrue(m.scan('source.txt',('-----BEGIN'+' PRIVATE KEY-----').encode()))
 def test_invalid_fixture_allowance_cannot_hide_an_actual_key(self):
  path='Frontend/src/test/settings-drawer.test.tsx'
  fake=('-----BEGIN'+' PRIVATE KEY-----\\nsecret-fixture\\n-----END PRIVATE KEY-----').encode()
  self.assertFalse(m.scan(path,fake))
  actual=('-----BEGIN'+' PRIVATE KEY-----\\n'+'A'*128+'\\n-----END PRIVATE KEY-----').encode()
  self.assertTrue(m.scan(path,actual))
 def test_public_config_template_and_source_are_allowed(self):
  self.assertFalse(m.scan('.env.example',b'SYNCSRV_INITIAL_PASSWORD=\n'))
  self.assertFalse(m.scan('NativeIO/main.go',b'package main\n'))
if __name__=='__main__':unittest.main()
