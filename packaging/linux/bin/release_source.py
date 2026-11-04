"""RelsHub is a discovery index; the installed Java verifier authenticates native metadata."""
import ipaddress
import hashlib
import json
import posixpath
import re
from urllib.parse import urlsplit, urljoin

SIDECAR = 'syncsrv-center-release.json'


def origin(url):
    p = urlsplit(url)
    if p.scheme not in ('http', 'https') or not p.hostname or p.username or p.password or p.fragment:
        raise ValueError('Credential-free HTTP(S) URL required')
    return p.scheme, p.hostname.lower(), p.port or (443 if p.scheme == 'https' else 80)


def validate_url(url, allow_http_origin=None):
    actual = origin(url)
    if actual[0] == 'https':
        return
    if not allow_http_origin or actual != origin(allow_http_origin):
        raise ValueError('HTTPS required; intranet HTTP requires an explicit exact origin')
    allowed = urlsplit(allow_http_origin)
    if allowed.path not in ('', '/') or allowed.query:
        raise ValueError('HTTP exception must be a site origin')
    address = ipaddress.ip_address(actual[1])
    private = [ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '127.0.0.0/8', '::1/128', 'fc00::/7')]
    if not any(address in network for network in private if address.version == network.version):
        raise ValueError('HTTP exception only permits literal intranet or loopback IPs')


def feed_channel(url):
    parsed = urlsplit(url)
    match = re.fullmatch(r'/api/v1/projects/syncsrv/channels/(stable|beta)/manifest', parsed.path)
    if not match or parsed.query not in ('os=linux&arch=x64', 'arch=x64&os=linux'):
        raise ValueError('Expected syncsrv stable/beta linux/x64 channel feed')
    origin(url)
    return match[1]


def artifact_url(url, document, row):
    channel = feed_channel(url)
    release_id = document.get('releaseId', '')
    if not re.fullmatch(r'[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}', release_id):
        raise ValueError('Invalid RelsHub release ID')
    location = urljoin(url, row.get('url', ''))
    parsed = urlsplit(location)
    if origin(location) != origin(url) or parsed.query or parsed.fragment or posixpath.normpath(parsed.path) != parsed.path:
        raise ValueError('Artifact URL escapes configured origin')
    api = rf'/api/v1/projects/syncsrv/channels/{channel}/releases/{release_id}/artifacts/[1-9][0-9]*'
    public = rf'/downloads/releases/(?:syncsrv/)?{release_id}/files/[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*'
    if not re.fullmatch(api + '|' + public, parsed.path):
        raise ValueError('Artifact channel/release ID differs')
    return location


def resolve(url, manifest, directory, fetch, verify, verify_index=None):
    document = json.loads(manifest.read_text())
    if 'payload' in document:
        return verify(manifest)
    channel = feed_channel(url)
    if verify_index is None:
        raise ValueError('RelsHub requires the matching installed index verifier; upgrade scripts and verifier together')
    verify_index(manifest)  # Authenticate canonical spec and descriptor rows before fetching anything else.
    spec = document.get('spec', {})
    if (spec.get('project'), spec.get('os'), spec.get('arch')) != ('syncsrv', 'linux', 'x64'):
        raise ValueError('Unexpected RelsHub project/platform')
    rows = document.get('files', [])
    candidates = [f for f in rows if f.get('name') == SIDECAR]
    if len(candidates) != 1:
        raise ValueError('RelsHub must contain one native SyncSrv signed manifest')
    sidecar = candidates[0]
    location = artifact_url(url, document, sidecar)
    native = directory / SIDECAR
    fetch(location, native, 131072)
    raw = native.read_bytes()
    if len(raw) != sidecar.get('size') or hashlib.sha256(raw).hexdigest() != sidecar.get('sha256'):
        raise ValueError('Native manifest size/SHA-256 differs from signed index')
    release = verify(native)
    if release.get('product') != 'SyncSrv' or release.get('target') != 'linux-x64' or release.get('channel') != channel:
        raise ValueError('Signed publisher product/platform/channel differs')
    if release['version'] != spec.get('version') or spec.get('targetSchema') != 1 or not spec.get('rollbackAllowed') or spec.get('intermediate'):
        raise ValueError('RelsHub index and signed publisher version/schema differ')
    if any(release.get(k) != spec.get(k) for k in ('sourceMin', 'sourceMax', 'schemaMin', 'schemaMax')):
        raise ValueError('Signed publisher and RelsHub compatibility ranges differ')
    asset = release['asset']
    repository = urlsplit(asset['repository'])
    if release.get('distribution') != 'relshub-v1' or origin(asset['repository']) != origin(url) or repository.path not in ('', '/') or repository.query:
        raise ValueError('Signed release is not bound to the configured repository')
    full = [f for f in rows if f.get('kind') == 'FULL']
    if len(full) != 1 or any(full[0].get(k) != asset[k] for k in ('name', 'size', 'sha256')):
        raise ValueError('RelsHub index and publisher package digest differ')
    if asset['name'] != f"syncsrv-{release['version']}-linux-x64.tar.gz":
        raise ValueError('Signed package name/platform differs')
    package_url = artifact_url(url, document, full[0])
    release['asset']['url'] = package_url
    return release


def compatible(release, installed):
    def version(text):
        if not isinstance(text, str) or not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', text):
            raise ValueError('Stable version required')
        return tuple(map(int, text.split('.')))
    current, target = version(installed), version(release['version'])
    if current == target:
        return False
    if target < current:
        raise ValueError('Downgrade rejected; use explicit rollback')
    for key, check in [('sourceMin', lambda value: current >= value), ('sourceMax', lambda value: current <= value)]:
        if release.get(key) and not check(version(release[key])):
            raise ValueError('Installed version is outside signed upgrade source range')
    return True
