#!/usr/bin/env python3
"""Build complete interactive installers; publish latest only after every target succeeds."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
import datetime
from release_sign import sign, verify
from release_gate import fingerprint

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument('--go', default='go')
parser.add_argument('--output', type=Path, default=root / 'artifacts/agent-releases')
args = parser.parse_args()
verify(sign({"product":"publisher-key-probe"}),json.loads((root/"Agent/release-trust.json").read_text()))
expected=json.loads((root/'packaging/go-toolchain.lock.json').read_text())['version']
actual=subprocess.check_output([args.go,'env','GOVERSION'],text=True).strip()
if actual != expected: raise SystemExit(f'Use pinned safety toolchain {expected}, found {actual}')
version = re.search(r'const version = "([^"]+)"', (root/'Agent/main.go').read_text()).group(1)
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
final = output/version
if final.exists():
    raise SystemExit(f'Release already exists: {final}. Increment the version; published artifacts are immutable.')
targets = [(system, arch) for system in ('linux', 'windows') for arch in ('amd64', 'arm64')]
if platform.system() == 'Darwin': targets += [('darwin', 'amd64'), ('darwin', 'arm64')]

def checksum(path):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    path.with_name(path.name+'.sha256').write_text(f'{digest}  {path.name}\n')
    return digest

with tempfile.TemporaryDirectory(prefix='.build-', dir=output) as temporary:
    destination = Path(temporary)
    assets = []
    for system, arch in targets:
        base = f'syncsrv-agent-{system}-{arch}'
        name = base + ('.exe' if system == 'windows' else '')
        binary = destination/name
        environment = {**os.environ, 'GOOS':system, 'GOARCH':arch, 'CGO_ENABLED':'1' if system == 'darwin' else '0'}
        if system == 'darwin':
            environment['MACOSX_DEPLOYMENT_TARGET'] = '12.0'
            environment['CGO_CFLAGS'] = '-mmacosx-version-min=12.0'
            environment['CGO_LDFLAGS'] = '-mmacosx-version-min=12.0'
        subprocess.run([args.go,'build','-buildvcs=false','-trimpath','-ldflags=-s -w -buildid=','-o',str(binary),'.'],
                       cwd=root/'Agent',env=environment,check=True)
        if system == 'darwin':
            metadata=subprocess.check_output(['otool','-l',str(binary)],text=True)
            match=re.search(r'minos\s+(\d+\.\d+)',metadata)
            if not match or match.group(1)!='12.0': raise ValueError('Unexpected macOS deployment minimum')
        digest = checksum(binary)
        with tempfile.TemporaryDirectory(prefix='agent-package-') as scratch:
            bundle = Path(scratch)
            shutil.copyfile(binary,bundle/('syncsrv-agent.exe' if system == 'windows' else 'syncsrv-agent'))
            for source in (root/'Agent/tools').iterdir():
                if source.suffix == '.command' and system != 'darwin': continue
                if (source.suffix in ('.ps1','.bat')) == (system == 'windows'):
                    shutil.copyfile(source,bundle/source.name)
            installer = 'install.ps1' if system == 'windows' else 'install.sh'
            shutil.copyfile(root/'Backend/src/main/resources/agent'/installer,bundle/('service-'+installer))
            for source in (root/'Agent/installer').iterdir():
                if (source.suffix in ('.ps1','.bat')) == (system == 'windows'):
                    if source.suffix != '.command' or system == 'darwin':
                        shutil.copyfile(source,bundle/source.name)
            (bundle/'package-info').write_text(f'{version}\n{system}\n{arch}\n')
            shutil.copyfile(root/'docs/Agent安装与升级-0.16.md',bundle/'README.md')
            shutil.copyfile(root/'docs/设备执行与群晖安装-0.11.md',bundle/'DEVICE-EXECUTION.md')
            shutil.copyfile(root/'docs/源端并发与快速启动-0.12.md',bundle/'PARALLEL-TRANSFER.md')
            shutil.copyfile(root/'docs/小文件传输与自动并发-0.13.md',bundle/'SMALL-FILE-TRANSFER.md')
            shutil.copyfile(root/'docs/Windows目录删除与监听修复-0.14.md',bundle/'WINDOWS-WATCH-FIX.md')
            shutil.copyfile(root/'docs/Agent-0.16发布说明.md',bundle/'RELEASE-NOTES.md')
            shutil.copyfile(root/'docs/全局并发与平台排查-20260919.md',bundle/'CONCURRENCY.md')
            shutil.copyfile(root/'docs/Linux-macOS监听并发排查-20260919.md',bundle/'UNIX-WATCH.md')
            shutil.copyfile(root/'docs/Agent安装与升级-0.16.md',bundle/'UPGRADE.md')
            checks = []
            for file in sorted(bundle.iterdir()):
                if file.suffix == '.ps1':
                    # Windows PowerShell 5.1 needs BOM for Chinese prompts.
                    file.write_text(file.read_text(encoding='utf-8-sig'), encoding='utf-8-sig')
                if file.suffix == '.bat':
                    file.write_bytes(file.read_text().replace('\r\n','\n').replace('\n','\r\n').encode('utf-8'))
                file.chmod(0o755 if file.name == 'syncsrv-agent' or file.suffix in ('.sh','.command') else 0o644)
                checks.append(hashlib.sha256(file.read_bytes()).hexdigest()+'  '+file.name)
            (bundle/'SHA256SUMS').write_text('\n'.join(checks)+'\n')
            package = destination/(base+('.zip' if system == 'windows' else '.tar.gz'))
            if system == 'windows':
                with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as archive:
                    for file in sorted(bundle.iterdir()): archive.write(file,file.name)
            else:
                with tarfile.open(package,'w:gz') as archive:
                    for file in sorted(bundle.iterdir()): archive.add(file,arcname=file.name)
        assets.append(dict(platform=system,arch=arch,filename=name,sha256=digest,size=binary.stat().st_size,
                           packageName=package.name,packageSha256=checksum(package),packageSize=package.stat().st_size))
        print(f'Built {name} + {package.name}',flush=True)
    import importlib.util
    spec = importlib.util.spec_from_file_location('synology_builder', root/'scripts/build-synology.py')
    synology = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(synology)
    for arch in ('amd64','arm64'):
        assets.append(synology.build(root, destination, version, arch))
        print(f'Built native DSM SPK: {arch}', flush=True)
    now = datetime.datetime.now(datetime.timezone.utc)
    payload = dict(product='SyncSrvAgent',version=version,assets=assets,channel='stable',schema=1,
                   minCenterVersion='0.4.0',sourceSha256=fingerprint(),issuedAt=now.isoformat().replace('+00:00','Z'),
                   expiresAt=(now+datetime.timedelta(days=365)).isoformat().replace('+00:00','Z'))
    (destination/'release.json').write_text(json.dumps({**payload, **sign(payload)},indent=2)+'\n')
    destination.chmod(0o755)
    destination.rename(final)
pointer=output/'.latest.tmp'
pointer.write_text(version+'\n')
pointer.replace(output/'latest.txt')
print('Published complete installation release: '+version)
