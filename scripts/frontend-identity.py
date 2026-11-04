#!/usr/bin/env python3
"""Record compiled frontend bytes and their source identity after Vite succeeds."""
import hashlib,json
from pathlib import Path
from release_gate import frontend_fingerprint
root=Path(__file__).resolve().parent.parent
static=root/'Backend/src/main/resources/static'
if not (static/'index.html').is_file():raise SystemExit('Build frontend first')
assets={str(p.relative_to(static)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(static.rglob('*'))if p.is_file()and p.name!='frontend-identity.json'}
(static/'frontend-identity.json').write_text(json.dumps(dict(sourceSha256=frontend_fingerprint(),assets=assets),sort_keys=True)+'\n')
print('Bound frontend source and compiled assets')
