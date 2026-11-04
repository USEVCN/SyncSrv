#!/bin/sh
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "$(id -u)" != 0 ]; then exec sudo sh "$script_dir/uninstall.sh"; fi
exec sh "$script_dir/agent-control.sh" uninstall
