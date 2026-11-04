#!/usr/bin/env python3
"""Explicit SyncSrv build-side signing/publication. Never controls an installed service."""
import argparse
import base64
from datetime import datetime, timezone, timedelta
import getpass
import hashlib
import json
import os
from pathlib import Path
import tarfile
from urllib.parse import urlencode
from cryptography.hazmat.primitives import serialization
from relshub_client import Api, SCOPES, SERVICE, atomic_json, binding, canonical, keychain, project, token_for
from release_sign import sign, verify

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PROFILE = ROOT / 'work/relshub/profile.json'
SIDECAR = 'syncsrv-center-release.json'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''): h.update(block)
    return h.hexdigest()


def signing_key(profile):
    path = Path(profile['privateKey'])
    if path.stat().st_mode & 0o077: raise ValueError('Publisher private key must have mode 0600')
    key = serialization.load_pem_private_key(path.read_bytes(), None)
    public = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    if hashlib.sha256(public).hexdigest() != profile['publicKeySha256']: raise ValueError('Publisher key fingerprint differs')
    trust = json.loads((ROOT / 'Backend/src/main/resources/release-trust.json').read_text())
    if base64.b64decode(trust[profile['keyId']]) != key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw): raise ValueError('Publisher key differs from installed application trust')
    return key


def status(profile):
    signing_key(profile); api = Api(profile, token_for(profile)); p = project(api)
    session = api.request('/api/session')
    if session.get('administrator') is not False or len(api.request('/api/projects')) != 1: raise ValueError('Expected a single-project non-administrator token')
    return {'ready': True, 'project': p['code'], 'projectId': p['id'], 'url': profile['url'], 'credentialStorage': 'macOS Keychain', 'expiresAt': profile['credential']['expiresAt'], 'channels': api.request(f'/api/projects/{p["id"]}/channels')}


def setup(profile, path, username):
    signing_key(profile)
    if profile.get('credential') and datetime.fromisoformat(profile['credential']['expiresAt'].replace('Z', '+00:00')) > datetime.now(timezone.utc): return status(profile)
    password = getpass.getpass('RelsHub existing administrator password (not saved): ')
    try: api = Api(profile, username=username, password=password)
    finally: password = None
    p = project(api)
    created = api.request(f'/api/projects/{p["id"]}/tokens', 'POST', {'scopes': SCOPES, 'days': 90})
    token = created['token']; keychain(profile, token)
    bound = binding(profile)
    profile['credential'] = {'service': SERVICE, 'account': 'project-' + bound, 'bindingSha256': bound, 'scopes': SCOPES, 'expiresAt': created['expiresAt']}
    atomic_json(path, profile)
    return status(profile)


