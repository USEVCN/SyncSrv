#!/bin/sh
# Development installer. Release binaries must be published by the center operator.
set -eu
umask 077
server= version= install_dir= data_dir= service_user= binary= token_file= allow_http= device_name=
fail() { printf '%s\n' "$*" >&2; exit 1; }
while [ "$#" -gt 0 ]; do
  if [ "$1" = --allow-http ]; then allow_http=--allow-http; shift; continue; fi
  [ "$#" -ge 2 ] || fail "Missing value for $1"
  case "$1" in
    --server) server=$2 ;; --version) version=$2 ;;
    --install-dir) install_dir=$2 ;; --data-dir) data_dir=$2 ;;
    --service-user) service_user=$2 ;; --binary) binary=$2 ;;
    --token-file) token_file=$2 ;;
    --name) device_name=$2 ;;
    *) fail "Unknown option: $1" ;;
  esac
  shift 2
done
[ "$(id -u)" = 0 ] || fail 'Run this installer with sudo/root.'
case "$server" in https://*) ;; http://*) [ -n "$allow_http" ] || fail "Use --allow-http for LAN testing" ;; *) fail '--server must be an HTTPS origin' ;; esac
server=${server%/}
curl_protocols="=https"
[ -z "$allow_http" ] || curl_protocols="=http,https"
case "$version" in ''|*[!A-Za-z0-9._-]*) fail '--version must name a pinned release' ;; esac
[ -n "$service_user" ] || fail '--service-user must name an existing local account with read access to source paths'
id "$service_user" >/dev/null 2>&1 || fail 'Service account does not exist'
case "$service_user" in *[!A-Za-z0-9_.-]*) fail 'Unsupported service account name' ;; esac
case "$(uname -s)" in
  Linux) platform=linux; mode=systemd; default_install=/opt/syncsrv-agent; default_data=/var/lib/syncsrv-agent
    if ! command -v systemctl >/dev/null 2>&1 || [ ! -d /run/systemd/system ]; then
      mode=portable
      [ "$service_user" = root ] || fail 'Portable Linux/NAS mode currently requires --service-user root'
      [ -n "$install_dir" ] && [ -n "$data_dir" ] || fail 'NAS requires explicit persistent --install-dir and --data-dir (for example under /volume1)'
    fi ;;
  Darwin) platform=darwin; mode=launchd; default_install=/usr/local/lib/syncsrv-agent; default_data='/Library/Application Support/SyncSrvAgent' ;;
  *) fail 'Unsupported system. Windows uses the PowerShell response from the same /install URL.' ;;
esac
case "$(uname -m)" in x86_64|amd64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; *) fail 'No release for this architecture' ;; esac
install_dir=${install_dir:-$default_install}
data_dir=${data_dir:-$default_data}
pending_file=$install_dir/.install-pending
pending_settings=$(printf '%s\n' "$server" "$version" "$install_dir" "$data_dir" "$service_user")
resume=0
if [ -f "$pending_file" ] && [ ! -L "$pending_file" ]; then
  [ "$(cat "$pending_file")" = "$pending_settings" ] || fail 'Incomplete installation belongs to different settings; preserve its data and use the original settings'
  resume=1
