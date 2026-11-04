package main

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
)

type upgradeServiceFile struct {
	Path    string `json:"path"`
	File    string `json:"file"`
	Existed bool   `json:"existed"`
}

func serviceSnapshotPaths(mode string) []string {
	if runtime.GOOS == "linux" && mode == "systemd" {
		return []string{"/etc/systemd/system/SyncSrvAgent.service", "/etc/systemd/system/syncsrv-agent.service"}
	}
	if runtime.GOOS == "darwin" && mode == "launchd" {
		return []string{"/Library/LaunchDaemons/com.oadig.syncsrv.agent.plist"}
	}
	return nil
}
func snapshotServiceFiles(mode, backup string) ([]upgradeServiceFile, error) {
	var saved []upgradeServiceFile
	for i, path := range serviceSnapshotPaths(mode) {
		entry := upgradeServiceFile{Path: path, File: fmt.Sprintf("service-%d.snapshot", i)}
		info, err := os.Lstat(path)
		if err != nil && !os.IsNotExist(err) {
			return nil, err
		}
		if err == nil {
			if !info.Mode().IsRegular() {
				return nil, fmt.Errorf("service registration is not a regular file")
			}
			entry.Existed = true
			if err = protectedCopy(path, filepath.Join(backup, entry.File)); err != nil {
				return nil, err
			}
		}
		saved = append(saved, entry)
	}
	return saved, nil
}
func restoreServiceFiles(j upgradeJournal) error {
	allowed := map[string]bool{}
	for _, mode := range []string{"systemd", "launchd"} {
		for _, path := range serviceSnapshotPaths(mode) {
			allowed[path] = true
		}
	}
	for _, entry := range j.ServiceFiles {
		if !allowed[entry.Path] || filepath.Base(entry.File) != entry.File {
			return fmt.Errorf("invalid service rollback path")
		}
		if info, err := os.Lstat(entry.Path); err == nil && !info.Mode().IsRegular() {
			return fmt.Errorf("unsafe service rollback destination")
		}
		if !entry.Existed {
			if err := os.Remove(entry.Path); err != nil && !os.IsNotExist(err) {
				return err
			}
			continue
		}
		tmp := entry.Path + ".restore"
		_ = os.Remove(tmp)
		if err := protectedCopy(filepath.Join(j.Backup, entry.File), tmp); err != nil {
			return err
		}
		if err := replaceUpgrade(tmp, entry.Path); err != nil {
			return err
		}
		if err := syncDirectory(filepath.Dir(entry.Path)); err != nil {
			return err
		}
	}
	return nil
}
