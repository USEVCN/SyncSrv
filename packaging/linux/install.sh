#!/bin/sh
set -eu
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH LD_PRELOAD LD_LIBRARY_PATH
update_manifest=''
http_origin=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --update-manifest) [ "$#" -ge 2 ] || exit 2; update_manifest=$2; shift 2 ;;
    --allow-http-origin) [ "$#" -ge 2 ] || exit 2; http_origin=$2; shift 2 ;;
    *) printf '未知参数：%s\n' "$1" >&2; exit 2 ;;
  esac
done
umask 027
package_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)

if [ "$(uname -s)" != Linux ] || [ "$(uname -m)" != x86_64 ]; then
  printf '此安装包只适用于 Linux x86_64。\n' >&2
  exit 1
fi
if [ "$(id -u)" -ne 0 ]; then
  printf '系统服务安装需要 root，请使用 sudo sh install.sh。\n' >&2
  exit 1
fi
if ! command -v systemctl >/dev/null 2>&1 || [ ! -d /run/systemd/system ]; then
  printf '未发现正在运行的 systemd；请按说明使用免安装启动方式。\n' >&2
  exit 1
fi
for existing in /opt/syncsrv /etc/syncsrv /var/lib/syncsrv /etc/systemd/system/syncsrv.service; do
  if [ -e "$existing" ] || [ -L "$existing" ]; then
    printf '已存在 %s；为保护配置、密钥和历史记录，本脚本不覆盖已有安装。\n' "$existing" >&2
    exit 1
  fi
done

command -v python3 >/dev/null 2>&1 || { printf '需要 Python 3.9 或更新版本。\n' >&2; exit 1; }
python3 - "$package_root/bin" "$update_manifest" "$http_origin" <<'PYTHON'
import sys
if sys.version_info < (3,9):raise SystemExit('Python 3.9+ required')
sys.path.insert(0,sys.argv[1])
from release_source import validate_url
if sys.argv[2]:validate_url(sys.argv[2],sys.argv[3] or None)
PYTHON
sh "$package_root/bin/verify-package.sh"
# Run the bundled JVM before making any changes to the host.
sh "$package_root/bin/start.sh" --runtime-info
if ! getent group syncsrv >/dev/null; then
  groupadd --system syncsrv
fi
if ! id syncsrv >/dev/null 2>&1; then
  useradd --system --gid syncsrv --home-dir /var/lib/syncsrv \
    --no-create-home --shell /usr/sbin/nologin syncsrv
fi

version=$("$package_root/runtime/bin/java" -jar "$package_root/app/syncsrv.jar" release-version)
install -d -o root -g root -m 0755 "/opt/syncsrv/releases/$version"
cp -R "$package_root/." "/opt/syncsrv/releases/$version/"
ln -s "releases/$version" /opt/syncsrv/current
chown -R root:root /opt/syncsrv
chmod -R a+rX,go-w /opt/syncsrv
install -d -o root -g syncsrv -m 0750 /etc/syncsrv
install -m 0640 -o root -g syncsrv \
  "$package_root/conf/syncsrv.env.example" /etc/syncsrv/syncsrv.env
install -m 0640 -o root -g syncsrv "$package_root/conf/application-private.yml.example" /etc/syncsrv/application-private.yml
install -d -o syncsrv -g syncsrv -m 0750 /var/lib/syncsrv
install -m 0644 -o root -g root \
  "$package_root/systemd/syncsrv.service" /etc/systemd/system/syncsrv.service
if [ -n "$update_manifest" ]; then
  python3 - "$update_manifest" "$http_origin" <<'PYTHON'
import json,os
with open('/etc/syncsrv/update.json','x') as output:
    os.fchmod(output.fileno(),0o640)
    json.dump({'manifest':__import__('sys').argv[1],'allowHttpOrigin':__import__('sys').argv[2] or None},output,indent=2)
PYTHON
  chown root:syncsrv /etc/syncsrv/update.json
fi
systemctl daemon-reload
printf '\nSyncSrv 已安装，尚未启动。\n'
printf '1. 在 /etc/syncsrv/application-private.yml 设置独立审计锚点与告警；在 syncsrv.env 设置首次管理员密码。\n'
printf '2. 授予 syncsrv 账号所需的 源目录、备份库与归档库本地目录及恢复目标目录权限。\n'
printf '3. H2 在数据目录自动初始化；迁移时完整保留数据库及密钥。\n'
printf '4. 先以 syncsrv 用户运行 current/bin/initialize.sh，注册输出的审计公钥至独立锚点，再执行 systemctl enable --now syncsrv。\n'
printf '服务日志：journalctl -u syncsrv -f\n'
