#!/bin/sh
# Sourced by agent-control.sh after acquiring the management lock.
uninstall_agent() {
  case "$mode" in
    systemd)
      loaded=$(systemctl show "$service" --property=LoadState --value)
      if [ "$loaded" = not-found ]; then log SERVICE_ALREADY_UNINSTALLED; return; fi
      unit=$(systemctl show "$service" --property=FragmentPath --value)
      [ "$unit" = "/etc/systemd/system/$service" ] && [ -f "$unit" ] && [ ! -L "$unit" ] || fail 'Unexpected service unit; no files removed.'
      command=$(systemctl cat "$service" | awk '/^ExecStart=/{s=$0} END{print s}')
      [ "$command" = "ExecStart=\"$exe\" run --data-dir \"$data_dir\"" ] || fail 'Service belongs to a different installation; nothing removed.'
      log UNINSTALL_REQUESTED
      stop_agent
      state=$(systemctl show "$service" --property=ActiveState --value)
      case "$state" in inactive|failed) ;; *) fail 'Service has not stopped; registration retained.' ;; esac
      systemctl disable "$service"
      rm -- "$unit"
      systemctl daemon-reload
      ;;
    launchd)
      plist=/Library/LaunchDaemons/com.oadig.syncsrv.agent.plist
      if [ ! -e "$plist" ] && ! launchctl print system/com.oadig.syncsrv.agent >/dev/null 2>&1; then
        log SERVICE_ALREADY_UNINSTALLED; return
      fi
      [ -f "$plist" ] && [ ! -L "$plist" ] || fail 'Unexpected launchd registration; nothing removed.'
      registered_exe=$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' "$plist")
      registered_data=$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:3' "$plist")
      [ "$registered_exe" = "$exe" ] && [ "$registered_data" = "$data_dir" ] || fail 'Service belongs to a different installation; nothing removed.'
      log UNINSTALL_REQUESTED
      stop_agent
      if launchctl print system/com.oadig.syncsrv.agent >/dev/null 2>&1; then fail 'Service has not unloaded; registration retained.'; fi
      rm -- "$plist"
      ;;
    portable)
      log UNINSTALL_REQUESTED
      stop_agent
      # Portable installations have no system unit; custom NAS boot tasks are user-owned.
      printf '%s\n' 'Portable process stopped. Remove any custom NAS boot task that starts this Agent.'
      ;;
  esac
  log "SERVICE_UNINSTALLED mode=$mode retained_data=$data_dir"
  printf '%s\n' "Program and configuration retained: $install_dir" "Identity, queue and logs retained: $data_dir" 'Backup files and server-side records were not removed.'
}
