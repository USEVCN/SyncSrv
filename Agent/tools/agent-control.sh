#!/bin/sh
# The configuration is four literal lines, never sourced or evaluated as shell code.
set -eu
umask 077
fail() { printf '%s\n' "$*" >&2; exit 1; }
[ "$(id -u)" = 0 ] || fail 'Run with sudo/root.'
action=${1:-update}
case "$action" in start|stop|restart|update|uninstall) ;; *) fail 'Use start, stop, restart, update or uninstall.' ;; esac
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bootstrap=$script_dir/syncsrv-agent
service=SyncSrvAgent.service
config=${SYNCSRV_AGENT_CONTROL_CONFIG:-$script_dir/agent-control.conf}
# A config override is a literal path, useful when unpacking an upgrade beside an existing NAS install.
if [ -f "$config" ] && [ ! -L "$config" ]; then
  install_dir=$(sed -n '1p' "$config")
  data_dir=$(sed -n '2p' "$config")
  mode=$(sed -n '3p' "$config")
  service_user=$(sed -n '4p' "$config")
  exe=$install_dir/syncsrv-agent
elif [ "$(uname -s)" = Darwin ]; then
  plist=/Library/LaunchDaemons/com.oadig.syncsrv.agent.plist
  [ -f "$plist" ] || fail 'Install the source service first.'
  exe=$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' "$plist")
  data_dir=$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:3' "$plist")
  service_user=$(/usr/libexec/PlistBuddy -c 'Print :UserName' "$plist")
  install_dir=$(dirname "$exe"); mode=launchd
elif command -v systemctl >/dev/null 2>&1 && { systemctl cat SyncSrvAgent.service >/dev/null 2>&1 || systemctl cat syncsrv-agent.service >/dev/null 2>&1; }; then
  if ! systemctl cat SyncSrvAgent.service >/dev/null 2>&1; then service=syncsrv-agent.service; fi
  line=$(systemctl cat "$service" | awk '/^ExecStart=/{s=$0} END{print s}')
  exe=$(printf '%s\n' "$line" | sed -n 's/^ExecStart="\([^"]*\)" run --data-dir "\([^"]*\)"$/\1/p')
  data_dir=$(printf '%s\n' "$line" | sed -n 's/^ExecStart="\([^"]*\)" run --data-dir "\([^"]*\)"$/\2/p')
  service_user=$(systemctl show "$service" --property=User --value)
  [ -n "$service_user" ] || service_user=root
  install_dir=$(dirname "$exe"); mode=systemd
else
  fail 'Existing configuration not found. Set SYNCSRV_AGENT_CONTROL_CONFIG to the installed agent-control.conf; do not re-enroll.'
