#!/usr/bin/env python3
"""Run the packaged shell entry with a fake service installer; never install a host service."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
class InteractiveInstall(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='syncsrv-installer-test-')
        self.root = Path(self.temp.name)
        self.package = self.root/'package with spaces'; self.package.mkdir()
        self.mock = self.root/'mockbin'; self.mock.mkdir()
        self.state = self.root/'state'; self.state.mkdir()
        for name in ['install.sh','install-common.sh']:
            shutil.copyfile(ROOT/'Agent/installer'/name,self.package/name)
        for name, code in {
            'id':'#!/bin/sh\nprintf "0\\n"\n',
            'uname':'#!/bin/sh\ncase "$1" in -s) echo Darwin ;; -m) echo "${MOCK_ARCH:-arm64}" ;; esac\n',
        }.items():
            p=self.mock/name; p.write_text(code); p.chmod(0o755)
        (self.package/'package-info').write_text('0.8.0-dev\ndarwin\narm64\n')
        binary=self.package/'syncsrv-agent'
        binary.write_text('#!/bin/sh\necho "0.8.0-dev darwin arm64"\n'); binary.chmod(0o755)
        (self.package/'service-install.sh').write_text('''#!/bin/sh
printf '%s\\n' "$@" >"$TEST_STATE/args"
while [ "$#" -gt 0 ]; do
  if [ "$1" = --token-file ]; then
    printf '%s' "$2" >"$TEST_STATE/token-path"
    cat "$2" >"$TEST_STATE/token-value"
  fi
  shift
done
exit "${FAIL_INSTALL:-0}"
''')
        for name in ['uninstall.sh', 'agent-uninstall.sh', 'agent-control.sh']:
            shutil.copyfile(ROOT/'Agent/tools'/name,self.package/name)
        self.checksums()
    def tearDown(self): self.temp.cleanup()
    def checksums(self):
        (self.package/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n'
            for p in sorted(self.package.iterdir()) if p.name!='SHA256SUMS'))
    def run_installer(self,*args,inputs='',**extra):
        return subprocess.run(['sh',str(self.package/'install.sh'),*args],input=inputs,text=True,capture_output=True,
            env={**os.environ,'PATH':str(self.mock)+os.pathsep+os.environ['PATH'],'TEST_STATE':str(self.state),**extra})
    def inputs(self): return 'https://backup.example.test\nMy files\n/opt/backup app\n/var/lib/backup data\nbackup-user\npairing-test-value\n'
    def test_check_has_no_install_or_pairing_side_effect(self):
        result=self.run_installer('--check'); self.assertEqual(0,result.returncode,result.stderr)
        self.assertFalse((self.state/'args').exists())
    def test_detects_wrong_arch_before_any_side_effect(self):
        result=self.run_installer('--check',MOCK_ARCH='x86_64')
        self.assertNotEqual(0,result.returncode);self.assertIn('amd64',result.stderr)
        self.assertFalse((self.state/'args').exists())
    def test_corrupt_binary_is_rejected(self):
        (self.package/'syncsrv-agent').write_text('corrupt')
        result=self.run_installer('--check');self.assertNotEqual(0,result.returncode)
        self.assertIn('校验失败',result.stderr)
    def test_missing_mandatory_checksum_is_rejected(self):
        p=self.package/'SHA256SUMS';p.write_text('\n'.join(line for line in p.read_text().splitlines() if not line.endswith('  service-install.sh'))+'\n')
        result=self.run_installer('--check');self.assertNotEqual(0,result.returncode)
        self.assertIn('清单缺少',result.stderr)
    def test_missing_uninstall_entry_is_rejected(self):
        (self.package/'uninstall.sh').unlink(); self.checksums()
        result=self.run_installer('--check'); self.assertNotEqual(0,result.returncode)
        self.assertIn('uninstall.sh',result.stderr)
    def test_literal_paths_and_secret_are_passed_without_download(self):
        result=self.run_installer(inputs=self.inputs());self.assertEqual(0,result.returncode,result.stderr)
        args=(self.state/'args').read_text().splitlines()
        self.assertEqual('/opt/backup app',args[args.index('--install-dir')+1])
        self.assertEqual('My files',args[args.index('--name')+1])
        self.assertEqual(str(self.package/'syncsrv-agent'),args[args.index('--binary')+1])
        self.assertEqual('pairing-test-value\n',(self.state/'token-value').read_text())
        self.assertNotIn('pairing-test-value',result.stdout+result.stderr+'\n'.join(args))
        self.assertFalse(Path((self.state/'token-path').read_text()).exists())
    def test_pairing_failure_cleans_only_temporary_token(self):
        result=self.run_installer(inputs=self.inputs(),FAIL_INSTALL='1')
        self.assertNotEqual(0,result.returncode);self.assertIn('保留',result.stderr)
        self.assertFalse(Path((self.state/'token-path').read_text()).exists())
    def test_http_requires_explicit_confirmation(self):
        inputs=self.inputs().replace('https://backup.example.test','http://192.168.50.20:18010').replace('pairing-test-value','n')
        result=self.run_installer(inputs=inputs);self.assertNotEqual(0,result.returncode)
        self.assertFalse((self.state/'args').exists())

if __name__=='__main__': unittest.main()
