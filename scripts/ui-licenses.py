#!/usr/bin/env python3
"""Collect the complete notices for locked UI dependencies; unknown terms fail closed."""
import argparse, hashlib, json
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
FALLBACKS = {'@ant-design/icons-svg': 'ant-design__icons-svg'}
BITS = ['LoginAurora.tsx', 'LoginBorderGlow.tsx', 'LoginBorderGlow.css']

def verify_review(root, packages, bits_text):
    review = json.loads((root / 'licenses/ui-reviewed.json').read_text())
    if review.get('schema') != 1:
        raise ValueError('Unsupported UI license review schema')
    approved = {(p['name'], p['version']): p for p in review['npmPackages']}
    for package in packages:
        prior = approved.get((package['name'], package['version']))
        if not prior or any(prior.get(key) != package[key] for key in ('license', 'licenseSha256')):
            raise ValueError('New or changed UI dependency needs license review: ' + package['name'])
    bits = review['reactBits']
    if bits.get('files') != BITS or bits.get('licenseSha256') != hashlib.sha256(bits_text.encode()).hexdigest():
        raise ValueError('React Bits license/source scope changed; review before building')
    return review

def collect(root):
    frontend = root / 'Frontend'
    lock = json.loads((frontend / 'package-lock.json').read_text())
    packages = {}
    for location, entry in sorted(lock['packages'].items()):
        if not location.startswith('node_modules/') or entry.get('dev'):
            continue
        folder = frontend / location
        installed = json.loads((folder / 'package.json').read_text())
        name, version = installed['name'], installed['version']
        if version != entry.get('version') or entry.get('license', installed.get('license')) != 'MIT' or installed.get('license') != 'MIT':
            raise ValueError('UI license/version needs review: ' + name)
        files = sorted(p for p in folder.iterdir() if p.is_file() and p.name.lower().startswith(('license', 'licence', 'copying', 'notice')))
        texts = [p.read_text(errors='strict') for p in files]
        source = 'installed package: ' + location
        if not texts and (folder / 'README.md').is_file():
            readme = (folder / 'README.md').read_text()
            if '## License' in readme:
                section = readme.split('## License', 1)[1].split('\n## ', 1)[0]
                if 'Permission is hereby granted' in section:
                    texts = [section.strip()]
                    source = 'installed package README.md license section: ' + location
        if not texts:
            stem = FALLBACKS.get(name)
            if not stem:
                raise ValueError('Missing UI license text: ' + name)
            metadata = json.loads((root / 'licenses/upstream' / (stem + '-metadata.json')).read_text())
            if metadata['package'] != name or metadata['version'] != version or metadata['license'] != 'MIT' or not metadata.get('gitHead'):
                raise ValueError('Upstream license evidence is stale: ' + name)
            texts = [(root / 'licenses/upstream' / (stem + '-MIT.txt')).read_text()]
            source = metadata['licenseUrl']
        text = '\n\n'.join(texts)
        if 'Permission is hereby granted' not in text or 'copyright notice' not in text.lower():
            raise ValueError('Incomplete MIT notice: ' + name)
        key = (name, version)
        if key in packages:
            if packages[key]['licenseSha256'] != hashlib.sha256(text.encode()).hexdigest():
                raise ValueError('Conflicting license text: ' + name)
            packages[key]['installedPaths'].append(location)
            continue
        packages[key] = dict(name=name, version=version, license='MIT', freeCommercialUse=True,
            conditions=['Retain copyright and complete permission/disclaimer notices in source and distributions.'],
            installedPaths=[location], evidence=source, licenseSha256=hashlib.sha256(text.encode()).hexdigest(), text=text)
    bits_path = root / 'Frontend/public/licenses/react-bits.txt'
    bits_text = bits_path.read_text()
    if 'Commons Clause' not in bits_text or 'Copyright (c) 2026 David Haz' not in bits_text:
        raise ValueError('React Bits original terms must be retained')
    for name in BITS:
        path = root / 'Frontend/src/modules/auth' / name
        if 'LicenseRef-ReactBits-MIT-Commons-Clause' not in path.read_text():
            raise ValueError('Missing UI license exception: ' + name)
    components = list(packages.values())
    review = verify_review(root, components, bits_text)
    inventory = dict(schema=1, reviewDate=review['reviewedAt'],
        reviewPolicy='licenses/ui-reviewed.json', npmPackages=[{k:v for k,v in p.items() if k!='text'} for p in components],
        applicationComponents=[dict(name='React Bits free application effects', files=BITS,
            license='LicenseRef-ReactBits-MIT-Commons-Clause', freeCommercialUse=True,
            conditions=['Use only as part of the application or website.', 'Retain the original license and David Haz copyright.',
                'Do not relicense, extract or redistribute as a standalone reusable component offering.', 'React Bits Pro is not covered.'],
            evidence=[review['reactBits']['licenseUrl'], 'https://github.com/DavidHDev/react-bits/discussions/1111', 'https://github.com/DavidHDev/react-bits/discussions/1101'],
            licenseSha256=hashlib.sha256(bits_text.encode()).hexdigest())])
    notice = ['SyncSrv UI third-party licenses', 'Original SyncSrv code: Apache-2.0 (see LICENSE and NOTICE).',
        'Third-party code keeps its own license. Free use does not remove attribution or redistribution conditions.']
    for p in components:
        notice.extend(['\n' + '='*72, p['name']+' '+p['version']+' — MIT', p['evidence'], p['text']])
    notice.extend(['\n'+'='*72, 'React Bits free application effects — MIT + Commons Clause',
        'Application-only use; excluded from the project Apache-2.0 license.', bits_text,
        'Maintainer clarification: https://github.com/DavidHDev/react-bits/discussions/1111'])
    return inventory, '\n\n'.join(notice)+'\n'

def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--check', action='store_true'); parser.add_argument('--root',type=Path,default=ROOT)
    args = parser.parse_args(); root=args.root.resolve(); inventory, notice = collect(root)
    outputs = {root/'licenses/ui-inventory.json':json.dumps(inventory,ensure_ascii=False,indent=2)+'\n',
        root/'Frontend/public/licenses/ui-third-party.txt':notice}
    for path, content in outputs.items():
        if args.check:
            if not path.is_file() or path.read_text()!=content:raise ValueError('UI notices need regeneration: '+str(path.relative_to(root)))
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    print('UI license check:',len(inventory['npmPackages']),'MIT package versions + React Bits application exception; no paid UI package found.')
if __name__=='__main__':main()
