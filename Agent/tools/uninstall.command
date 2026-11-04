#!/bin/sh
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sh "$script_dir/uninstall.sh"
result=$?
printf '\nPress Enter to close...'
read -r answer
exit "$result"
