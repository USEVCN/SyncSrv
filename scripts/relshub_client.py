"""Origin-bound RelsHub API and macOS Keychain credentials; no secrets in argv/files."""
import base64
from datetime import datetime, timezone
import hashlib
import http.client
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from urllib.parse import urlsplit, urlencode

SERVICE = 'io.relshub.publisher'
SCOPES = ['CHANNEL', 'PUBLISH', 'READ', 'UPLOAD']


def binding(profile):
    parsed = urlsplit(profile['url'])
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.path not in ('', '/') or parsed.query or parsed.fragment:
        raise ValueError('RelsHub must be a credential-free site origin')
    if profile.get('project') != 'syncsrv':
        raise ValueError('This publisher only accepts the syncsrv project')
    if parsed.scheme == 'http':
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'packaging/linux/bin'))
        from release_source import validate_url
        validate_url(profile['url'], profile['url'] if profile.get('allowHttp') else None)
    return hashlib.sha256((profile['url'] + '|syncsrv').encode()).hexdigest()


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(dir=path.parent, prefix='.receipt-')
    try:
        with os.fdopen(fd, 'w') as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def keychain(profile, token=None):
    account = 'project-' + binding(profile)
    if token is not None:
        if not re.fullmatch(r'rh_[A-Za-z0-9_-]{43}', token):
            raise ValueError('Invalid project token format')
        result = subprocess.run(['/usr/bin/security', '-i'], input=f'add-generic-password -U -s {SERVICE} -a {account} -w {token}\n', capture_output=True, text=True)
        if result.returncode:
            raise ValueError('Keychain write failed; no plaintext fallback was used')
    result = subprocess.run(['/usr/bin/security', 'find-generic-password', '-s', SERVICE, '-a', account, '-w'], capture_output=True, text=True)
    if result.returncode or not re.fullmatch(r'rh_[A-Za-z0-9_-]{43}', result.stdout.strip()):
        raise ValueError('Unlock Keychain or run setup in a local Mac terminal')
    return result.stdout.strip()


def token_for(profile):
    bound = binding(profile); c = profile.get('credential', {})
    if c.get('bindingSha256') != bound or c.get('account') != 'project-' + bound or c.get('service') != SERVICE or sorted(c.get('scopes', [])) != SCOPES:
        raise ValueError('Credential origin/project/scopes binding differs; run setup')
    if datetime.fromisoformat(c['expiresAt'].replace('Z', '+00:00')) <= datetime.now(timezone.utc):
        raise ValueError('Project credential expired; run setup')
    return keychain(profile)


class Api:
    def __init__(self, profile, token=None, username=None, password=None):
        binding(profile); self.base = urlsplit(profile['url']); self.token = token
        self.cookies = {}; self.login = True
        if username:
            csrf = self.request('/api/session/csrf')
            self.request('/api/session/login', 'POST', urlencode({'username': username, 'password': password}).encode(), headers={'Content-Type': 'application/x-www-form-urlencoded', csrf['header']: csrf['token']})
        self.login = False

    def request(self, path, method='GET', body=None, file=None, headers=None):
        if not path.startswith('/api/') or path.startswith('//'):
            raise ValueError('API request escaped configured origin')
        connection = (http.client.HTTPSConnection if self.base.scheme == 'https' else http.client.HTTPConnection)(self.base.hostname, self.base.port, timeout=180)
        h = dict(headers or {})
        if self.token: h['Authorization'] = 'Bearer ' + self.token
        elif method != 'GET' and not self.login:
            csrf = self.request('/api/session/csrf'); h[csrf['header']] = csrf['token']
        if self.cookies: h['Cookie'] = '; '.join(k + '=' + v for k, v in self.cookies.items())
        data = file.open('rb') if file else body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        if file: h.update({'Content-Type': 'application/octet-stream', 'Content-Length': str(file.stat().st_size)})
        elif data is not None: h.setdefault('Content-Type', 'application/json')
        try:
            connection.request(method, path, body=data, headers=h)
            response = connection.getresponse()
            for name, value in response.getheaders():
                if name.lower() == 'set-cookie':
                    cookie = SimpleCookie(); cookie.load(value); self.cookies.update({k: v.value for k, v in cookie.items()})
            raw = response.read(2097153)
            if not 200 <= response.status < 300:
                raise RuntimeError(f'RelsHub HTTP {response.status} at {path.split("?")[0]}; retry the same publication identity')
            if len(raw) > 2097152: raise ValueError('API response too large')
            return json.loads(raw) if raw else None
        finally:
            if file: data.close()
            connection.close()


def canonical(spec):
    fields = [spec.get(k) for k in ('project', 'version', 'buildNumber', 'os', 'arch')]
    fields += [hashlib.sha256(spec['notes'].encode()).hexdigest()]
    fields += [spec.get(k) for k in ('sourceMin', 'sourceMax', 'intermediate', 'schemaMin', 'schemaMax', 'targetSchema')]
    fields += [str(spec['rollbackAllowed']).lower(), spec['keyId'], len(spec['files'])]
    for file in sorted(spec['files'], key=lambda item: item['name']):
        fields += [file.get(k) for k in ('name', 'kind', 'sourceVersion', 'size', 'sha256')]
    return ('relshub-release-v1\n' + ''.join('~\n' if v is None else base64.urlsafe_b64encode(str(v).encode()).decode().rstrip('=') + '\n' for v in fields)).encode()


def project(api):
    rows = api.request('/api/projects')
    found = [row for row in rows if row['code'] == 'syncsrv']
    if len(found) != 1: raise ValueError('SyncSrv project is missing or not accessible')
    return found[0]
