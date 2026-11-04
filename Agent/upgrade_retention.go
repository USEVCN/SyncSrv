package main

import (
	"os"
	"path/filepath"
	"regexp"
	"sort"
)

// Only files created by our upgrade transaction are eligible; device identity and live queue are never removed.
func pruneUpgradeBackups(install string, current upgradeJournal) error {
	entries, err := os.ReadDir(install)
	if err != nil {
		return err
	}
	pattern := regexp.MustCompile(`^syncsrv-agent(?:\.exe)?\.backup-[0-9]{14}-[A-Za-z0-9-]+$`)
	for _, directories := range []bool{false, true} {
		type candidate struct {
			name string
			when int64
		}
		var files []candidate
		for _, entry := range entries {
			name := entry.Name()
			path := filepath.Join(install, name)
			if path == current.Binary || path == current.Backup || entry.Type()&os.ModeSymlink != 0 {
				continue
			}
			if directories {
				if !entry.IsDir() || !regexp.MustCompile(`^\.rollback-[0-9]+$`).MatchString(name) {
					continue
				}
			} else {
				if !pattern.MatchString(name) || !entry.Type().IsRegular() {
					continue
				}
			}
			info, e := entry.Info()
			if e != nil {
				return e
			}
			files = append(files, candidate{name, info.ModTime().UnixNano()})
		}
		sort.Slice(files, func(i, j int) bool { return files[i].when > files[j].when })
		// Keep current transaction plus two older snapshots/binaries.
		for _, file := range files[min(2, len(files)):] {
			path := filepath.Join(install, file.name)
			if directories {
				err = os.RemoveAll(path)
			} else {
				err = os.Remove(path)
			}
			if err != nil {
				return err
			}
		}
	}
	return nil
}
