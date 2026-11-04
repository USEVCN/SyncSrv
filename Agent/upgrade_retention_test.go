package main

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestUpgradeRetentionNeverDeletesLiveStateOrManualFiles(t *testing.T) {
	dir := t.TempDir()
	for i := 0; i < 8; i++ {
		os.WriteFile(filepath.Join(dir, fmt.Sprintf("syncsrv-agent.backup-20260101010101-%d", i)), []byte("old"), 0700)
	}
	for _, name := range []string{"identity.json", "events.db", "manual.backup", "syncsrv-agent.backup-manual"} {
		os.WriteFile(filepath.Join(dir, name), []byte("keep"), 0600)
	}
	current := upgradeJournal{Binary: filepath.Join(dir, "syncsrv-agent.backup-20260101010101-7")}
	if err := pruneUpgradeBackups(dir, current); err != nil {
		t.Fatal(err)
	}
	saved, _ := filepath.Glob(filepath.Join(dir, "syncsrv-agent.backup-202601*"))
	if len(saved) != 3 {
		t.Fatalf("kept %d automatic backups", len(saved))
	}
	for _, name := range []string{"identity.json", "events.db", "manual.backup", "syncsrv-agent.backup-manual"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			t.Fatal("deleted non-upgrade state", name)
		}
	}
}
