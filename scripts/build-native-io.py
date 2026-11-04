#!/usr/bin/env python3
"""Build the stdlib-only storage bridge; no signing credentials or live state required."""
import argparse,hashlib,json,os,platform,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
def main():
 p=argparse.ArgumentParser();p.add_argument('--go',default='go');p.add_argument('--host-only',action='store_true');a=p.parse_args()
 expected=json.loads((ROOT/'packaging/go-toolchain.lock.json').read_text())['version']
 if subprocess.check_output([a.go,'env','GOVERSION'],text=True).strip()!=expected:raise SystemExit('Use pinned '+expected)
 targets=[(s,c)for s in ('darwin','linux','windows')for c in ('amd64','arm64')]
 if a.host_only:
  system={'Darwin':'darwin','Linux':'linux','Windows':'windows'}[platform.system()];arch={'aarch64':'arm64','arm64':'arm64','x86_64':'amd64','AMD64':'amd64'}[platform.machine()];targets=[(system,arch)]
 destination=ROOT/'Backend/src/main/resources/native-io';destination.mkdir(parents=True,exist_ok=True);hashes={}
 for system,arch in targets:
  name=system+'-'+arch+('.exe'if system=='windows'else'');output=destination/name
  subprocess.run([a.go,'build','-buildvcs=false','-trimpath','-ldflags=-s -w -buildid=','-o',str(output),'.'],cwd=ROOT/'NativeIO',env={**os.environ,'GOOS':system,'GOARCH':arch,'CGO_ENABLED':'0'},check=True)
  hashes[name]=hashlib.sha256(output.read_bytes()).hexdigest()
 (destination/'checksums.json').write_text(json.dumps(hashes,sort_keys=True)+'\n');print('Built safe local I/O:',', '.join(hashes))
if __name__=='__main__':main()
