#!/bin/sh
# Interactive entry point; all executable files are supplied in this package.
set -eu
umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
fail() { printf '\n安装未完成：%s\n' "$*" >&2; exit 1; }
check_only=0
case "${1:-}" in --check) check_only=1 ;; '') ;; *) fail '请直接运行 install.sh，或使用 --check 仅校验安装包。' ;; esac
if [ "$check_only" = 0 ] && [ "$(id -u)" != 0 ]; then
  command -v sudo >/dev/null 2>&1 || fail '请由管理员以 root 身份运行此安装脚本。'
  exec sudo sh "$script_dir/install.sh"
fi
. "$script_dir/install-common.sh"
verify_package
if [ "$check_only" = 1 ]; then printf '安装包校验通过：%s %s %s\n' "$version" "$platform" "$arch"; exit 0; fi
case "$platform" in
  linux)
    command -v systemctl >/dev/null 2>&1 && [ -d /run/systemd/system ] ||
      fail '此系统未运行 systemd。群晖/QNAP 等 NAS 需厂商原生套件；此安装器不会创建临时后台进程冒充系统服务。'
    default_install=/opt/syncsrv-agent; default_data=/var/lib/syncsrv-agent ;;
  darwin)
    default_install=/usr/local/lib/syncsrv-agent; default_data='/Library/Application Support/SyncSrvAgent' ;;
esac
printf '\nSyncSrv 备份设备安装向导 · %s\n\n' "$version"
printf '直接使用包内程序，不再下载安装文件。待备份目录在网页中配置。\n'
server=
while [ -z "$server" ]; do prompt '备份服务器地址（含 https:// 或 http:// 和端口）' ''; server=$answer; done
prompt '设备名称' "$(hostname)"; device_name=$answer
prompt '程序安装目录（不要选择解压目录）' "$default_install"; install_dir=$answer
prompt '运行数据目录（身份、队列和日志，不是待备份目录）' "$default_data"; data_dir=$answer
prompt '服务运行账户（须能读取待备份文件）' "${SUDO_USER:-root}"; service_user=$answer
allow_http=
case "$server" in http://*)
  prompt '确认仅在可信私有网络中使用 HTTP？输入 Y 确认' N
  case "$answer" in Y|y) allow_http=--allow-http ;; *) fail '已取消安装。' ;; esac ;;
esac
token_file=$(mktemp)
terminal_state=
cleanup() {
  [ -z "$terminal_state" ] || stty "$terminal_state"
  rm -f "$token_file"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
printf '一次性配对密钥（从网页复制，输入不回显）：'
if [ -t 0 ]; then terminal_state=$(stty -g); stty -echo; fi
IFS= read -r token || fail '没有读取到配对密钥。'
if [ -n "$terminal_state" ]; then stty "$terminal_state"; terminal_state=; fi
printf '\n'
[ -n "$token" ] || fail '配对密钥不能为空。'
printf '%s\n' "$token" >"$token_file"
unset token
sh "$script_dir/service-install.sh" --server "$server" --version "$version" \
  --name "$device_name" --install-dir "$install_dir" --data-dir "$data_dir" \
  --service-user "$service_user" --binary "$script_dir/syncsrv-agent" --token-file "$token_file" $allow_http ||
  fail '请保留上述错误；已有身份和队列已保留，修正后可重试未完成的安装。'
printf '\n安装成功。回到网页「备份设备」授权这台设备，再创建备份任务。\n'
