#!/usr/bin/env python3
"""Audit the complete resolved source graph; reconcile every packaged runtime JAR."""
import argparse,datetime,hashlib,io,json,os,subprocess,urllib.request,urllib.parse,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent

def stream_json(raw):
 decoder=json.JSONDecoder()
 while raw.strip():
  value,n=decoder.raw_decode(raw.lstrip());raw=raw.lstrip()[n:];yield value

def maven(tree):
 result={}
 if tree.suffix=='.txt':
  for line in tree.read_text().splitlines():
   parts=line.strip().split(' -- module ',1)[0].split(':')
   if len(parts)<6 or parts[2]!='jar':continue
   scopes=[i for i,value in enumerate(parts)if value in ('compile','runtime')]
   if len(scopes)!=1:raise ValueError('Ambiguous Maven artifact record')
   i=scopes[0];name=parts[0]+':'+parts[1];version=parts[i-1];file=Path(':'.join(parts[i+1:])).name
   result[name,version]={'ecosystem':'Maven','name':name,'version':version,'file':file,'scope':'runtime','_artifactPath':':'.join(parts[i+1:])}
  if not result:raise ValueError('Maven artifact inventory is empty')
  return list(result.values())
 def walk(n):
  for c in n.get('children',[]):
   key=(c['groupId']+':'+c['artifactId'],c['version'])
   if c.get('type','jar')=='jar':result[key]={'ecosystem':'Maven','name':key[0],'version':key[1],
     'file':c['artifactId']+'-'+c['version']+('-'+c['classifier']if c.get('classifier')else'')+'.jar','scope':'runtime'}
   walk(c)
 walk(json.loads(tree.read_text()));return list(result.values())

def inventory(tree,go):
 result=maven(tree)
 for location,p in json.loads((ROOT/'Frontend/package-lock.json').read_text())['packages'].items():
  if not location.startswith('node_modules/') or not p.get('version'):continue
  result.append({'ecosystem':'npm','name':location.rsplit('node_modules/',1)[-1],'version':p['version'],
   'scope':'development'if p.get('dev')else'runtime','declaredLicense':p.get('license','UNKNOWN')})
 modules=subprocess.check_output([go,'list','-m','-json','all'],cwd=ROOT/'Agent',text=True)
 for m in stream_json(modules):
  if m.get('Main'):continue
  if m.get('Replace'):raise ValueError('Replaced Go module requires explicit review')
  result.append({'ecosystem':'Go','name':m['Path'],'version':m['Version'],'scope':'agent module graph'})
 version=subprocess.check_output([go,'env','GOVERSION'],text=True).strip().removeprefix('go')
 result.append({'ecosystem':'Go','name':'stdlib','version':version,'scope':'agent and native I/O bridge'})
 unique={}
 for p in result:
  key=(p['ecosystem'],p['name'],p['version'])
  if key not in unique:unique[key]=p
 return sorted(unique.values(),key=lambda p:(p['ecosystem'],p['name'],p['version']))

def reconcile(jar,packages):
 candidates={}
 for p in packages:
  if p['ecosystem']=='Maven':candidates.setdefault(p['file'],[]).append(p)
 with zipfile.ZipFile(jar)as z:
  names=[n for n in z.namelist()if n.startswith('BOOT-INF/lib/')and n.endswith('.jar')]
  for name in names:
   matches=candidates.get(Path(name).name,[])
   # Boot's repackage plugin injects its tools JAR outside the resolved runtime graph.
   boot=ET.parse(ROOT/'Backend/pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}parent/{http://maven.apache.org/POM/4.0.0}version').text
   if not matches and Path(name).name=='spring-boot-jarmode-tools-'+boot+'.jar':
    url='https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-jarmode-tools/'+boot+'/spring-boot-jarmode-tools-'+boot+'.jar'
    with urllib.request.urlopen(url,timeout=45)as proof:official=proof.read(16*1024*1024+1)
    if len(official)>16*1024*1024 or official!=z.read(name):raise ValueError('Boot plugin artifact differs from official Maven Central bytes')
    extra={'ecosystem':'Maven','name':'org.springframework.boot:spring-boot-jarmode-tools','version':boot,'file':Path(name).name,'scope':'repackage plugin bundled runtime'}
    packages.append(extra);matches=[extra]
   if len(matches)!=1:raise ValueError('Unmapped/ambiguous packaged dependency: '+name)
   matches[0]['sha256']=hashlib.sha256(z.read(name)).hexdigest();matches[0]['packaged']=True
 missing=[]
 for p in packages:
  if p['ecosystem']!='Maven'or p.get('packaged'):continue
  if not p['name'].startswith('org.springframework.boot:spring-boot-starter'):missing.append(p['name']);continue
  with zipfile.ZipFile(p['_artifactPath'])as starter:
   files=[n for n in starter.namelist()if not n.endswith('/')]
   allowed=all(n in ('META-INF/MANIFEST.MF','META-INF/LICENSE.txt','META-INF/NOTICE.txt')or n.startswith('META-INF/maven/')for n in files)
   if not allowed or b'Class-Path:'in starter.read('META-INF/MANIFEST.MF'):missing.append(p['name']);continue
  p['packaged']=False;p['excludedReason']='Verified metadata-only Boot starter, omitted by official repackage plugin'
 if missing:raise ValueError('Resolved runtime JARs missing from application: '+str(missing))
 return len(names)

