#!/usr/bin/env python3
"""Privileged Linux center updater. Verify with the installed, pinned Java verifier first."""
import argparse, contextlib, fcntl, hashlib, json, os, shutil, subprocess, tarfile, tempfile, time, uuid
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import build_opener, HTTPRedirectHandler, Request
import release_source
import update_source

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('Release redirects are forbidden')

def sync_directory(path):
    fd=os.open(path,os.O_RDONLY)
    try:os.fsync(fd)
    finally:os.close(fd)

def durable_image(image):
    for path in image.rglob("*"):
        if path.is_file():
            with path.open("rb")as file:os.fsync(file.fileno())
    for path in sorted((p for p in image.rglob("*")if p.is_dir()),key=lambda p:len(p.parts),reverse=True):sync_directory(path)
    sync_directory(image)

def atomic(path, value):
    temp=path.with_suffix('.tmp')
    with temp.open('w') as f:
        os.fchmod(f.fileno(),0o600)
        json.dump(value,f,sort_keys=True);f.flush();os.fsync(f.fileno())
    os.replace(temp,path)
    fd=os.open(path.parent,os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)

def journal(path,state):
    atomic(path,state)
    if not state.get('evidence'):return
    evidence=Path(state['evidence'])
    if evidence.parent!=path.parent/'upgrade-evidence' or evidence.is_symlink():
        raise ValueError('Upgrade evidence path escapes installation')
    receipt=json.loads(evidence.read_text())
    receipt.setdefault('events',[]).append({'phase':state['status'],'time':time.time()})
    atomic(evidence,receipt)

def retain_evidence(root,current,release,manifest,stage):
    directory=root/'upgrade-evidence';directory.mkdir(mode=0o700,exist_ok=True)
    if directory.is_symlink():raise ValueError('Upgrade evidence directory must not be a link')
    signed=stage/release_source.SIDECAR
    if not signed.exists():signed=manifest
    path=directory/(uuid.uuid4().hex+'.json')
    receipt={'publisherManifest':json.loads(signed.read_text()),'discoverySha256':digest(manifest),
             'previous':str(current),'targetVersion':release['version'],'archiveSha256':release['asset']['sha256'],
             'applicationSha256':release['applicationSha256'],'events':[]}
    atomic(path,receipt);path.chmod(0o600)
    return str(path)

def fetch(url,path,maximum,allow_http_origin=None):
    release_source.validate_url(url,allow_http_origin)
    with build_opener(NoRedirect).open(Request(url,headers={'User-Agent':'SyncSrv-Updater'}),timeout=30) as response, path.open('xb') as out:
        count=0
        while True:
            block=response.read(1024*1024)
            if not block: break
            count+=len(block)
            if count>maximum: raise ValueError('Release exceeds size limit')
            out.write(block)
        out.flush();os.fsync(out.fileno())

def digest(path):
    sha=hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda:source.read(1024*1024),b""):sha.update(block)
    return sha.hexdigest()

def unpack(archive,destination):
    with tarfile.open(archive,'r:gz') as tar:
        members=tar.getmembers()
        total=0
        for item in members:
            parts=Path(item.name).parts
            if not parts or Path(item.name).is_absolute() or '..' in parts or not (item.isdir() or item.isfile()):
                raise ValueError('Unsafe package member')
            total+=item.size
            if total>2*1024**3 or len(members)>50000: raise ValueError('Unpacked release too large')
        tar.extractall(destination,members=members)
    children=list(destination.iterdir())
    if len(children)!=1 or not children[0].is_dir(): raise ValueError('Package must contain one image')
    return children[0]

def verify(package,manifest,product='SyncSrv',command='verify-release',channel=None):
    environment={k:v for k,v in os.environ.items() if k not in {'JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','CLASSPATH','LD_PRELOAD','LD_LIBRARY_PATH'}}
    try:
        result=subprocess.run([str(package/'runtime/bin/java'),'-jar',str(package/'app/syncsrv.jar'),
                           command,str(manifest),product]+([channel]if channel else []),check=True,capture_output=True,text=True,timeout=30,env=environment)
    except subprocess.CalledProcessError as error:
        raise ValueError('Installed verifier rejected metadata; check signature and keep updater/application from the same signed image') from error
    return json.loads(result.stdout)

