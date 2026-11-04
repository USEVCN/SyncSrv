"""Source-policy and untrusted-state tests. All files are disposable; no real root policy is read."""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'packaging/linux/bin'))
import update_source as source

FEED = 'https://old.example/api/v1/projects/syncsrv/channels/beta/manifest?os=linux&arch=x64'


class SourceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.state = self.root / 'update-source.json'

    def write(self, **changes):
        value = dict(schema=1, status='COMMITTED', baseRevision=hashlib.sha256((FEED+'\n').encode()).hexdigest(),
                     revision='a'*32, serverUrl='https://new.example:18761')
        value.update(changes)
        self.state.write_text(json.dumps(value)); self.state.chmod(0o600)
        return value

    def effective(self, base=FEED, allowed=None):
        return source.effective(base, allowed, self.state, owner=os.getuid())

    def test_committed_https_preference_keeps_channel_project_and_platform(self):
        self.write()
        self.assertEqual('https://new.example:18761/api/v1/projects/syncsrv/channels/beta/manifest?os=linux&arch=x64', self.effective())

    def test_missing_initial_state_retains_legacy_policy(self):
        self.assertEqual(FEED, self.effective())
        http=FEED.replace('https://old.example', 'http://192.168.50.118:18760')
        self.assertEqual(http, self.effective(http, 'http://192.168.50.118:18760'))

    def test_pending_malformed_or_changed_policy_never_falls_back(self):
        for changes in [dict(status='PENDING'), dict(baseRevision='0'*64), dict(revision='wrong'), dict(schema=2)]:
            with self.subTest(changes=changes):
                self.write(**changes)
                with self.assertRaises(ValueError): self.effective()
        self.state.write_text('[]')
        with self.assertRaises(ValueError): self.effective()
        self.state.write_text(' '*16385)
        with self.assertRaises(PermissionError): self.effective()

    def test_state_cannot_widen_http_exception_or_include_credentials(self):
        for url in ['http://192.168.50.119:18760', 'https://user:secret@new.example', 'https://new.example/path', 'https://new.example/?token=secret']:
            with self.subTest(url=url):
                self.write(serverUrl=url, allowHttpOrigin='http://192.168.50.119:18760')
                with self.assertRaises(ValueError): self.effective()

    def test_rejects_final_and_parent_symlinks_including_broken_links(self):
        self.write(); original=self.root/'original.json';self.state.rename(original)
        self.state.symlink_to(original)
        with self.assertRaises(OSError): self.effective()
        self.state.unlink();self.state.symlink_to(self.root/'absent')
        with self.assertRaises(OSError): self.effective()
        self.state.unlink();folder=self.root/'folder';folder.mkdir();(folder/self.state.name).write_text(original.read_text())
        alias=self.root/'alias';alias.symlink_to(folder)
        with self.assertRaises(OSError): source.effective(FEED,None,alias/self.state.name,owner=os.getuid())

    def test_rejects_wrong_owner_and_group_write_permissions(self):
        self.write();self.state.chmod(0o660)
        with self.assertRaises(PermissionError): self.effective()
        self.state.chmod(0o600)
        with patch.object(source.os,'fstat',return_value=SimpleNamespace(st_mode=0o100600,st_size=self.state.stat().st_size,st_uid=987654)):
            with self.assertRaises(PermissionError): self.effective()

    def test_policy_compatibility_is_read_only(self):
        policy=self.root/'policy.json';value=dict(manifest=FEED,serverUrl='https://saved.example',unrelated='keep')
        policy.write_text(json.dumps(value));policy.chmod(0o400);before=policy.read_bytes()
        self.assertEqual('https://saved.example/api/v1/projects/syncsrv/channels/beta/manifest?os=linux&arch=x64',source.policy_manifest(source.read_object(policy,os.getuid())))
        self.assertEqual(before,policy.read_bytes());self.assertEqual(0o400,policy.stat().st_mode&0o777)

    def test_custom_data_directory_is_parsed_without_executing_environment(self):
        env=self.root/'env';env.write_text("SYNCSRV_INITIAL_PASSWORD='test-only-secret'\nSYNCSRV_DATA='/var/lib/custom data'\n");env.chmod(0o600)
        self.assertEqual(Path('/var/lib/custom data/update-source.json'),source.preference_path({},env,owner=os.getuid()))
        env.write_text('SYNCSRV_DATA=$(touch /should-never-exist)\n')
        with self.assertRaises(ValueError): source.preference_path({},env,owner=os.getuid())
        self.assertEqual(self.state,source.preference_path({'sourceState':str(self.state)},env,owner=os.getuid()))
        with self.assertRaises(ValueError):source.preference_path({'sourceState':'relative.json'},env,owner=os.getuid())

    def test_default_policy_and_new_preferences_do_not_change_root_file(self):
        self.assertEqual(source.DEFAULT_FEED,source.policy_manifest({}))
        env=self.root/'absent.env'
        self.assertEqual(Path('/var/lib/syncsrv/update-source.json'),source.preference_path({},env,owner=os.getuid()))
        with self.assertRaises(ValueError): source.manifest_for_server('https://new.example',FEED.replace('/syncsrv/','/other/'))


if __name__=='__main__': unittest.main()
