#!/usr/bin/env python3
"""Bind an immutable release to production source, exact JAR bytes, and verification evidence."""
import argparse, hashlib, json, re, time, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
EXCLUDE={'target','node_modules','dist','data','work','artifacts','.git','__pycache__','static'}
IDENTITY='Backend/src/main/resources/build-identity.json'

def inputs(directories, files):
    paths=set()
    for directory in directories:
        base=ROOT/directory
        if not base.exists():continue
        for path in base.rglob('*'):
            relative=path.relative_to(ROOT)
            if any(part in EXCLUDE for part in relative.parts) or relative.as_posix()==IDENTITY or path.name.endswith(('.log','.pyc')):continue
            if path.is_symlink():raise ValueError('Release input must not be a symlink: '+str(relative))
            if path.is_file():paths.add(path)
    for name in files:
        path=ROOT/name
        if path.is_symlink():raise ValueError('Release input must not be a symlink: '+name)
        if path.is_file():paths.add(path)
    return sorted(paths,key=lambda p:p.relative_to(ROOT).as_posix())

def digest_inputs(paths):
    digest=hashlib.sha256()
    for path in paths:digest.update(path.relative_to(ROOT).as_posix().encode()+b'\0'+hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()

def frontend_inputs():
    return inputs(['Frontend/src','Frontend/public'],['Frontend/index.html','Frontend/vite.config.ts',
        'Frontend/vitest.config.ts','Frontend/tsconfig.json','Frontend/tsconfig.app.json','Frontend/tsconfig.node.json',
        'Frontend/package.json','Frontend/package-lock.json'])

def fingerprint():
    paths=inputs(['Backend/src/main','Backend/src/test','Frontend/src','Frontend/public','Agent','NativeIO','scripts','packaging','licenses','docs','.github'],
        ['Backend/pom.xml','Frontend/package.json','Frontend/package-lock.json','release-version.json',
        'Frontend/index.html','Frontend/vite.config.ts','Frontend/vitest.config.ts','Frontend/tsconfig.json',
        'Frontend/tsconfig.app.json','Frontend/tsconfig.node.json','LICENSE','NOTICE','THIRD_PARTY_NOTICES.md',
        'README.md','SECURITY.md','CONTRIBUTING.md','release-policy.json','security-vex.json','.gitattributes','.gitignore'])
    # Agent build artifacts are not source inputs; packaging/linux/bin is source and stays included.
    return digest_inputs([p for p in paths if not p.is_relative_to(ROOT/'Agent/bin') and not p.is_relative_to(ROOT/'Backend/src/main/resources/native-io')])

def frontend_fingerprint():
    return digest_inputs(frontend_inputs())

def stamp():
    version=json.loads((ROOT/'release-version.json').read_text())
    identity={**version,'sourceSha256':fingerprint()}
    (ROOT/IDENTITY).write_text(json.dumps(identity,sort_keys=True)+'\n')
    return identity

def verify(jar):
    with zipfile.ZipFile(jar) as archive:
        identity=json.loads(archive.read('BOOT-INF/classes/build-identity.json'))
        if identity['sourceSha256']!=fingerprint():raise ValueError('JAR source identity is stale; rebuild and repeat verification')
        if 'BOOT-INF/classes/com/oadig/syncsrv/modules/archive/ArchiveService.class' not in archive.namelist():raise ValueError('Archive module absent from JAR')
        frontend=json.loads(archive.read('BOOT-INF/classes/static/frontend-identity.json'))
        if frontend['sourceSha256']!=frontend_fingerprint():raise ValueError('Compiled frontend source is stale')
        actual={name.removeprefix('BOOT-INF/classes/static/'):name for name in archive.namelist()if name.startswith('BOOT-INF/classes/static/')and not name.endswith('/')and not name.endswith('/frontend-identity.json')}
        if set(actual)!=set(frontend['assets']):raise ValueError('Frontend asset inventory differs')
        for name,hash in frontend['assets'].items():
            if hashlib.sha256(archive.read(actual[name])).hexdigest()!=hash:raise ValueError('Compiled frontend asset differs: '+name)
        settings=archive.read('BOOT-INF/classes/application.yml').decode()
        if re.search(r'username:\s*[\'\"]?root',settings) or re.search(r'password:\s*[\'\"]?[^$\s\'\"]',settings):raise ValueError('Distribution contains a non-placeholder database credential')
        if 'application-private.yml' in '\n'.join(archive.namelist()):raise ValueError('Private deployment configuration was packaged')
    return identity

def evidence(jar,report):
    identity=verify(jar)
    result=json.loads(report.read_text())
    required=['backend','frontend','agentRace','protocolE2E','h2Recovery','packaging','linuxNative','dependencyAudit']
    if result.get('sourceSha256')!=identity['sourceSha256'] or result.get('jarSha256')!=hashlib.sha256(jar.read_bytes()).hexdigest():raise ValueError('Verification evidence does not match exact source/JAR bytes')
    if time.time()-result.get('verifiedAt',0)>86400:raise ValueError('Verification evidence expired')
    if any(result.get(name)!='PASS' for name in required):raise ValueError('Required release checks are incomplete')
    return identity

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('action',choices=['stamp','verify']);p.add_argument('--jar',type=Path,default=ROOT/'Backend/target/syncsrv.jar');a=p.parse_args()
    print(json.dumps(stamp() if a.action=='stamp' else verify(a.jar),indent=2))
