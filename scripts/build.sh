#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
python3 scripts/check-source.py
python3 scripts/build-native-io.py --go "${SYNCSRV_GO:-go}"
(cd Frontend && npm ci && npm run build)
python3 scripts/release_gate.py stamp
(cd Backend && mvn -q clean verify)
printf 'Build complete: Backend/target/syncsrv.jar\n'
