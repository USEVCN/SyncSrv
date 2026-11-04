"""Fault injection for center release activation. Never operates a host service."""
import importlib.util,io,json,tarfile,tempfile,unittest,sys
sys.path.insert(0,str(__import__('pathlib').Path(__file__).resolve().parent.parent/'packaging/linux/bin'))
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('updater',ROOT/'packaging/linux/bin/update.py');u=importlib.util.module_from_spec(spec);spec.loader.exec_module(u)
class UpdateTest(unittest.TestCase):
 def test_verifier_commands_remain_offline_and_channel_explicit(self):
  with patch.object(u.subprocess,'run')as process:
   process.return_value.stdout='{}'
   u.verify(Path('/installed'),Path('/metadata'),channel='beta')
   self.assertEqual(['verify-release','/metadata','SyncSrv','beta'],process.call_args.args[0][-4:])
   u.verify(Path('/installed'),Path('/metadata'),'https://repo.example/feed','verify-relshub-index')
   self.assertEqual('verify-relshub-index',process.call_args.args[0][-3])
 def test_atomic_activation_retains_previous_version(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t).resolve();(root/'one').mkdir();(root/'two').mkdir();(root/'current').symlink_to(root/'one');u.activate(root,root/'two');self.assertEqual(root/'two',(root/'current').resolve());self.assertTrue((root/'one').exists())
 def test_unsafe_archive_is_rejected_before_extraction(self):
  for name,kind in [('../escape',tarfile.REGTYPE),('/absolute',tarfile.REGTYPE),('image/link',tarfile.SYMTYPE)]:
   with self.subTest(name=name),tempfile.TemporaryDirectory() as t:
    root=Path(t).resolve();archive=root/'x.tar.gz';out=root/'out';out.mkdir()
    with tarfile.open(archive,'w:gz') as tar:
     info=tarfile.TarInfo(name);info.type=kind;info.linkname='/etc/passwd';tar.addfile(info)
    with self.assertRaises(ValueError):u.unpack(archive,out)
    self.assertEqual([],list(out.iterdir()))
 def test_secure_fetch_rejects_http_and_url_credentials(self):
  with tempfile.TemporaryDirectory() as t:
   for url in ['http://127.0.0.1/file','https://user:password@server/file']:
    with self.assertRaises(ValueError):u.fetch(url,Path(t)/'file',100)
 def test_atomic_journal_is_complete_json(self):
  with tempfile.TemporaryDirectory() as t:
   path=Path(t)/'state.json';u.atomic(path,{'status':'ACTIVATING'});u.atomic(path,{'status':'COMPLETED'});self.assertEqual({'status':'COMPLETED'},json.loads(path.read_text()))
 def test_health_failure_rolls_back_same_interrupted_transaction(self):
  with tempfile.TemporaryDirectory() as t:
   root=Path(t).resolve();old=root/'releases/0.4.0';new=root/'releases/0.4.1';old.mkdir(parents=True);new.mkdir();(old/'release.json').write_text('{"version":"0.4.0"}');(root/'current').symlink_to(new)
   (root/'upgrade-state.json').write_text(json.dumps(dict(status='ACTIVATED',previous=str(old),next=str(new))))
   calls=[]
   with patch.object(u,'check_root'),patch.object(u,'health') as health,patch.object(u.subprocess,'run',side_effect=lambda args,**kw:calls.append(args)),patch('sys.argv',['update.py','recover','--root',str(root)]):u.main()
   self.assertEqual(old,(root/'current').resolve());health.assert_called_once();self.assertEqual('ROLLED_BACK',json.loads((root/'upgrade-state.json').read_text())['status']);self.assertEqual([['systemctl','stop','syncsrv.service'],['systemctl','start','syncsrv.service']],calls)
 def test_interrupted_staging_keeps_files_and_frees_version_for_retry(self):
  for phase,exists in [('STAGING',False),('STAGING',True),('STAGED',True)]:
   with self.subTest(phase=phase,exists=exists),tempfile.TemporaryDirectory()as t:
    root=Path(t).resolve();old=root/'releases/0.4.0';new=root/'releases/0.4.1';old.mkdir(parents=True);(root/'current').symlink_to(old)
    if exists:new.mkdir();(new/'keep').write_text('verified release')
    journal=root/'upgrade-state.json';journal.write_text(json.dumps(dict(status=phase,previous=str(old),next=str(new))))
    with patch.object(u,'check_root'),patch.object(u.subprocess,'run')as service,patch('sys.argv',['update.py','recover','--root',str(root)]):u.main()
    self.assertEqual('ABORTED',json.loads(journal.read_text())['status']);self.assertFalse(new.exists());self.assertEqual(old,(root/'current').resolve());service.assert_not_called()
    if exists:self.assertEqual('verified release',next((root/'releases').glob('.aborted-*/keep')).read_text())
 def test_retry_after_health_rollback_retains_failed_image_and_releases_version_path(self):
  with tempfile.TemporaryDirectory()as t:
   root=Path(t).resolve();old=root/'0.4.0';new=root/'0.4.1';old.mkdir();new.mkdir();(new/'keep').write_text('failed image')
   state={'status':'ROLLED_BACK','next':str(new)};journal=root/'state.json'
   u.reserve_destination(new,old,state,journal)
   self.assertFalse(new.exists());self.assertEqual('failed image',(Path(state['retainedFailed'])/'keep').read_text())
   new.mkdir()
   with self.assertRaises(ValueError):u.reserve_destination(new,old,{'status':'COMPLETED','next':str(new)},journal)
 def test_signed_evidence_and_each_upgrade_phase_are_retained(self):
  with tempfile.TemporaryDirectory()as t:
   root=Path(t).resolve();stage=root/'stage';stage.mkdir();manifest=stage/'signed.json';manifest.write_text('{"payload":"signed","signature":"proof"}')
   release={'version':'0.4.1','asset':{'sha256':'a'*64},'applicationSha256':'b'*64}
   receipt=u.retain_evidence(root,root/'old',release,manifest,stage)
   journal=root/'upgrade-state.json';state={'status':'STAGED','evidence':receipt};u.journal(journal,state);state['status']='COMPLETED';u.journal(journal,state)
   saved=json.loads(Path(receipt).read_text());self.assertEqual('proof',saved['publisherManifest']['signature']);self.assertEqual(['STAGED','COMPLETED'],[e['phase']for e in saved['events']]);self.assertEqual('a'*64,saved['archiveSha256'])
 def test_explicit_root_manifest_does_not_consume_web_preference(self):
  with tempfile.TemporaryDirectory()as t:
   root=Path(t).resolve();old=root/'releases/0.4.3';old.mkdir(parents=True);(root/'current').symlink_to(old)
   explicit='https://explicit.example/api/v1/projects/syncsrv/channels/stable/manifest?os=linux&arch=x64'
   with patch.object(u,'check_root'),patch.object(u.update_source,'read_object',return_value={'manifest':'https://policy.example/manifest'}),patch.object(u.update_source,'preference_path')as preferences,patch.object(u,'fetch',side_effect=ValueError('isolated stop before network'))as fetch,patch('sys.argv',['update.py','check','--root',str(root),'--manifest',explicit]):
    with self.assertRaises(ValueError):u.main()
   preferences.assert_not_called();self.assertEqual(explicit,fetch.call_args.args[0])
 def test_legacy_url_uses_same_policy_directory_without_service_commands(self):
  with tempfile.TemporaryDirectory()as t:
   root=Path(t).resolve();old=root/'releases/0.4.3';old.mkdir(parents=True);(root/'current').symlink_to(old)
   legacy='https://legacy.example/api/v1/projects/syncsrv/channels/beta/manifest?os=linux&arch=x64'
   policy=root/'update.json';(root/'update.url').write_text(legacy+'\n')
   original_read=u.update_source.read_data
   with patch.object(u,'check_root'),patch.object(u.update_source,'read_object',return_value={}),patch.object(u.update_source,'read_data',side_effect=lambda p,*a,**kw:original_read(p,__import__('os').getuid(),**kw)),patch.object(u.update_source,'preference_path',return_value=root/'missing-state.json'),patch.object(u.update_source,'effective',side_effect=lambda base,allowed,path:base),patch.object(u,'fetch',side_effect=ValueError('isolated stop before network'))as fetch,patch.object(u.subprocess,'run')as services,patch('sys.argv',['update.py','check','--root',str(root),'--config',str(policy)]):
    with self.assertRaises(ValueError):u.main()
   self.assertEqual(legacy,fetch.call_args.args[0]);services.assert_not_called()
if __name__=='__main__':unittest.main()