fi
for directory in "$install_dir" "$data_dir"; do
  case "$directory" in /*) ;; *) fail 'Installation and data directories must be absolute' ;; esac
  case "$directory" in /|*'"'*|*'\'*|*'%'*|*'$'*|*'`'*|*'
'*) fail 'Unsupported directory characters' ;; esac
  [ ! -L "$directory" ] || fail 'A dedicated real directory is required'
  [ ! -e "$directory" ] || { [ -d "$directory" ] && { [ "$resume" = 1 ] || [ -z "$(ls -A "$directory")" ]; }; } || fail "Directory must be new or empty: $directory"
done
[ "$install_dir" != "$data_dir" ] || fail 'Binary and data directories must be different'
case "$data_dir/" in "$install_dir/"*) fail 'Data must be outside the binary directory' ;; esac
case "$install_dir/" in "$data_dir/"*) fail 'Binary must be outside the data directory' ;; esac
service_name=com.oadig.syncsrv.agent
if [ "$mode" = systemd ]; then
  [ ! -e /etc/systemd/system/SyncSrvAgent.service ] && [ ! -e /etc/systemd/system/syncsrv-agent.service ] || fail 'Service already exists; run update.sh from the complete upgrade package instead of reinstalling'
elif [ "$mode" = launchd ]; then
  [ ! -e /Library/LaunchDaemons/$service_name.plist ] || fail 'Service already exists; run update.sh from the complete upgrade package instead of reinstalling'
fi
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT HUP INT TERM
asset=syncsrv-agent-$platform-$arch
if [ -n "$binary" ]; then
  [ -f "$binary" ] || fail '--binary must name a locally built binary'
  cp "$binary" "$temp_dir/agent"
else
  command -v curl >/dev/null || fail 'curl is required'
  curl --proto "$curl_protocols" --tlsv1.2 -fsS "$server/agent/releases/$version/$asset" -o "$temp_dir/agent"
  curl --proto "$curl_protocols" --tlsv1.2 -fsS "$server/agent/releases/$version/$asset.sha256" -o "$temp_dir/hash"
  expected=$(awk 'NR==1 {print $1}' "$temp_dir/hash")
  [ "${#expected}" = 64 ] || fail 'Invalid release checksum'
  case "$expected" in *[!a-f0-9]*) fail 'Invalid release checksum' ;; esac
  if command -v sha256sum >/dev/null; then actual=$(sha256sum "$temp_dir/agent" | awk '{print $1}')
  else actual=$(shasum -a 256 "$temp_dir/agent" | awk '{print $1}'); fi
  [ "$actual" = "$expected" ] || fail 'Release checksum mismatch'
fi
chmod 700 "$temp_dir/agent"
reported=$("$temp_dir/agent" version)
[ "$reported" = "$version $platform $arch" ] || fail "Unexpected binary identity: $reported"
mkdir -p "$install_dir" "$data_dir"
chmod 755 "$install_dir"
chmod 700 "$data_dir"
cp "$temp_dir/agent" "$install_dir/syncsrv-agent"
chmod 755 "$install_dir/syncsrv-agent"
printf '%s\n' "$pending_settings" >"$pending_file"
if [ -n "$token_file" ]; then
  "$install_dir/syncsrv-agent" enroll $allow_http --server "$server" --data-dir "$data_dir" --name "$device_name" --token-file "$token_file"
else
  "$install_dir/syncsrv-agent" enroll $allow_http --server "$server" --data-dir "$data_dir" --name "$device_name" </dev/tty
fi
chown "$service_user" "$data_dir" "$data_dir/identity.json"
"$install_dir/syncsrv-agent" install-tools --install-dir "$install_dir" --data-dir "$data_dir" --mode "$mode" --service-user "$service_user"
if [ "$mode" = systemd ]; then
  cat >"$temp_dir/service" <<EOF
[Unit]
Description=SyncSrvAgent
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
User=$service_user
ExecStart="$install_dir/syncsrv-agent" run --data-dir "$data_dir"
Restart=on-failure
RestartSec=15
UMask=0077
NoNewPrivileges=true
ProtectSystem=full
# File access is limited by the selected service account and verified directory bindings.
ProtectHome=false
[Install]
WantedBy=multi-user.target
EOF
  cp "$temp_dir/service" /etc/systemd/system/SyncSrvAgent.service
  chmod 644 /etc/systemd/system/SyncSrvAgent.service
  systemctl daemon-reload
  systemctl enable --now SyncSrvAgent
  systemctl is-active --quiet SyncSrvAgent || fail 'Service did not stay active; inspect journalctl -u SyncSrvAgent'
elif [ "$mode" = launchd ]; then
  xml() { printf '%s' "$1" | sed 's/\&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g'; }
  exe_xml=$(xml "$install_dir/syncsrv-agent"); data_xml=$(xml "$data_dir")
  cat >"/Library/LaunchDaemons/$service_name.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>$service_name</string>
<key>UserName</key><string>$service_user</string>
<key>ProgramArguments</key><array><string>$exe_xml</string><string>run</string><string>--data-dir</string><string>$data_xml</string></array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>ThrottleInterval</key><integer>15</integer>
<key>StandardOutPath</key><string>$data_xml/service.log</string>
<key>StandardErrorPath</key><string>$data_xml/service.log</string>
</dict></plist>
EOF
  chmod 644 "/Library/LaunchDaemons/$service_name.plist"
  plutil -lint "/Library/LaunchDaemons/$service_name.plist"
  launchctl bootstrap system "/Library/LaunchDaemons/$service_name.plist"
  launchctl print system/$service_name >/dev/null
else
  sh "$install_dir/start.sh"
  printf '%s\n' "Portable NAS service started. Configure the NAS boot task to run: sh '$install_dir/start.sh'"
fi
[ "$mode" != systemd ] || printf '%s\n' 'Linux management: systemctl status SyncSrvAgent'
printf '%s\n' 'Service installed. Authorize this device in the center, then assign readable source paths.'
rm -f "$pending_file"
