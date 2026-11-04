#!/usr/bin/env python3
"""Build DSM 7.2.2+ native SPK using the statically linked Linux Agent."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import tarfile
import tempfile


def build(root, destination, version, arch):
    base = f'syncsrv-agent-synology-{arch}'
    binary = destination / base
    shutil.copyfile(destination / f'syncsrv-agent-linux-{arch}', binary)
    binary.chmod(0o755)
    with tempfile.TemporaryDirectory(prefix='syncsrv-spk-') as temp:
        stage = Path(temp)
        payload = stage / 'payload'
        payload.mkdir()
        shutil.copyfile(binary, payload / 'syncsrv-agent')
        (payload / 'syncsrv-agent').chmod(0o755)
        shutil.copyfile(root / 'docs/设备执行与群晖安装-0.11.md', payload / 'README.md')
        package = stage / 'package.tgz'
        with tarfile.open(package, 'w:gz', format=tarfile.USTAR_FORMAT) as tar:
            for file in sorted(payload.iterdir()):
                tar.add(file, arcname=file.name)
        spk = stage / 'spk'
        shutil.copytree(root / 'Agent/synology', spk)
        (spk / 'install-wizard.js').unlink()
        shutil.copyfile(package, spk / 'package.tgz')
        wizard = spk / 'WIZARD_UIFILES'
        wizard.mkdir()
        (wizard / 'install_uifile').write_text(json.dumps([dict(
            custom_render_name='syncsrv_install',
            custom_render_fn=(root/'Agent/synology/install-wizard.js').read_text())], ensure_ascii=False))
        numeric = re.match(r'\d+\.\d+\.\d+', version).group()
        family = 'x86_64' if arch == 'amd64' else 'armv8'
        major, minor, patch = map(int, numeric.split('.'))
        build_number = major * 1000000 + minor * 1000 + patch
        info = dict(package='SyncSrvAgent', version=f'{numeric}-{build_number}',
                    displayname='SyncSrv Backup Agent', maintainer='SyncSrv',
                    description='One device, one Agent. Local backup, archive and restore execution controlled by SyncSrv.',
                    arch=family, os_min_ver='7.2.2-72806', startable='yes',
                    beta='yes', thirdparty='yes', install_type='volume',
                    silent_install='no', silent_upgrade='yes', silent_uninstall='yes',
                    checksum=hashlib.md5(package.read_bytes()).hexdigest())
        (spk / 'INFO').write_text(''.join(f'{key}="{value}"\n' for key,value in info.items()))
        for script in (spk / 'scripts').iterdir():
            script.chmod(0o755)
        output = destination / f'{base}.spk'
        with tarfile.open(output, 'w', format=tarfile.USTAR_FORMAT) as tar:
            for file in sorted(spk.iterdir()): tar.add(file, arcname=file.name)
    def checksum(file):
        digest = hashlib.sha256(file.read_bytes()).hexdigest()
        file.with_name(file.name+'.sha256').write_text(f'{digest}  {file.name}\n')
        return digest
    return dict(platform='synology', arch=arch, filename=binary.name, sha256=checksum(binary),
                size=binary.stat().st_size, packageName=output.name,
                packageSha256=checksum(output), packageSize=output.stat().st_size)
