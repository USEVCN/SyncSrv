"""Simulate services in a temporary sandbox; never control a host service."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parent.parent
MOCK='''#!/usr/bin/env python3
import os,sys
from pathlib import Path
p=Path(os.environ['AGENT_TEST_ROOT']);name=Path(sys.argv[0]).name;args=sys.argv[1:]
if name=='id': print(0);sys.exit(0)
if name=='sleep': sys.exit(0)
if name=='uname': print('Linux' if args==['-s'] else 'x86_64');sys.exit(0)
if name=='systemctl':
 with (p/'actions').open('a') as f:f.write(' '.join(args)+'\\n')
 unit=p/'systemd'/((args[1] if len(args)>1 else '') or 'SyncSrvAgent.service')
 if args[0]=='cat':
  if not unit.is_file():sys.exit(1)
  print(unit.read_text());sys.exit(0)
 if args[0]=='show':
  prop=next(x for x in args if x.startswith('--property=')).split('=',1)[1]
  value={'LoadState':'loaded' if unit.exists() else 'not-found','FragmentPath':str(unit),
   'ActiveState':'active' if (p/'active').exists() else 'inactive','User':'syncsrv'}[prop]
  print(value);sys.exit(0)
 if args[0]=='is-active':sys.exit(0 if (p/'active').exists() else 3)
 if args[0]=='stop':
  if os.environ.get('FAIL_STOP'):sys.exit(1)
  (p/'active').unlink(missing_ok=True);sys.exit(0)
 if args[0]=='disable':
  if os.environ.get('FAIL_DISABLE'):sys.exit(1)
  (p/'enabled').unlink(missing_ok=True);sys.exit(0)
 if args[0]=='daemon-reload':sys.exit(0)
 if args[0]=='start':
  if os.environ.get('FAIL_START') and 'NEW_BINARY' in (p/'installed/syncsrv-agent').read_text():sys.exit(1)
  (p/'active').write_text('1');sys.exit(0)
 sys.exit(1)
if name=='launchctl':
 with (p/'actions').open('a') as f:f.write(' '.join(args)+'\\n')
 if args[0]=='print':sys.exit(0 if (p/'active').exists() else 1)
 if args[0]=='bootout':
  if os.environ.get('FAIL_STOP'):sys.exit(1)
  (p/'active').unlink(missing_ok=True);sys.exit(0)
 sys.exit(1)
if name=='PlistBuddy':
 print(str(p/('installed/syncsrv-agent' if args[1].endswith(':0') else 'data')));sys.exit(0)

'''
PROGRAM='''#!/usr/bin/env python3
# MARKER
import os,sys,shutil
from pathlib import Path
p=Path(os.environ['AGENT_TEST_ROOT']);args=sys.argv[1:]
if args[0]=='version':print('VERSION linux amd64')
elif args[0]=='prepare-update':
 if os.environ.get('FAIL_DOWNLOAD'):sys.exit(1)
 if os.environ.get('NO_UPDATE'):print('UNCHANGED');sys.exit(0)
 target=Path(args[args.index('--output-dir')+1])/'syncsrv-agent'
 shutil.copyfile(p/'package/syncsrv-agent',target);target.chmod(0o755);print('0.7.0-dev')
elif args[0] in ('install-tools','health-check'):pass
elif args[0]=='upgrade-transaction':
 if '--action' in args and args[args.index('--action')+1]=='inspect':print('CLEAN')
else:sys.exit(1)
'''
class ManagementTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(prefix='syncsrv-management-');self.root=Path(self.tmp.name)
  for name in ['installed','data','package','mockbin','systemd','launchd']:(self.root/name).mkdir()
  for name in ['agent-control.sh','agent-uninstall.sh','uninstall.sh','update.sh','start.sh','stop.sh','restart.sh']:
   shutil.copyfile(ROOT/'Agent/tools'/name,self.root/'package'/name)
  for name in ['id','sleep','uname','systemctl','launchctl','PlistBuddy']:
   path=self.root/'mockbin'/name;path.write_text(MOCK);path.chmod(0o755)
  for folder,marker,version in [('installed','OLD_BINARY','0.6.0-dev'),('package','NEW_BINARY','0.7.0-dev')]:
   path=self.root/folder/'syncsrv-agent';path.write_text(PROGRAM.replace('MARKER',marker).replace('VERSION',version));path.chmod(0o755)
  (self.root/'package/agent-control.conf').write_text(f'{self.root}/installed\n{self.root}/data\nsystemd\nsyncsrv\n')
  helper=self.root/'package/agent-uninstall.sh'
  helper.write_text(helper.read_text().replace('/etc/systemd/system',str(self.root/'systemd'))
   .replace('/Library/LaunchDaemons',str(self.root/'launchd')).replace('/usr/libexec/PlistBuddy',str(self.root/'mockbin/PlistBuddy')))
  self.unit=self.root/'systemd/SyncSrvAgent.service'
  self.unit.write_text(f'ExecStart="{self.root}/installed/syncsrv-agent" run --data-dir "{self.root}/data"\n')
  (self.root/'enabled').touch()
  for name in ['identity.json','events.db']:(self.root/'data'/name).write_text('preserve')
  self.env={**os.environ,'AGENT_TEST_ROOT':str(self.root),'PATH':str(self.root/'mockbin')+os.pathsep+os.environ['PATH']}
 def tearDown(self):self.tmp.cleanup()
 def run_action(self,action='update',**extra):
  result=subprocess.run(['sh',str(self.root/'package'/(action+'.sh'))],env={**self.env,**extra},capture_output=True,text=True)
  for name in ['identity.json','events.db']:self.assertEqual('preserve',(self.root/'data'/name).read_text())
  return result
 def test_running_service_upgrade(self):
  (self.root/'active').touch();r=self.run_action();self.assertEqual(0,r.returncode,r.stderr)
  self.assertIn('NEW_BINARY',(self.root/'installed/syncsrv-agent').read_text());self.assertTrue((self.root/'active').exists())
  self.assertTrue(list((self.root/'installed').glob('syncsrv-agent.backup-*')))
 def test_failed_download_does_not_stop_service(self):
  (self.root/'active').touch();r=self.run_action(FAIL_DOWNLOAD='1');self.assertNotEqual(0,r.returncode)
  self.assertTrue((self.root/'active').exists());self.assertNotIn('stop',(self.root/'actions').read_text())
  self.assertIn('OLD_BINARY',(self.root/'installed/syncsrv-agent').read_text())
 def test_start_failure_rolls_back_and_restarts_previous(self):
  (self.root/'active').touch();r=self.run_action(FAIL_START='1');self.assertNotEqual(0,r.returncode)
  self.assertIn('OLD_BINARY',(self.root/'installed/syncsrv-agent').read_text());self.assertTrue((self.root/'active').exists())
  self.assertIn('ROLLED_BACK',(self.root/'installed/upgrade.log').read_text())
 def test_stopped_service_stays_stopped(self):
  r=self.run_action();self.assertEqual(0,r.returncode,r.stderr);self.assertFalse((self.root/'active').exists())
 def test_no_newer_version_does_not_touch_binary(self):
  r=self.run_action(NO_UPDATE='1');self.assertEqual(0,r.returncode,r.stderr)
  self.assertIn('OLD_BINARY',(self.root/'installed/syncsrv-agent').read_text());self.assertFalse(list((self.root/'installed').glob('syncsrv-agent.backup-*')))
 def test_start_stop_restart_wrappers(self):
  for action,running in [('start',True),('stop',False),('restart',True)]:
   r=self.run_action(action);self.assertEqual(0,r.returncode,r.stderr);self.assertEqual(running,(self.root/'active').exists())
 def test_lock_rejects_concurrent_management(self):
  (self.root/'installed/.management-lock').mkdir();r=self.run_action();self.assertNotEqual(0,r.returncode)
  self.assertFalse((self.root/'actions').exists())

 def test_uninstall_stops_disables_and_removes_only_own_unit(self):
  (self.root/'active').touch()
  r=self.run_action('uninstall');self.assertEqual(0,r.returncode,r.stderr)
  self.assertFalse((self.root/'active').exists());self.assertFalse(self.unit.exists())
  self.assertFalse((self.root/'enabled').exists());self.assertTrue((self.root/'installed/syncsrv-agent').exists())
  self.assertIn('SERVICE_UNINSTALLED',(self.root/'installed/upgrade.log').read_text())
  r=self.run_action('uninstall');self.assertEqual(0,r.returncode,r.stderr)
 def test_uninstall_stop_or_disable_failure_retains_unit(self):
  for fail in ['FAIL_STOP','FAIL_DISABLE']:
   with self.subTest(fail=fail):
    (self.root/'active').touch()
    r=self.run_action('uninstall',**{fail:'1'});self.assertNotEqual(0,r.returncode)
    self.assertTrue(self.unit.exists())
 def test_uninstall_refuses_foreign_unit_before_stopping_it(self):
  (self.root/'active').touch();self.unit.write_text('ExecStart="/other/app" run --data-dir "/other/data"')
  r=self.run_action('uninstall');self.assertNotEqual(0,r.returncode)
  self.assertTrue(self.unit.exists());self.assertTrue((self.root/'active').exists())
 def test_uninstall_refuses_symlinked_unit(self):
  foreign=self.root/'foreign';foreign.write_text(self.unit.read_text());self.unit.unlink();self.unit.symlink_to(foreign)
  r=self.run_action('uninstall');self.assertNotEqual(0,r.returncode)
  self.assertTrue(foreign.exists());self.assertTrue(self.unit.is_symlink())
 def test_uninstall_legacy_unit_without_config_in_downloaded_package(self):
  old=self.unit.with_name('syncsrv-agent.service');self.unit.rename(old)
  (self.root/'package/agent-control.conf').unlink()
  r=self.run_action('uninstall');self.assertEqual(0,r.returncode,r.stderr);self.assertFalse(old.exists())
 def test_mac_uninstall_unloads_and_removes_plist_retaining_data(self):
  conf=self.root/'package/agent-control.conf';conf.write_text(conf.read_text().replace('systemd','launchd'))
  plist=self.root/'launchd/com.oadig.syncsrv.agent.plist';plist.write_text('fixture')
  (self.root/'active').touch()
  r=self.run_action('uninstall');self.assertEqual(0,r.returncode,r.stderr)
  self.assertFalse(plist.exists());self.assertFalse((self.root/'active').exists())
  r=self.run_action('uninstall');self.assertEqual(0,r.returncode,r.stderr)

if __name__=='__main__':unittest.main()
