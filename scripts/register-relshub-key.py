#!/usr/bin/env python3
"""Run once on the RelsHub host. Installs only SyncSrv's public key; no restart/DB mutation."""
import argparse,grp,hashlib,os,pwd,subprocess,tempfile
from pathlib import Path
PUBLIC=b'-----BEGIN PUBLIC KEY-----\nMCowBQYDK2VwAyEATpxPOGSlgUwpls2XdNYl4GRNBzRoPTIjU//l2K1YyMI=\n-----END PUBLIC KEY-----\n'
EXPECTED='8888c8a42e86c784685d9ea87a0b6ea5506d8b2ee023acf2a416a74928f4defb'

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path('/usr/RelsHub'));p.add_argument('--trusted-root',type=Path);p.add_argument('--expected-address',default='192.168.50.118');a=p.parse_args()
 if os.geteuid()!=0:raise PermissionError('Run as root on the RelsHub server')
 if a.expected_address not in subprocess.check_output(['hostname','-I'],text=True).split():raise ValueError('RelsHub host address differs')
 if hashlib.sha256(PUBLIC).hexdigest()!=EXPECTED:raise ValueError('Public key fingerprint differs')
 process=subprocess.check_output(['systemctl','show','relshub.service','--property=MainPID','--value'],text=True).strip()
 if not process.isdigit() or int(process)<=0:raise ValueError('RelsHub service must be running to resolve actual configuration')
 environment=dict(entry.split(b'=',1)for entry in (Path('/proc')/process/'environ').read_bytes().split(b'\0')if b'='in entry)
 actual=None
 settings=a.root/'config/settings/system.properties'
 if settings.is_file():
  for line in settings.read_text().splitlines():
   if line.startswith('relshub.trusted-keys='):actual=line.partition('=')[2].encode()
 actual=actual or environment.get(b'RELSHUB_TRUSTED_KEYS')
 actual=Path(actual.decode() if actual else str(a.root/'state/trusted-keys'))
 if a.trusted_root and a.trusted_root.resolve()!=actual.resolve():raise ValueError('Requested path differs from running service configuration')
 if not actual.is_absolute():raise ValueError('Trusted root must be absolute')
 for component in [actual,*actual.parents]:
  if component.is_symlink():raise ValueError('Trusted key directory must not use symbolic links')
 username=subprocess.check_output(['systemctl','show','relshub.service','--property=User','--value'],text=True).strip()or'root';account=pwd.getpwnam(username)
 directory=actual/'syncsrv';directory.mkdir(mode=0o750,parents=True,exist_ok=True)
 if directory.is_symlink():raise ValueError('Project key directory must not be a link')
 key=directory/'syncsrv-2026.pub'
 if key.exists() or key.is_symlink():
  if key.is_symlink()or key.read_bytes()!=PUBLIC:raise ValueError('Different public key exists; refusing replacement')
 else:
  fd,name=tempfile.mkstemp(prefix='.key-',dir=directory)
  try:
   with os.fdopen(fd,'wb')as out:out.write(PUBLIC);out.flush();os.fsync(out.fileno())
   os.chmod(name,0o644);os.chown(name,0,account.pw_gid);os.replace(name,key)
  finally:Path(name).unlink(missing_ok=True)
 os.chown(directory,0,account.pw_gid);os.chmod(directory,0o750)
 print('SyncSrv public key registered:',key,'SHA256:',EXPECTED)
 print('RelsHub was not restarted; other projects and keys were unchanged.')
if __name__=='__main__':main()
