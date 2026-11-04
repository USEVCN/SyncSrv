"""Publisher-index tamper tests, no installed service or real data."""
import copy, hashlib, importlib.util, json, sys, tempfile, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'packaging/linux/bin'))
import release_source as r

class ReleaseSourceTest(unittest.TestCase):
 def fixture(self,root):
  self.url='https://releases.example.com/api/v1/projects/syncsrv/channels/stable/manifest?os=linux&arch=x64'
  path='/api/v1/projects/syncsrv/channels/stable/releases/11111111-1111-1111-1111-111111111111/artifacts/'
  rules={'sourceMin':'0.4.0','sourceMax':'0.4.0','schemaMin':1,'schemaMax':1}
  self.release={'product':'SyncSrv','channel':'stable','target':'linux-x64','version':'0.4.1','distribution':'relshub-v1',**rules,'asset':{'repository':'https://releases.example.com','name':'syncsrv-0.4.1-linux-x64.tar.gz','size':10,'sha256':'a'*64}}
  self.index={'releaseId':'11111111-1111-1111-1111-111111111111','spec':{'project':'syncsrv','os':'linux','arch':'x64','version':'0.4.1','targetSchema':1,'rollbackAllowed':True,**rules},'files':[{'name':r.SIDECAR,'kind':'INSTALLER','size':2,'sha256':hashlib.sha256(b'{}').hexdigest(),'url':path+'2'},{'name':'syncsrv-0.4.1-linux-x64.tar.gz','kind':'FULL','size':10,'sha256':'a'*64,'url':path+'1'}]}
  manifest=root/'index.json';manifest.write_text(json.dumps(self.index));return manifest
 def run_case(self,mutate=None,verify=None):
  with tempfile.TemporaryDirectory()as t:
   root=Path(t);manifest=self.fixture(root)
   if mutate:mutate(self.index,self.release);manifest.write_text(json.dumps(self.index))
   calls=[]
   def fetch(url,path,limit):calls.append(url);path.write_text('{}')
   return r.resolve(self.url,manifest,root,fetch,verify or (lambda path:copy.deepcopy(self.release)),lambda path:self.index),calls
 def test_routing_uses_signed_repository_name_size_and_hash(self):
  release,calls=self.run_case();self.assertEqual(1,len(calls));self.assertTrue(release['asset']['url'].endswith('/artifacts/1'))
 def test_unverified_index_cannot_change_digest_origin_or_identity(self):
  for mutate in [lambda i,p:i['files'][1].update(sha256='b'*64),lambda i,p:i['files'][1].update(url='https://evil.example/a'),lambda i,p:i['files'][0].update(url='http://169.254.169.254/credentials'),lambda i,p:i['spec'].update(version='0.4.2'),lambda i,p:p['asset'].update(repository='https://evil.example'),lambda i,p:i['files'].append(i['files'][1]),lambda i,p:i['spec'].update(project='le')]:
   with self.subTest(mutate=mutate),self.assertRaises(ValueError):self.run_case(mutate)
 def test_pinned_native_signature_failure_is_fatal(self):
  def fail(path):raise ValueError('Invalid signature')
  with self.assertRaises(ValueError):self.run_case(verify=fail)
 def test_native_feed_still_works(self):
  with tempfile.TemporaryDirectory()as t:
   root=Path(t);manifest=root/'manifest';manifest.write_text('{"payload":"signed"}')
   self.assertEqual({'verified':True},r.resolve('https://example.com/feed',manifest,root,None,lambda p:{'verified':True}))
 def test_default_https_and_narrow_http_exception(self):
  r.validate_url('https://example.com/feed');r.validate_url('http://192.168.50.118:18760/feed','http://192.168.50.118:18760')
  for url,allowed in [('http://192.168.50.118:18760/feed',None),('http://192.168.50.119:18760/feed','http://192.168.50.118:18760'),('http://example.com/feed','http://example.com'),('http://8.8.8.8/feed','http://8.8.8.8'),('https://user:pass@example.com/feed',None),('https://example.com/feed#fragment',None)]:
   with self.subTest(url=url),self.assertRaises(ValueError):r.validate_url(url,allowed)
 def test_same_version_and_source_compatibility(self):
  self.assertFalse(r.compatible({'version':'0.4.1'},'0.4.1'))
  self.assertTrue(r.compatible({'version':'0.4.2','sourceMin':'0.4.0','sourceMax':'0.4.1'},'0.4.1'))
  for payload in [{'version':'0.4.0'},{'version':'0.4.2','sourceMin':'0.4.2'},{'version':'0.4.2','sourceMax':'0.4.0'},{'version':'00.4.2'}]:
   with self.subTest(payload=payload),self.assertRaises(ValueError):r.compatible(payload,'0.4.1')

 def test_real_public_projection_and_legacy_public_paths_are_accepted(self):
  for prefix in ['/downloads/releases/','/downloads/releases/syncsrv/']:
   def paths(index,payload):
    for n,file in enumerate(index['files']):file['url']=prefix+index['releaseId']+'/files/'+str(n+1)
   with self.subTest(prefix=prefix):
    release,calls=self.run_case(paths);self.assertTrue(release['asset']['url'].endswith('/files/2'));self.assertEqual(1,len(calls))

 def test_channel_release_id_and_encoded_path_changes_are_rejected(self):
  for mutate in [lambda i,p:i['files'][0].update(url=i['files'][0]['url'].replace('/stable/','/beta/')),
   lambda i,p:i['files'][1].update(url=i['files'][1]['url'].replace(i['releaseId'],'22222222-2222-2222-2222-222222222222')),
   lambda i,p:i.update(releaseId='not-a-uuid'),lambda i,p:i['files'][0].update(url='/downloads/releases/'+i['releaseId']+'/files/%2e%2e'),
   lambda i,p:i['files'][0].update(url=i['files'][0]['url']+'?redirect=evil'),lambda i,p:i['files'][0].update(url=i['files'][0]['url']+'#fragment')]:
   with self.subTest(mutate=mutate),self.assertRaises(ValueError):self.run_case(mutate)

 def test_sidecar_digest_and_signed_compatibility_identity_are_required(self):
  for mutate in [lambda i,p:i['files'][0].update(size=4),lambda i,p:i['files'][0].update(sha256='b'*64),
   lambda i,p:p.update(channel='beta'),lambda i,p:p.update(target='linux-arm64'),lambda i,p:p.update(product='Other'),
   lambda i,p:i['spec'].update(sourceMin='0.4.1'),lambda i,p:i['spec'].update(schemaMax=2),
   lambda i,p:i['spec'].update(rollbackAllowed=False),lambda i,p:i['spec'].update(intermediate='0.4.0'),
   lambda i,p:p['asset'].update(repository='https://releases.example.com/other')]:
   with self.subTest(mutate=mutate),self.assertRaises(ValueError):self.run_case(mutate)

 def test_index_verification_failure_prevents_sidecar_fetch(self):
  with tempfile.TemporaryDirectory()as t:
   root=Path(t);manifest=self.fixture(root);calls=[]
   def rejected(path):raise ValueError('Outer signature rejected')
   with self.assertRaises(ValueError):r.resolve(self.url,manifest,root,lambda *args:calls.append(args),None,rejected)
   self.assertEqual([],calls)
   with self.assertRaises(ValueError):r.resolve(self.url,manifest,root,None,None)

 def test_configured_feed_platform_and_channel_are_strict(self):
  self.assertEqual('beta',r.feed_channel('https://example.com/api/v1/projects/syncsrv/channels/beta/manifest?arch=x64&os=linux'))
  for suffix in ['stable/manifest?os=windows&arch=x64','nightly/manifest?os=linux&arch=x64','stable/manifest?os=linux&arch=x64&arch=arm64']:
   with self.subTest(suffix=suffix),self.assertRaises(ValueError):r.feed_channel('https://example.com/api/v1/projects/syncsrv/channels/'+suffix)

if __name__=='__main__':unittest.main()