fi
case "$install_dir:$data_dir:$exe" in /*:/*:/*) ;; *) fail 'Invalid absolute service paths.' ;; esac
for path in "$install_dir" "$exe" "$data_dir"; do [ ! -L "$path" ] || fail 'Symbolic service paths are not supported.'; done
[ -f "$exe" ] && [ -d "$data_dir" ] || fail 'Installed program/data directory unavailable.'
case "$mode" in systemd|launchd|portable) ;; *) fail 'Unsupported service mode.' ;; esac
lock=$install_dir/.management-lock
if ! mkdir "$lock" 2>/dev/null; then
  owner=$(cat "$lock/pid" 2>/dev/null || printf 0)
  case "$owner" in ''|*[!0-9]*|0) fail 'Management lock is incomplete; inspect it before recovery.' ;; esac
  if kill -0 "$owner" 2>/dev/null; then fail 'Another management process is active.'; fi
  rm -f "$lock/pid"; rmdir "$lock" || fail 'Unsafe management lock contents.'
  mkdir "$lock" || fail 'Another operation acquired the lock.'
fi
printf '%s\n' "$$" >"$lock/pid"
stage= backup= replacement= changed=0 was_running=0
log() {
  if [ -f "$install_dir/upgrade.log" ] && [ "$(wc -c <"$install_dir/upgrade.log")" -gt 1048576 ]; then mv -f "$install_dir/upgrade.log" "$install_dir/upgrade.log.previous"; fi
  printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*" | tee -a "$install_dir/upgrade.log"; }
active() {
  case "$mode" in
    systemd) systemctl is-active --quiet "$service" ;;
    launchd) launchctl print system/com.oadig.syncsrv.agent 2>/dev/null | grep -q 'state = running' ;;
    portable) "$bootstrap" portable-control --action status --install-dir "$install_dir" --data-dir "$data_dir" ;;
  esac
}
stop_agent() {
  case "$mode" in
    systemd) systemctl stop "$service" ;;
    launchd) if launchctl print system/com.oadig.syncsrv.agent >/dev/null 2>&1; then launchctl bootout system /Library/LaunchDaemons/com.oadig.syncsrv.agent.plist; fi ;;
    portable) "$bootstrap" portable-control --action stop --install-dir "$install_dir" --data-dir "$data_dir" ;;
  esac
}
start_agent() {
  case "$mode" in
    systemd) systemctl start "$service" ;;
    launchd) if ! launchctl print system/com.oadig.syncsrv.agent >/dev/null 2>&1; then launchctl bootstrap system /Library/LaunchDaemons/com.oadig.syncsrv.agent.plist; fi ;;
    portable) "$bootstrap" portable-control --action start --install-dir "$install_dir" --data-dir "$data_dir" ;;
  esac
  sleep 3
  active || return 1
}
cleanup() {
  result=$?
  trap - EXIT HUP INT TERM
  if [ "$result" -ne 0 ]; then
    if [ "$changed" = 1 ] && [ -n "$stage" ]; then
      stop_agent || true
      "$stage/syncsrv-agent" upgrade-transaction --action rollback --install-dir "$install_dir" --data-dir "$data_dir" || { log "ROLLBACK_INCOMPLETE inspect=$install_dir/upgrade-state.json"; exit "$result"; }
      if [ "$mode" = systemd ]; then systemctl daemon-reload; fi
    fi
    if [ "$changed" = 1 ] && [ -n "$backup" ]; then
      if stop_agent && cp -p "$backup" "$replacement" && mv -f "$replacement" "$exe"; then
        if [ "$was_running" = 0 ] || start_agent; then log 'ROLLED_BACK to_previous_binary'
        else log "ROLLBACK_START_FAILED backup=$backup"; fi
      else log "ROLLBACK_FAILED restore=$backup"; fi
    fi
    log 'FAILED (see preceding error)'
  fi
  [ -z "$stage" ] || rm -rf "$stage"
  [ -z "$replacement" ] || rm -f "$replacement"
  rm -f "$lock/pid"; rmdir "$lock"
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' HUP INT TERM
if [ "$mode" = systemd ]; then
  if systemctl cat SyncSrvAgent.service >/dev/null 2>&1; then
    if systemctl cat syncsrv-agent.service >/dev/null 2>&1; then fail 'Both old and new services exist; resolve the duplicate registration first.'; fi
    service=SyncSrvAgent.service
  elif systemctl cat syncsrv-agent.service >/dev/null 2>&1; then service=syncsrv-agent.service; fi
fi
if [ -f "$install_dir/upgrade-state.json" ] || [ -f "$install_dir/upgrade-state.json.previous" ]; then
  recovery=$("$bootstrap" upgrade-transaction --action inspect --install-dir "$install_dir" --data-dir "$data_dir")
  case "$recovery" in
    RECOVER_RUNNING|RECOVER_STOPPED)
      stop_agent
      "$bootstrap" upgrade-transaction --action rollback --install-dir "$install_dir" --data-dir "$data_dir"
      if [ "$mode" = systemd ]; then systemctl daemon-reload; fi
      if [ "$recovery" = RECOVER_RUNNING ]; then start_agent; fi
      log INTERRUPTED_UPGRADE_RECOVERED ;;
  esac
fi
case "$action" in
  uninstall) . "$script_dir/agent-uninstall.sh"; uninstall_agent; exit 0 ;;
  start) start_agent; log SERVICE_STARTED; exit 0 ;;
  stop) stop_agent; log SERVICE_STOPPED; exit 0 ;;
  restart) stop_agent; start_agent; log SERVICE_RESTARTED; exit 0 ;;
esac
[ -x "$bootstrap" ] || fail 'Extract the complete upgrade package first.'
old_identity=$("$exe" version)
old_version=${old_identity%% *}
active && was_running=1
stage=$(mktemp -d "$install_dir/.upgrade-XXXXXXXX")
log "CHECKING from=$old_version"
new_version=$("$bootstrap" prepare-update --data-dir "$data_dir" --current-version "$old_version" --output-dir "$stage")
if [ "$new_version" = UNCHANGED ]; then log NO_NEWER_VERSION; exit 0; fi
# prepare-update already checks SHA-256, size, platform and executable version.
backup=$install_dir/syncsrv-agent.backup-$(date -u '+%Y%m%d%H%M%S')-$$
cp -p "$exe" "$backup"
replacement=$install_dir/.replacement-$$
[ ! -e "$replacement" ] && [ ! -L "$replacement" ] || fail 'Replacement path already exists.'
cp -p "$exe" "$replacement"
cat "$stage/syncsrv-agent" >"$replacement"
log "VERIFIED from=$old_version to=$new_version backup=$backup"
stop_agent
"$stage/syncsrv-agent" upgrade-transaction --action begin --install-dir "$install_dir" --data-dir "$data_dir" --backup "$backup" --mode "$mode" --running="$([ "$was_running" = 1 ] && printf true || printf false)"
"$stage/syncsrv-agent" upgrade-transaction --action activate --install-dir "$install_dir" --data-dir "$data_dir"
changed=1
mv -f "$replacement" "$exe"
if [ "$was_running" = 1 ]; then
  rm -f "$data_dir/health.json"
  start_agent
  "$exe" health-check --data-dir "$data_dir" --expected-version "$new_version" --timeout 120
fi
"$exe" install-tools --install-dir "$install_dir" --data-dir "$data_dir" --mode "$mode" --service-user "$service_user"
"$exe" upgrade-transaction --action commit --install-dir "$install_dir" --data-dir "$data_dir"
log "UPGRADED from=$old_version to=$new_version running=$was_running"
printf '%s\n' 'Refresh the center version and verify a small-file backup. Identity and event queue are preserved.'
