#!/usr/bin/env python3
"""Build-time release signing. Private PEM is supplied by the release operator."""
import base64, json, os
from pathlib import Path
from cryptography.hazmat.primitives import serialization

def sign(payload):
    keyfile = Path(os.environ['SYNCSRV_RELEASE_KEY'])
    if keyfile.stat().st_mode & 0o077:
        raise ValueError('Publisher private key must have mode 0600')
    key = serialization.load_pem_private_key(keyfile.read_bytes(), None)
    raw = json.dumps(payload, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
    return dict(payload=base64.b64encode(raw).decode(), signature=base64.b64encode(key.sign(raw)).decode(),
                keyId=os.environ.get('SYNCSRV_RELEASE_KEY_ID', 'syncsrv-2026'))

def verify(envelope, trust):
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    raw = base64.b64decode(envelope['payload'], validate=True)
    key = Ed25519PublicKey.from_public_bytes(base64.b64decode(trust[envelope['keyId']], validate=True))
    key.verify(base64.b64decode(envelope['signature'], validate=True), raw)
    return json.loads(raw)