def fetch(url,payload=None):
 request=urllib.request.Request(url,None if payload is None else json.dumps(payload).encode(),
  {'Content-Type':'application/json','User-Agent':'SyncSrv-Source-Security-Review'})
 with urllib.request.urlopen(request,timeout=45)as response:return json.load(response)

def apply_vex(findings,go):
 document=json.loads((ROOT/'security-vex.json').read_text())
 graphs={};proof={}
 for finding in findings:
  p=finding['package']
  matches=[s for s in document['statements']if (s['id'],s['ecosystem'],s['name'],s['version'])==(finding['id'],p['ecosystem'],p['name'],p['version'])]
  if not matches:continue
  if document['expiresAt']<datetime.date.today().isoformat():raise ValueError('VEX review expired')
  s=matches[0]
  if s['justification']!='component_not_present'or p['ecosystem']!='Go':raise ValueError('Unsupported VEX evidence')
  paths={i['path']for a in finding['affected']if a['package']['name']==p['name']for i in a.get('ecosystem_specific',{}).get('imports',[])}
  if not paths:raise ValueError('VEX lacks affected-package evidence')
  expected={system+'/'+arch for system in ('darwin','linux','windows')for arch in ('amd64','arm64')}
  if set(s['requiredPlatforms'])!=expected:raise ValueError('VEX platform matrix incomplete')
  for target in sorted(expected):
   if target not in graphs:
    system,arch=target.split('/');env={**os.environ,'GOOS':system,'GOARCH':arch,'CGO_ENABLED':'1'if system=='darwin'else'0'}
    raw=subprocess.check_output([go,'list','-deps','-json','.'],cwd=ROOT/'Agent',env=env,text=True)
    graphs[target]={p['ImportPath']for p in stream_json(raw)}
    proof[target]={'packages':len(graphs[target]),'sha256':hashlib.sha256('\n'.join(sorted(graphs[target])).encode()).hexdigest()}
   if any(q==path or q.startswith(path+'/')for q in graphs[target]for path in paths):raise ValueError('VEX rejected: affected package imported on '+target)
  finding['vex']={'status':'not_affected','justification':'component_not_present','platformEvidence':proof,'expiresAt':document['expiresAt']}
 return sum('vex'in f for f in findings)

def main():
 p=argparse.ArgumentParser();p.add_argument('--jar',type=Path,required=True);p.add_argument('--tree',type=Path,default=ROOT/'Backend/target/runtime-artifacts.txt');p.add_argument('--go',default='go');p.add_argument('--output',type=Path,required=True);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 if not a.tree.is_file():
  subprocess.run(['mvn','--batch-mode','org.apache.maven.plugins:maven-dependency-plugin:3.8.1:list','-DincludeScope=runtime','-DoutputAbsoluteArtifactFilename=true','-DoutputFile='+str(a.tree.resolve())],cwd=ROOT/'Backend',check=True)
 packages=inventory(a.tree,a.go);count=reconcile(a.jar,packages)
 for package in packages:package.pop('_artifactPath',None)
 (a.output/'inventory.json').write_text(json.dumps(packages,ensure_ascii=False,indent=2)+'\n')
 results=fetch('https://api.osv.dev/v1/querybatch',{'queries':[{'package':{'ecosystem':v['ecosystem'],'name':v['name']},'version':v['version']}for v in packages]})['results']
 if len(results)!=len(packages):raise ValueError('Incomplete OSV batch response')
 details={};findings=[]
 for package,result in zip(packages,results):
  if result.get('next_page_token'):raise ValueError('OSV pagination requires review; refusing partial scan')
  for issue in result.get('vulns',[]):
   key=issue['id']
   if key not in details:details[key]=fetch('https://api.osv.dev/v1/vulns/'+urllib.parse.quote(key,safe=''))
   detail=details[key]
   if detail.get('withdrawn'):continue
   findings.append({'package':package,'id':key,'summary':detail.get('summary',''),'aliases':detail.get('aliases',[]),
    'references':detail.get('references',[]),'severity':detail.get('severity',[]),
    'affected':detail.get('affected',[])})
 not_affected=apply_vex(findings,a.go)
 (a.output/'osv-findings.json').write_text(json.dumps(findings,ensure_ascii=False,indent=2)+'\n')
 summary={'components':len(packages),'mappedRuntimeJars':count,'unmappedRuntimeJars':0,'findings':len(findings),'notAffectedWithPlatformProof':not_affected,'unresolved':len(findings)-not_affected,'pass':len(findings)==not_affected}
 (a.output/'summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
 if not summary['pass']:raise SystemExit(2)
if __name__=='__main__':main()