def activate(root,target):
    link=root/'.current-next'
    link.unlink(missing_ok=True);link.symlink_to(target)
    os.replace(link,root/'current')
    fd=os.open(root,os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)

def health(url,version,seconds=90):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            with build_opener(NoRedirect).open(url,timeout=3) as r: body=json.load(r)
            data=body.get('data',{})
            if body.get('success') and data.get('database')=='UP' and data.get('version')==version:
                return
        except (OSError,ValueError): pass
        time.sleep(2)
    raise RuntimeError('New center did not pass database/version health check')

def check_root(root):
    if os.geteuid()!=0: raise PermissionError('Run with sudo/root')
    for directory in [root,*root.parents]:
        st=directory.stat()
        if directory.is_symlink() or st.st_uid!=0 or st.st_mode&0o022:
            raise PermissionError('Installation ancestry must be root-owned and not group/world writable')

def reserve_destination(destination,current,state,statefile):
    if not destination.exists():return
    if state.get('status')!='ROLLED_BACK' or Path(state.get('next',''))!=destination or destination.is_symlink() or destination.resolve()==current:
        raise ValueError('Release directory already exists')
    retained=destination.with_name('.rolled-back-'+destination.name+'-'+uuid.uuid4().hex)
    destination.rename(retained);sync_directory(destination.parent)
    state['retainedFailed']=str(retained);journal(statefile,state)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['check','update','rollback','recover'])
    parser.add_argument('--root',type=Path,default=Path('/opt/syncsrv'))
    parser.add_argument('--manifest',help='Signed SyncSrv feed or RelsHub channel manifest URL')
    parser.add_argument('--allow-http-origin',help='Explicit intranet validation origin; HTTPS remains the default')
    parser.add_argument('--config',type=Path,default=Path('/etc/syncsrv/update.json'))
    parser.add_argument('--health-url',default='http://127.0.0.1:18010/api/health')
    args=parser.parse_args();root=args.root.absolute();check_root(root);root=root.resolve()
    with (root/'.update.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        statefile=root/'upgrade-state.json'
        state=json.loads(statefile.read_text()) if statefile.exists() else {}
        current=(root/'current').resolve(strict=True)
        if not current.is_relative_to(root): raise ValueError('Current release escapes installation')
        def service(action): subprocess.run(['systemctl',action,'syncsrv.service'],check=True,timeout=120)
        def rollback():
            old=Path(state['previous'])
            if not old.is_relative_to(root/'releases') or not old.is_dir(): raise ValueError('No valid rollback release')
            service('stop');activate(root,old);service('start')
            health(args.health_url,json.loads((old/'release.json').read_text())['version'])
            state['status']='ROLLED_BACK';journal(statefile,state)
        if args.action in ['rollback','recover']:
            if state.get('status') in ['STAGING','STAGED']:
                candidate=Path(state['next'])
                if current!=Path(state['previous']).resolve() or candidate.parent!=root/'releases' or candidate.is_symlink() or candidate.resolve()==current:
                    raise ValueError('Staging recovery identity differs from the installed version')
                if candidate.exists():
                    abandoned=candidate.with_name('.aborted-'+candidate.name+'-'+uuid.uuid4().hex)
                    candidate.rename(abandoned);sync_directory(candidate.parent);state['abandoned']=str(abandoned)
                state['status']='ABORTED';journal(statefile,state)
                print('Unactivated release retained; retry update when ready')
            elif state.get('status') in ['ACTIVATING','ACTIVATED','FAILED'] or args.action=='rollback': rollback()
            else: print('No interrupted upgrade')
            return
        if state.get('status') in ['STAGING','STAGED','ACTIVATING','ACTIVATED','FAILED']:
            raise RuntimeError('Recover the interrupted upgrade before another update')
        config=update_source.read_object(args.config,0,missing=True) or {}
        explicit_manifest=args.manifest
        args.manifest=args.manifest or update_source.policy_manifest(config)
        args.allow_http_origin=args.allow_http_origin or config.get('allowHttpOrigin')
        if args.allow_http_origin is not None and not isinstance(args.allow_http_origin,str):raise ValueError('HTTP origin policy must be text')
        # An explicit root CLI source overrides preferences. Root-owned policy controls HTTP exceptions.
        if not explicit_manifest:
            legacy=args.config.absolute().with_name('update.url')
            if not config:
                previous=update_source.read_data(legacy,0,missing=True,maximum=4096)
                if previous is not None:args.manifest=previous.decode().strip()
            preference=update_source.preference_path(config)
            args.manifest=update_source.effective(args.manifest,args.allow_http_origin,preference)
        release_source.validate_url(args.manifest,args.allow_http_origin)
        def download(url,path,maximum):fetch(url,path,maximum,args.allow_http_origin)
        releases=root/'releases';releases.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.upgrade-',dir=root) as temp:
            stage=Path(temp);manifest=stage/'signed-release.json';download(args.manifest,manifest,262144)
            channel=release_source.feed_channel(args.manifest) if '/api/v1/projects/' in args.manifest else 'stable'
            release=release_source.resolve(args.manifest,manifest,stage,download,
                lambda path:verify(current,path,channel=channel),
                lambda path:verify(current,path,args.manifest,'verify-relshub-index'))
            if release.get('schemaMin')!=1 or release.get('schemaMax')!=1:
                raise ValueError('Schema migration requires an operator-supervised release')
            import re
            version=release['version']
            if not re.fullmatch(r'\d+\.\d+\.\d+',version): raise ValueError('Stable version required')
            old=json.loads((current/'release.json').read_text())
            if release.get('target')!='linux-x64': raise ValueError('Wrong center platform')
            minimum=release.get('rollbackMinimumVersion')
            if not minimum or tuple(map(int,minimum.split('.'))) > tuple(map(int,old['version'].split('.'))):
                raise ValueError('This release does not promise rollback compatibility with the installed version')
            if not release_source.compatible(release,old['version']):
                print('Already up to date:',version);return
            asset=release['asset']
            if not 0<asset['size']<=1024**3: raise ValueError('Package size out of bounds')
            if shutil.disk_usage(root).free < asset['size']*4+1024**3: raise OSError('Insufficient upgrade/rollback space')
            print('Verified publisher release',version)
            if args.action=='check':return
            archive=stage/'package.tar.gz';download(asset['url'],archive,asset['size'])
            if archive.stat().st_size!=asset['size'] or digest(archive)!=asset['sha256']:
                raise ValueError('Package SHA-256/size mismatch')
            unpacked=stage/'unpack';unpacked.mkdir();image=unpack(archive,unpacked)
            packaged=json.loads((image/'release.json').read_text())
            if packaged['version']!=version or packaged['applicationSha256']!=release['applicationSha256']:
                raise ValueError('Signed release identity mismatch')
            subprocess.run(['sh',str(image/'bin/verify-package.sh')],check=True,timeout=120)
            # No untrusted code ran before publisher signature and complete package digest checks.
            subprocess.run(['sh',str(image/'bin/start.sh'),'--runtime-info'],check=True,timeout=30)
            destination=releases/version
            reserve_destination(destination,current,state,statefile)
            state={'status':'STAGING','previous':str(current),'next':str(destination),'version':version,'time':time.time(),
                   'evidence':retain_evidence(root,current,release,manifest,stage)}
            journal(statefile,state)
            durable_image(image);image.rename(destination);sync_directory(releases)
            state['status']='STAGED';journal(statefile,state)
            # SIGTERM drains task workers; their signed journals remain resumable. Never modify B/C here.
            state['status']='ACTIVATING';journal(statefile,state)
            try:
                service('stop')
                activate(root,destination);state['status']='ACTIVATED';journal(statefile,state)
                service('start');health(args.health_url,version)
                state['status']='COMPLETED';journal(statefile,state)
            except BaseException:
                state['status']='FAILED'
                try:journal(statefile,state)
                except OSError:pass
                rollback();raise
            print('Upgraded to',version,'Previous release retained:',current)

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,KeyError,subprocess.SubprocessError)as error:raise SystemExit('Update aborted: '+str(error))
