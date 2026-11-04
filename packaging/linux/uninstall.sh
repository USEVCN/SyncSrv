#!/bin/sh
set -eu
[ "$(id -u)" -eq 0 ] || { printf '请使用 sudo sh uninstall.sh。\n' >&2; exit 1; }
[ -f /etc/systemd/system/syncsrv.service ] || { printf 'SyncSrv 服务未注册。\n'; exit 0; }
systemctl stop syncsrv.service
systemctl disable syncsrv.service
stamp=$(date +%Y%m%d-%H%M%S)
install -d -m 0700 "/opt/syncsrv/uninstalled-$stamp"
mv /etc/systemd/system/syncsrv.service "/opt/syncsrv/uninstalled-$stamp/syncsrv.service"
systemctl daemon-reload
printf 'SyncSrv 服务已卸载；程序、配置、H2、密钥和所有备份文件均保留。\n'