def publish(profile, package, notes_file, source_min, source_max, channel='stable', register_only=False):
    key = signing_key(profile); api = Api(profile, token_for(profile)); p = project(api); base = f'/api/projects/{p["id"]}'
    if channel not in ('stable', 'beta'): raise ValueError('Unknown release channel')
    notes = notes_file.read_text().strip()
    if len(notes) < 30: raise ValueError('Actual release notes required')
    # An already signed exact archive is mandatory, even if the server proposes another build.
    original = package.with_name(package.name.removesuffix('.tar.gz') + '.signed.json')
    trust = json.loads((ROOT / 'Backend/src/main/resources/release-trust.json').read_text())
    prior = verify(json.loads(original.read_text()), trust); sha = digest(package)
    if prior['asset']['sha256'] != sha or prior['asset']['size'] != package.stat().st_size or prior['product'] != 'SyncSrv': raise ValueError('Archive differs from the signed candidate')
    if prior.get('target') != 'linux-x64' or any(prior.get(k) != 1 for k in ('schema', 'schemaMin', 'schemaMax')):
        raise ValueError('Only the signed linux-x64 schema-1 center distribution may be published')
    version = prior['version']
    if package.name != f'syncsrv-{version}-linux-x64.tar.gz': raise ValueError('Archive name/version differs')
    import sys
    sys.path.insert(0, str(ROOT / 'packaging/linux/bin'))
    from release_source import compatible
    if not compatible({'version': version, 'sourceMin': source_min, 'sourceMax': source_max}, source_min): raise ValueError('Target must exceed source version')
    identity = hashlib.sha256(json.dumps({'sha256': sha, 'notes': notes, 'sourceMin': source_min, 'sourceMax': source_max, 'channel': channel, 'origin': profile['url']}, sort_keys=True).encode()).hexdigest()
    folder = Path(profile['receipts']) / identity; folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    receipt = folder / 'receipt.json'; state = json.loads(receipt.read_text()) if receipt.exists() else {}
    def save(**value): state.update(value); atomic_json(receipt, state)
    number = 'syncsrv-' + identity[:24]
    def lookup(): return api.request(base + '/automation/lookup?' + urlencode({'version': version, 'buildNumber': number}))
    if 'expectedRevision' not in state:
        head = next((h for h in api.request(base + '/channels') if h['name'] == channel and h['os'] == 'linux' and h['arch'] == 'x64'), None)
        save(expectedRevision=head['revision'] if head else 0)
    found = [v for v in lookup()['versions'] if v['version'] == version]
    if not found:
        api.request(base + '/versions', 'POST', {'version': version, 'notes': notes, 'commit': prior['sourceSha256']})
        found = [v for v in lookup()['versions'] if v['version'] == version]
    if len(found) != 1 or found[0]['notes'] != notes: raise ValueError('Immutable version notes differ')
    rules = {'os': 'linux', 'arch': 'x64', 'sourceMin': source_min, 'sourceMax': source_max, 'intermediate': None, 'schemaMin': 1, 'schemaMax': 1, 'targetSchema': 1, 'rollbackAllowed': True}
    builds = [b for b in lookup()['builds'] if b['build_number'] == number]
    if not builds:
        api.request(base + '/builds', 'POST', {'versionId': found[0]['id'], 'buildNumber': number, **rules})
        builds = [b for b in lookup()['builds'] if b['build_number'] == number]
    if len(builds) != 1: raise ValueError('Build identity differs')
    build = base + '/builds/' + str(builds[0]['id'])
    def upload(file, kind):
        rows = api.request(build + '/artifacts'); matching = [f for f in rows if f['original_name'] == file.name]
        valid = [f for f in matching if f['sha256'] == digest(file) and f['size_bytes'] == file.stat().st_size and f['state'] in ('HASH_VERIFIED', 'VERIFIED')]
        if len(valid) == 1: return valid[0]
        if matching: raise ValueError('Same artifact name is incomplete or has different content')
        api.request(build + '/artifacts?' + urlencode({'kind': kind, 'name': file.name}), 'POST', file=file, headers={'X-Artifact-Size': str(file.stat().st_size), 'X-Artifact-SHA256': digest(file)})
        return next(f for f in api.request(build + '/artifacts') if f['original_name'] == file.name and f['sha256'] == digest(file))
    artifact = upload(package, 'FULL')
    # Native trust binds repository origin, name, size and hash before using index download paths.
    native_path = folder / SIDECAR
    if not native_path.exists():
        now = datetime.now(timezone.utc)
        payload = {**prior, 'channel': channel, 'sourceMin': source_min, 'sourceMax': source_max, 'issuedAt': now.isoformat().replace('+00:00', 'Z'), 'expiresAt': (now + timedelta(days=365)).isoformat().replace('+00:00', 'Z')}
        payload['distribution'] = 'relshub-v1'
        payload['asset'] = {'repository': profile['url'], 'name': package.name, 'sha256': sha, 'size': package.stat().st_size}
        raw = json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
        atomic_json(native_path, {'payload': base64.b64encode(raw).decode(), 'signature': base64.b64encode(key.sign(raw)).decode(), 'keyId': profile['keyId']})
    upload(native_path, 'INSTALLER')
    plan = api.request(build + '/manifest?' + urlencode({'keyId': profile['keyId']})); spec = plan['spec']
    expected = {'project': 'syncsrv', 'version': version, 'buildNumber': number, 'notes': notes, 'keyId': profile['keyId'], **rules}
    files = [{'name': path.name, 'kind': kind, 'sourceVersion': None, 'size': path.stat().st_size, 'sha256': digest(path)} for path, kind in [(package, 'FULL'), (native_path, 'INSTALLER')]]
    if any(spec.get(k) != v for k, v in expected.items()) or spec['files'] != sorted(files, key=lambda f: f['name']) or base64.b64decode(plan['canonicalBase64'], validate=True) != canonical(spec): raise ValueError('Server plan differs from local release input')
    release = api.request(build + '/publish', 'POST', {'requestKey': identity, 'keyId': profile['keyId'], 'signature': base64.b64encode(key.sign(canonical(spec))).decode()})['releaseId']
    save(releaseId=release, version=version, sha256=sha, status='published', buildNumber=number)
    api.request(base + '/releases/' + release + '/recover', 'POST')
    if not register_only:
        heads = api.request(base + '/channels'); head = next((h for h in heads if h['name'] == channel and h['os'] == 'linux' and h['arch'] == 'x64'), None)
        if head is None:
            api.request(base + f'/channels/{channel}/heads', 'POST', {'os': 'linux', 'arch': 'x64'})
            head = next(h for h in api.request(base + '/channels') if h['name'] == channel and h['os'] == 'linux' and h['arch'] == 'x64')
        if head['release_id'] != release:
            if head['revision'] != state['expectedRevision']: raise ValueError('Channel revision changed; refusing to overwrite another publisher')
            result = api.request(base + f'/channels/{channel}/heads', 'PUT', {'os': 'linux', 'arch': 'x64', 'releaseId': release, 'revision': head['revision']})
            if result.get('state') != 'READY': raise ValueError('Release projection is pending; recover and recheck')
        save(status='recommended', manifestUrl=profile['url'] + f'/api/v1/projects/syncsrv/channels/{channel}/manifest?os=linux&arch=x64')
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['setup', 'status', 'publish'])
    parser.add_argument('--profile', type=Path, default=DEFAULT_PROFILE)
    parser.add_argument('--url'); parser.add_argument('--allow-http-origin')
    parser.add_argument('--username', default='admin'); parser.add_argument('--package', type=Path); parser.add_argument('--notes', type=Path)
    parser.add_argument('--from-version'); parser.add_argument('--source-max'); parser.add_argument('--channel', default='stable'); parser.add_argument('--register-only', action='store_true')
    args = parser.parse_args()
    if not args.profile.exists() and args.command == 'setup':
        if not args.url: parser.error('First setup requires --url')
        private = ROOT / 'config/release-signing-private.pem'
        key = serialization.load_pem_private_key(private.read_bytes(), None)
        public = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
        profile = {'url': args.url.rstrip('/'), 'allowHttp': args.allow_http_origin == args.url.rstrip('/'), 'project': 'syncsrv', 'keyId': 'syncsrv-2026', 'privateKey': str(private), 'publicKeySha256': hashlib.sha256(public).hexdigest(), 'receipts': str(ROOT / 'work/relshub/receipts')}
        binding(profile); atomic_json(args.profile,profile)
    profile = json.loads(args.profile.read_text()); binding(profile)
    if args.url and args.url.rstrip('/') != profile['url']: raise ValueError('Changing credential origin requires a new profile')
    if args.command == 'setup': result = setup(profile, args.profile, args.username)
    elif args.command == 'status': result = status(profile)
    else:
        if not all([args.package, args.notes, args.from_version]): parser.error('publish requires --package --notes --from-version')
        result = publish(profile, args.package, args.notes, args.from_version, args.source_max or args.from_version, args.channel, args.register_only)
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    try: main()
    except (ValueError, RuntimeError) as error: raise SystemExit(str(error))
