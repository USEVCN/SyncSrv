"""Read source preferences as untrusted data; root policy and publisher signatures stay authoritative."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import shlex
from urllib.parse import urlsplit
import release_source

DEFAULT_FEED = 'https://update.devgo.io:18761/api/v1/projects/syncsrv/channels/stable/manifest?os=linux&arch=x64'


def read_data(path, owner, missing=False, maximum=16384):
    path = Path(path).absolute()
    # Walk opened directories rather than following mutable ancestry or final symlinks.
    directory = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
    try:
        try:
            for part in path.parts[1:-1]:
                next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                os.close(directory)
                directory = next_fd
            descriptor = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        except FileNotFoundError:
            if missing:
                return None
            raise
        try:
            identity = os.fstat(descriptor)
            if not stat.S_ISREG(identity.st_mode) or identity.st_size > maximum or identity.st_uid not in {0, owner} or identity.st_mode & 0o022:
                raise PermissionError('Invalid update source file type, size, owner or write permissions')
            raw = os.read(descriptor, maximum + 1)
            if len(raw) > maximum:
                raise ValueError('Update source file too large')
            return raw
        finally:
            os.close(descriptor)
    finally:
        os.close(directory)


def read_object(path, owner, missing=False):
    raw = read_data(path, owner, missing)
    if raw is None:
        return None
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError('Update source must be an object')
    return value


def preference_path(policy, environment=Path('/etc/syncsrv/syncsrv.env'), owner=0):
    if policy.get('sourceState'):
        if not isinstance(policy['sourceState'], str):
            raise ValueError('Source state path must be text')
        chosen = Path(policy['sourceState'])
    else:
        raw = read_data(environment, owner, missing=True, maximum=65536)
        data = '/var/lib/syncsrv'
        for line in (raw or b'').decode().splitlines():
            assignment = re.sub(r'^export\s+', '', line.strip())
            if assignment.startswith('SYNCSRV_DATA='):
                words = shlex.split(assignment.partition('=')[2], comments=True)
                if len(words) != 1 or any(c in words[0] for c in '$`'):
                    raise ValueError('Dynamic data directory needs an explicit sourceState in root policy')
                data = words[0]
        chosen = Path(data) / 'update-source.json'
    if not chosen.is_absolute():
        raise ValueError('Source state must use an absolute deployment-owned path')
    return chosen


def server_origin(value):
    if not isinstance(value, str) or len(value) > 2048 or not value.isascii() or not re.fullmatch(r'https://[^/?#\s]+/?', value):
        raise ValueError('Only a credential-free HTTPS server origin may be saved')
    parsed = urlsplit(value)
    if not parsed.hostname or parsed.username is not None or parsed.password is not None or parsed.port == 0:
        raise ValueError('Invalid HTTPS origin')
    return 'https://' + parsed.netloc


def manifest_for_server(server, previous):
    channel = release_source.feed_channel(previous)
    return server_origin(server) + f'/api/v1/projects/syncsrv/channels/{channel}/manifest?os=linux&arch=x64'


def policy_manifest(policy):
    manifest = policy.get('manifest') or DEFAULT_FEED
    if not isinstance(manifest, str):
        raise ValueError('Manifest URL must be text')
    return manifest_for_server(policy['serverUrl'], manifest) if 'serverUrl' in policy else manifest


def effective(base, allowed, path, owner=None):
    # The installed system account owns the state. No ownership rule is relaxed in tests.
    if owner is None:
        try:
            owner = pwd.getpwnam('syncsrv').pw_uid
        except KeyError:
            if not Path(path).exists() and not Path(path).is_symlink():
                return base
            raise PermissionError('SyncSrv service account is missing')
    value = read_object(path, owner, missing=True)
    if value is None:
        return base
    expected = hashlib.sha256((base + '\n' + (allowed or '')).encode()).hexdigest()
    if (type(value.get('schema')) is not int or value.get('schema') != 1 or value.get('status') != 'COMMITTED' or value.get('baseRevision') != expected
            or not isinstance(value.get('revision'), str) or not re.fullmatch('[0-9a-f]{32}', value['revision'])):
        raise ValueError('Source state is uncommitted or deployment policy changed; no fallback source selected')
    return manifest_for_server(value.get('serverUrl'), base)
