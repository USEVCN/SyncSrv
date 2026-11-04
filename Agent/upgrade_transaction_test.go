package main

import (
	bolt "go.etcd.io/bbolt"
	"os"
	"path/filepath"
	"testing"
)

func TestUpgradeCrashJournalRestoresToolsAndKeepsNewestQueue(t *testing.T) {
	install, data := t.TempDir(), t.TempDir()
	backup := filepath.Join(install, "syncsrv-agent.backup-test")
	for name, body := range map[string]string{"syncsrv-agent": "old", "syncsrv-agent.backup-test": "old", "update.sh": "old-tool"} {
		if err := os.WriteFile(filepath.Join(install, name), []byte(body), 0700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(data, "identity.json"), []byte("same-identity"), 0600); err != nil {
		t.Fatal(err)
	}
	db, err := bolt.Open(filepath.Join(data, "events.db"), 0600, nil)
	if err != nil {
		t.Fatal(err)
	}
	db.Update(func(tx *bolt.Tx) error {
		b, e := tx.CreateBucketIfNotExists([]byte("queue"))
		if e != nil {
			return e
		}
		return b.Put([]byte("before"), []byte("keep"))
	})
	db.Close()
	command := func(action string, extra ...string) {
		t.Helper()
		args := []string{"--action", action, "--install-dir", install, "--data-dir", data}
		if err := upgradeTransaction(append(args, extra...)); err != nil {
			t.Fatal(err)
		}
	}
	command("begin", "--backup", backup, "--running=true")
	command("activate")
	stage := filepath.Join(install, ".upgrade-test")
	os.Mkdir(stage, 0700)
	os.WriteFile(filepath.Join(stage, "syncsrv-agent"), []byte("new"), 0700)
	command("replace", "--replacement", filepath.Join(stage, "syncsrv-agent"))
	os.WriteFile(filepath.Join(install, "update.sh"), []byte("new-tool"), 0700)
	db, _ = bolt.Open(filepath.Join(data, "events.db"), 0600, nil)
	db.Update(func(tx *bolt.Tx) error { return tx.Bucket([]byte("queue")).Put([]byte("after"), []byte("do-not-lose")) })
	db.Close()
	command("rollback")
	for name, expected := range map[string]string{"syncsrv-agent": "old", "update.sh": "old-tool"} {
		raw, _ := os.ReadFile(filepath.Join(install, name))
		if string(raw) != expected {
			t.Fatal("rollback incomplete", name)
		}
	}
	db, _ = bolt.Open(filepath.Join(data, "events.db"), 0600, nil)
	defer db.Close()
	db.View(func(tx *bolt.Tx) error {
		if string(tx.Bucket([]byte("queue")).Get([]byte("after"))) != "do-not-lose" {
			t.Fatal("rollback lost post-upgrade events")
		}
		return nil
	})
}
func TestUpgradeSnapshotRejectsSymlink(t *testing.T) {
	install, data := t.TempDir(), t.TempDir()
	target := filepath.Join(install, "target")
	os.WriteFile(target, []byte("secret"), 0600)
	if err := os.Symlink(target, filepath.Join(install, "update.sh")); err != nil {
		t.Skip("symlinks unavailable")
	}
	err := upgradeTransaction([]string{"--action", "begin", "--install-dir", install, "--data-dir", data, "--backup", filepath.Join(install, "syncsrv-agent.backup-test")})
	if err == nil {
		t.Fatal("symlink entered privileged rollback snapshot")
	}
}
