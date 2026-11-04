import importlib.util,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('audit',Path(__file__).with_name('source-dependency-audit.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class DependencyEvidenceTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
  self.statement={'id':'TEST-ADVISORY','ecosystem':'Go','name':'example.test/module','version':'v1.0.0','justification':'component_not_present','requiredPlatforms':[s+'/'+a for s in ('darwin','linux','windows')for a in ('amd64','arm64')]}
  self.document={'expiresAt':'2099-01-01','statements':[self.statement]};self.save()
  self.finding={'id':'TEST-ADVISORY','package':{'ecosystem':'Go','name':'example.test/module','version':'v1.0.0'},'affected':[{'package':{'name':'example.test/module'},'ecosystem_specific':{'imports':[{'path':'example.test/module/unsafe'}]}}]}
 def save(self):(self.root/'security-vex.json').write_text(json.dumps(self.document))
 def proof(self,imports):
  with patch.object(m,'ROOT',self.root),patch.object(m.subprocess,'check_output',return_value=''.join(json.dumps({'ImportPath':p})for p in imports))as calls:
   result=m.apply_vex([self.finding],'go');return result,calls.call_count
 def test_absence_requires_all_six_regenerated_platforms(self):
  count,calls=self.proof(['example.test/module/safe']);self.assertEqual((1,6),(count,calls));self.assertEqual(6,len(self.finding['vex']['platformEvidence']))
 def test_affected_import_rejects_statement(self):
  with self.assertRaisesRegex(ValueError,'affected package imported'):self.proof(['example.test/module/unsafe'])
 def test_expired_or_incomplete_statement_cannot_clear_finding(self):
  self.document['expiresAt']='2000-01-01';self.save()
  with self.assertRaisesRegex(ValueError,'expired'):self.proof([])
  self.document['expiresAt']='2099-01-01';self.statement['requiredPlatforms'].pop();self.save()
  with self.assertRaisesRegex(ValueError,'incomplete'):self.proof([])
 def test_new_version_does_not_inherit_an_old_vex(self):
  self.finding['package']['version']='v2.0.0';self.assertEqual((0,0),self.proof([]));self.assertNotIn('vex',self.finding)
 def test_resolved_maven_list_keeps_dependencies_without_pom_properties(self):
  path=self.root/'artifacts.txt';path.write_text('   org.example:library:jar:1.0:compile:/tmp/library-1.0.jar -- module fixture\n')
  result=m.maven(path);self.assertEqual('org.example:library',result[0]['name']);self.assertEqual('library-1.0.jar',result[0]['file'])
if __name__=='__main__':unittest.main()
