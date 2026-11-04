package main

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const systemdService = "SyncSrvAgent.service"
const legacySystemdService = "syncsrv-agent.service"

type systemctlCommand func(...string) (string, error)

// install-tools is also invoked by old update scripts after replacing the binary.
// Migrating here upgrades those installations without requiring a second update.
func migrateInstalledService(install, data, mode string) error {
	if runtime.GOOS != "linux" || mode != "systemd" {
		return nil
	}
	if _, err := os.Lstat("/etc/systemd/system/" + legacySystemdService); os.IsNotExist(err) {
		return nil
	}
	log, err := os.OpenFile(filepath.Join(install, "upgrade.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer log.Close()
	if _, err = fmt.Fprintf(log, "%s SERVICE_RENAME_REQUESTED from=%s to=%s\n", time.Now().UTC().Format(time.RFC3339), legacySystemdService, systemdService); err != nil {
		return err
	}
	if err = log.Sync(); err != nil {
		return err
	}
	err = migrateSystemd("/etc/systemd/system", install, data, func(args ...string) (string, error) {
		out, err := exec.Command("systemctl", args...).CombinedOutput()
		if err != nil {
			return strings.TrimSpace(string(out)), fmt.Errorf("systemctl %s: %w: %s", strings.Join(args, " "), err, out)
		}
		return strings.TrimSpace(string(out)), nil
	})
	if err != nil {
		fmt.Fprintf(log, "%s SERVICE_RENAME_FAILED %v\n", time.Now().UTC().Format(time.RFC3339), err)
		return err
	}
	_, writeErr := fmt.Fprintf(log, "%s SERVICE_RENAMED from=%s to=%s\n", time.Now().UTC().Format(time.RFC3339), legacySystemdService, systemdService)
	if err = errors.Join(writeErr, log.Sync()); err != nil {
		// The old updater still knows the old unit name. Do not trigger its binary
		// rollback after a successful rename; report the audit failure explicitly.
		fmt.Fprintf(os.Stderr, "Service migrated to %s, but the completion log could not be flushed: %v\n", systemdService, err)
	}
	return nil
}

func migrateSystemd(folder, install, data string, ctl systemctlCommand) (result error) {
	oldPath, newPath := filepath.Join(folder, legacySystemdService), filepath.Join(folder, systemdService)
	info, err := os.Lstat(oldPath)
	if os.IsNotExist(err) {
		return nil
	}
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("old service is not a regular unit; registration retained")
	}
	if _, err = os.Lstat(newPath); !os.IsNotExist(err) {
		return fmt.Errorf("new service already exists or cannot be checked; no migration performed")
	}
	raw, err := os.ReadFile(oldPath)
	if err != nil {
		return err
	}
	var commands []string
	for _, line := range strings.Split(string(raw), "\n") {
		if strings.HasPrefix(line, "ExecStart=") {
			commands = append(commands, line)
		}
	}
	expected := fmt.Sprintf("ExecStart=\"%s\" run --data-dir \"%s\"", filepath.Join(install, "syncsrv-agent"), data)
	if len(commands) != 1 || commands[0] != expected {
		return fmt.Errorf("legacy service belongs to another installation; registration retained")
	}
	fragment, err := ctl("show", legacySystemdService, "--property=FragmentPath", "--value")
	if err != nil || fragment != oldPath {
		return fmt.Errorf("unexpected legacy unit location: %s: %v", fragment, err)
	}
	dropins, err := ctl("show", legacySystemdService, "--property=DropInPaths", "--value")
	if err != nil || dropins != "" {
		return fmt.Errorf("custom service overrides require manual migration; registration retained: %v", err)
	}
	state, err := ctl("show", legacySystemdService, "--property=ActiveState", "--value")
	if err != nil {
		return err
	}
	if state != "active" && state != "inactive" && state != "failed" {
		return fmt.Errorf("service is changing state; retry update once stable")
	}
	enabled, err := ctl("show", legacySystemdService, "--property=UnitFileState", "--value")
	if err != nil {
		return err
	}
	if enabled != "enabled" && enabled != "disabled" {
		return fmt.Errorf("unsupported service enable state %q; registration retained", enabled)
	}
	running, autostart, renamed := state == "active", enabled == "enabled", false
	defer func() {
		if result == nil {
			return
		}
		var rollback []error
		call := func(args ...string) { _, err := ctl(args...); rollback = append(rollback, err) }
		if renamed {
			call("stop", systemdService)
			call("disable", systemdService)
			rollback = append(rollback, os.Rename(newPath, oldPath))
			call("daemon-reload")
		}
		if autostart {
			call("enable", legacySystemdService)
		}
		if running {
			call("start", legacySystemdService)
		}
		result = errors.Join(result, errors.Join(rollback...))
	}()
	if _, err = ctl("stop", legacySystemdService); err != nil {
		return err
	}
	if _, err = ctl("disable", legacySystemdService); err != nil {
		return err
	}
	if err = os.Rename(oldPath, newPath); err != nil {
		return err
	}
	renamed = true
	if _, err = ctl("daemon-reload"); err != nil {
		return err
	}
	if autostart {
		if _, err = ctl("enable", systemdService); err != nil {
			return err
		}
	}
	if running {
		if _, err = ctl("start", systemdService); err != nil {
			return err
		}
		if _, err = ctl("is-active", "--quiet", systemdService); err != nil {
			return err
		}
	}
	return nil
}
