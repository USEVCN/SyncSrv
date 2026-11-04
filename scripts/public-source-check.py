#!/usr/bin/env python3
"""Scan the exact staged/tracked Git blobs, never an ignored working directory."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
FORBIDDEN={'node_modules','target','work','artifacts','dist','data','backups','logs','.idea','.codex','.agents','native-io','__pycache__'}
PATTERNS={
 'private-key':re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----'),
 'github-token':re.compile(r'\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})'),
 'aws-key':re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
 'api-secret':re.compile(r'\bsk-[A-Za-z0-9_-]{24,}'),
 'personal-path':re.compile(r'/Users/' + r'usev/|C:\\Users\\USEV|192\.168\.5\.'),
}
# These contain deliberately invalid, non-key strings for redaction/file-input tests.
FIXTURES={'Frontend/src/test/settings-drawer.test.tsx','Backend/src/test/java/com/oadig/syncsrv/shared/web/SafeMessagesTest.java'}
def scan(name,content):
 p=Path(name);errors=[]
 if any(x in FORBIDDEN for x in p.parts) or p.parts[:2]==('Agent','bin') or (p.parts and p.parts[0]=='config') or p.name=='.env' or p.suffix.lower() in ('.pem','.p12','.pfx','.jks','.keystore','.db','.mv.db','.log','.jar','.exe','.zip','.spk'):
  errors.append('forbidden distribution path')
 try:text=content.decode('utf-8')
 except UnicodeDecodeError:
  reviewed=json.loads((ROOT/'licenses/assets-reviewed.json').read_text())
  if reviewed.get(name)==hashlib.sha256(content).hexdigest():return errors
  return errors+['binary content requires explicit distribution review']
 for key,pattern in PATTERNS.items():
  if pattern.search(text):
   if key=='private-key' and name in FIXTURES:
    head='-----BEGIN'+' PRIVATE KEY-----'
    cleaned=re.sub(re.escape(head)+r'(?:\\n|\n)(?:secret-fixture|fixture-key-material)(?:\\n|\n)-----END PRIVATE KEY-----','[INVALID TEST FIXTURE]',text)
    if not pattern.search(cleaned):continue
   errors.append(key)
 return errors
def main():
 p=argparse.ArgumentParser();p.add_argument('--output',type=Path);a=p.parse_args()
 paths=subprocess.check_output(['git','ls-files','-s','-z'],cwd=ROOT).split(b'\0');failures=[];items=[]
 for line in paths:
  if not line:continue
  meta,name=line.split(b'\t',1);mode,sha,stage=meta.split();name=name.decode()
  if mode not in (b'100644',b'100755') or stage!=b'0':failures.append({'path':name,'rules':['unsupported Git entry']});continue
  content=subprocess.check_output(['git','cat-file','blob',sha.decode()],cwd=ROOT)
  rules=scan(name,content)
  if rules:failures.append({'path':name,'rules':rules})
  items.append({'path':name,'sha256':hashlib.sha256(content).hexdigest(),'size':len(content)})
 if not items:raise SystemExit('Refuse to approve empty Git tree')
 result={'schema':1,'files':len(items),'bytes':sum(i['size']for i in items),'findings':failures,'inventory':items,'pass':not failures}
 if a.output:a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({k:result[k]for k in ('files','bytes','findings','pass')},ensure_ascii=False))
 if failures:raise SystemExit(2)
if __name__=='__main__':main()
