#!/usr/bin/env python3
"""Publish immutable, signed center releases after candidate verification."""
import argparse,datetime,hashlib,json,os,shutil,tarfile,tempfile
from pathlib import Path
from urllib.parse import urlparse
from release_sign import sign,verify

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--archive',type=Path,required=True);p.add_argument('--feed',type=Path,required=True);p.add_argument('--base-url',required=True);a=p.parse_args()
 url=urlparse(a.base_url)
 if url.scheme!='https' or not url.netloc or url.username or url.password or url.query or url.fragment:raise ValueError('HTTPS publisher origin/path required')
 with tarfile.open(a.archive)as tar:
  entries=[v for v in tar.getmembers()if v.name.endswith('/release.json') and v.name.count('/')==1]
  if len(entries)!=1 or entries[0].size>65536:raise ValueError('Ambiguous release image')
  release=json.load(tar.extractfile(entries[0]))
 version=release['version'];expected=f'syncsrv-{version}-linux-x64.tar.gz'
 if a.archive.name!=expected:raise ValueError('Archive identity mismatch')
 now=datetime.datetime.now(datetime.timezone.utc)
 digest=hashlib.sha256()
 with a.archive.open('rb')as f:
  for block in iter(lambda:f.read(1024*1024),b''):digest.update(block)
 payload={**release,'schema':1,'channel':'stable','issuedAt':now.isoformat().replace('+00:00','Z'),'expiresAt':(now+datetime.timedelta(days=365)).isoformat().replace('+00:00','Z'),
          'asset':dict(url=a.base_url.rstrip('/')+'/'+version+'/'+expected,size=a.archive.stat().st_size,sha256=digest.hexdigest())}
 trust=json.loads((Path(__file__).resolve().parent.parent/'Backend/src/main/resources/release-trust.json').read_text())
 prior=a.archive.with_name(a.archive.name.removesuffix('.tar.gz')+'.signed.json')
 authorized=verify(json.loads(prior.read_text()),trust)
 if authorized['asset']['sha256']!=digest.hexdigest() or authorized['asset']['size']!=a.archive.stat().st_size or authorized['version']!=version:raise ValueError('Archive differs from signed package')
 envelope=sign(payload)
 trust=json.loads((Path(__file__).resolve().parent.parent/'Backend/src/main/resources/release-trust.json').read_text());verify(envelope,trust)
 a.feed.mkdir(parents=True,exist_ok=True);destination=a.feed/version
 if destination.exists():raise ValueError('Published versions cannot be overwritten')
 with tempfile.TemporaryDirectory(prefix='.publish-',dir=a.feed)as temporary:
  stage=Path(temporary);shutil.copyfile(a.archive,stage/expected);(stage/'release.json').write_text(json.dumps(envelope,indent=2)+'\n');stage.chmod(0o755)
  for file in stage.iterdir():
   with file.open('rb')as f:os.fsync(f.fileno())
  stage.rename(destination)
 fd,path=tempfile.mkstemp(prefix='.latest-',dir=a.feed);temp=Path(path)
 with os.fdopen(fd,'w')as f:f.write(version+'\n');f.flush();os.fsync(f.fileno())
 temp.chmod(0o644);temp.replace(a.feed/'latest.txt')
 fd=os.open(a.feed,os.O_RDONLY)
 try:os.fsync(fd)
 finally:os.close(fd);print('Published signed center release',version)
if __name__=='__main__':main()
