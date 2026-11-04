"""Release publication protocol tests with in-memory server records and ephemeral test keys."""
import base64,hashlib,importlib.util,json,os,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
sys.path.insert(0,str(Path(__file__).resolve().parent))
spec=importlib.util.spec_from_file_location('publisher',Path(__file__).with_name('publish-relshub.py'));p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)
class FakeApi:
 def __init__(self):self.versions=[];self.builds=[];self.files=[];self.heads=[];self.releases=0;self.tamper=False;self.revision=0
 def request(self,path,method='GET',body=None,file=None,headers=None):
  route=path.split('?')[0]
  if route=='/api/projects':return [{'id':3,'code':'syncsrv'}]
  if route.endswith('/automation/lookup'):return {'versions':self.versions,'builds':self.builds}
  if route.endswith('/versions'):
   if method=='POST':self.versions.append({'id':1,**body})
   return self.versions
  if route.endswith('/builds'):
   if method=='POST':self.builds.append({'id':2,'build_number':body['buildNumber'],**body})
   return self.builds
  if route.endswith('/artifacts'):
   if file:
    from urllib.parse import parse_qs,urlsplit
    query=parse_qs(urlsplit(path).query);self.files.append({'id':len(self.files)+1,'original_name':file.name,'sha256':p.digest(file),'size_bytes':file.stat().st_size,'state':'HASH_VERIFIED','kind':query['kind'][0]})
   return self.files
  if route.endswith('/manifest'):
   b=self.builds[0];s={'project':'syncsrv','version':self.versions[0]['version'],'buildNumber':b['build_number'],'notes':self.versions[0]['notes'],'keyId':'syncsrv-2026',**{k:b[k]for k in ('os','arch','sourceMin','sourceMax','intermediate','schemaMin','schemaMax','targetSchema','rollbackAllowed')},'files':sorted([{'name':f['original_name'],'sha256':f['sha256'],'size':f['size_bytes'],'kind':f['kind'],'sourceVersion':None}for f in self.files],key=lambda f:f['name'])}
   if self.tamper:s['project']='le'
   return {'spec':s,'canonicalBase64':base64.b64encode(p.canonical(s)).decode()}
  if route.endswith('/publish'):self.releases+=1;return {'releaseId':'11111111-1111-1111-1111-111111111111'}
  if route.endswith('/recover'):return {}
  if route.endswith('/channels'):return self.heads
  if route.endswith('/heads'):
   if method=='POST':self.heads.append({'name':'stable','os':'linux','arch':'x64','revision':self.revision,'release_id':None})
   else:
    if body['revision']!=self.heads[0]['revision']:raise ValueError('CAS')
    self.heads[0].update(release_id=body['releaseId'],revision=body['revision']+1)
   return {'state':'READY'}
  raise AssertionError(path)
class PublisherTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.key=Ed25519PrivateKey.generate();self.api=FakeApi();self.archive=self.root/'syncsrv-0.4.1-linux-x64.tar.gz';self.archive.write_bytes(b'Exact test archive');self.notes=self.root/'notes';self.notes.write_text('Release notes with actual changes, rollback validation and platform boundaries.')
  self.prior={'product':'SyncSrv','target':'linux-x64','schema':1,'schemaMin':1,'schemaMax':1,'version':'0.4.1','sourceSha256':'a'*64,'asset':{'sha256':p.digest(self.archive),'size':self.archive.stat().st_size}}
  self.archive.with_name('syncsrv-0.4.1-linux-x64.signed.json').write_text('{}');self.profile={'url':'https://releases.example.com','project':'syncsrv','keyId':'syncsrv-2026','receipts':str(self.root/'receipts')}
  self.patches=[patch.object(p,'signing_key',return_value=self.key),patch.object(p,'token_for',return_value='hidden'),patch.object(p,'Api',return_value=self.api),patch.object(p,'verify',return_value=self.prior)]
  for mock in self.patches:mock.start()
 def tearDown(self):
  for mock in reversed(self.patches):mock.stop()
  self.tmp.cleanup()
 def publish(self):return p.publish(self.profile,self.archive,self.notes,'0.4.0','0.4.0')
 def test_streamed_publish_and_native_signature_bind_exact_package(self):
  result=self.publish();self.assertEqual('recommended',result['status']);self.assertEqual(2,len(self.api.files));folder=next((self.root/'receipts').iterdir());e=json.loads((folder/p.SIDECAR).read_text());raw=base64.b64decode(e['payload']);self.key.public_key().verify(base64.b64decode(e['signature']),raw);native=json.loads(raw);self.assertEqual(p.digest(self.archive),native['asset']['sha256']);self.assertEqual(self.profile['url'],native['asset']['repository'])
 def test_unknown_outcome_retry_reuses_files_and_does_not_repromote(self):
  first=self.publish();revision=self.api.heads[0]['revision'];second=self.publish();self.assertEqual(first['releaseId'],second['releaseId']);self.assertEqual(2,len(self.api.files));self.assertEqual(revision,self.api.heads[0]['revision'])
 def test_server_cannot_substitute_project_or_artifacts_in_signing_plan(self):
  self.api.tamper=True
  with self.assertRaises(ValueError):self.publish()
  self.assertEqual(0,self.api.releases)
 def test_exact_archive_digest_must_match_signed_candidate(self):
  self.archive.write_bytes(b'tampered')
  with self.assertRaises(ValueError):self.publish()
  self.assertEqual([],self.api.versions)
 def test_existing_channel_revision_is_not_overwritten_after_failure(self):
  self.api.tamper=True
  with self.assertRaises(ValueError):self.publish()
  self.api.tamper=False;self.api.heads=[{'name':'stable','os':'linux','arch':'x64','revision':7,'release_id':'another'}]
  with self.assertRaises(ValueError):self.publish()
  self.assertEqual('another',self.api.heads[0]['release_id'])
 def test_beta_publication_binds_native_channel_instead_of_inheriting_stable(self):
  self.prior['channel']='stable'
  p.publish(self.profile,self.archive,self.notes,'0.4.0','0.4.0',channel='beta',register_only=True)
  folder=next((self.root/'receipts').iterdir());native=json.loads(base64.b64decode(json.loads((folder/p.SIDECAR).read_text())['payload']))
  self.assertEqual('beta',native['channel'])
 def test_wrong_platform_or_schema_cannot_create_remote_versions(self):
  for field,value in [('target','linux-arm64'),('schemaMax',2)]:
   original=self.prior[field];self.prior[field]=value
   with self.assertRaises(ValueError):self.publish()
   self.assertEqual([],self.api.versions);self.prior[field]=original
if __name__=='__main__':unittest.main()
