#!/bin/sh
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sh "$script_dir/install.sh"
result=$?
printf '\n按回车关闭此窗口…'
read -r answer
exit "$result"
