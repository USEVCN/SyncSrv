#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
command -v python3 >/dev/null 2>&1 || { echo 'Python 3.9+ is required for center management.' >&2; exit 1; }
exec python3 "$root/bin/update.py" "${@:-check}"
