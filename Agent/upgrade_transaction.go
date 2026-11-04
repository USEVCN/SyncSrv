package main

import (
	"encoding/json"
	"flag"
	"fmt"
	bolt "go.etcd.io/bbolt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type upgradeJournal struct {
	ServiceFiles []upgradeServiceFile `json:"serviceFiles,omitempty"`
	State        string               `json:"state"`
	Backup       string               `json:"backup"`
	Binary       string               `json:"binary"`
	Running      bool                 `json:"running"`
	Files        []string             `json:"files"`
}

func journalSave(path string, journal upgradeJournal) error {
	raw, err := json.Marshal(journal)
	if err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".journal-*")
	if err != nil {
		return err
	}
	name := temp.Name()
	defer os.Remove(name)
	if _, err = temp.Write(raw); err == nil {
		err = temp.Sync()
	}
	e := temp.Close()
	if err == nil {
		err = e
	}
	if err != nil {
		return err
	}
	// Windows cannot atomically replace an open file using os.Rename. A retained .previous is recoverable.
	if _, err = os.Stat(path); err == nil {
		_ = os.Remove(path + ".previous")
		if err = os.Rename(path, path+".previous"); err != nil {
			return err
		}
	}
	if err := replaceUpgrade(name, path); err != nil {
		return err
	}
	return syncDirectory(filepath.Dir(path))
}
func protectedCopy(from, to string) error {
	info, err := os.Lstat(from)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("upgrade snapshot rejects non-regular files")
	}
	in, err := os.Open(from)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(to, os.O_CREATE|os.O_EXCL|os.O_WRONLY, info.Mode().Perm())
	if err != nil {
		return err
	}
	_, err = io.Copy(out, in)
	if err == nil {
		err = out.Sync()
	}
	e := out.Close()
	if err == nil {
		err = e
	}
	return err
}
func upgradeTransaction(args []string) error {
	f := flag.NewFlagSet("upgrade-transaction", flag.ContinueOnError)
	replacement := f.String("replacement", "", "verified staged program")
	action := f.String("action", "inspect", "begin|activate|commit|inspect|rollback")
	install := f.String("install-dir", "", "protected installation")
	data := f.String("data-dir", "", "persistent data")
	binary := f.String("backup", "", "previous binary backup")
	mode := f.String("mode", "", "service manager")
	running := f.Bool("running", false, "previous service running")
	if err := f.Parse(args); err != nil {
		return err
	}
	if !filepath.IsAbs(*install) || !filepath.IsAbs(*data) {
		return fmt.Errorf("absolute paths required")
	}
	for _, p := range []string{*install, *data} {
		info, err := os.Lstat(p)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("real protected directories required")
		}
	}
	path := filepath.Join(*install, "upgrade-state.json")
	if *action == "begin" {
		if filepath.Dir(*binary) != filepath.Clean(*install) || !strings.HasPrefix(filepath.Base(*binary), "syncsrv-agent") {
			return fmt.Errorf("invalid backup location")
		}
		backup, err := os.MkdirTemp(*install, ".rollback-*")
		if err != nil {
			return err
		}
		j := upgradeJournal{State: "PREPARED", Backup: backup, Binary: *binary, Running: *running}
		entries, err := os.ReadDir(*install)
		if err != nil {
			return err
		}
		for _, entry := range entries {
			name := entry.Name()
			ext := filepath.Ext(name)
			if entry.IsDir() || !(ext == ".sh" || ext == ".ps1" || ext == ".bat" || ext == ".command" || name == "agent-control.conf") {
				continue
			}
			if err = protectedCopy(filepath.Join(*install, name), filepath.Join(backup, name)); err != nil {
				return err
			}
			j.Files = append(j.Files, name)
		}
		if _, err = os.Stat(filepath.Join(*data, "events.db")); err == nil {
			db, e := bolt.Open(filepath.Join(*data, "events.db"), 0600, &bolt.Options{ReadOnly: true, Timeout: time.Second})
			if e != nil {
				return e
			}
			e = db.View(func(tx *bolt.Tx) error { return tx.CopyFile(filepath.Join(backup, "events.db.snapshot"), 0600) })
			db.Close()
			if e != nil {
				return e
			}
		}
		if _, err = os.Stat(filepath.Join(*data, "identity.json")); err == nil {
			if err = protectedCopy(filepath.Join(*data, "identity.json"), filepath.Join(backup, "identity.json.snapshot")); err != nil {
				return err
			}
		}
		j.ServiceFiles, err = snapshotServiceFiles(*mode, backup)
		if err != nil {
			return err
		}
		if err = syncDirectory(backup); err != nil {
			return err
		}
		return journalSave(path, j)
	}
	raw, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		raw, err = os.ReadFile(path + ".previous")
	}
	if os.IsNotExist(err) && *action == "inspect" {
		fmt.Println("CLEAN")
		return nil
	}
	if err != nil {
		return err
	}
	var j upgradeJournal
	if err = json.Unmarshal(raw, &j); err != nil {
		return err
	}
	if filepath.Dir(j.Backup) != filepath.Clean(*install) || !strings.HasPrefix(filepath.Base(j.Backup), ".rollback-") || filepath.Dir(j.Binary) != filepath.Clean(*install) {
		return fmt.Errorf("unsafe rollback journal")
	}
	switch *action {
	case "inspect":
		if j.State == "ACTIVATING" {
			if j.Running {
				fmt.Println("RECOVER_RUNNING")
			} else {
				fmt.Println("RECOVER_STOPPED")
			}
		} else {
			fmt.Println("CLEAN")
		}
		return nil
	case "replace":
		if j.State != "ACTIVATING" || filepath.Dir(filepath.Dir(*replacement)) != filepath.Clean(*install) || !strings.HasPrefix(filepath.Base(filepath.Dir(*replacement)), ".upgrade-") {
			return fmt.Errorf("replacement requires a prepared private stage")
		}
		parent, e := os.Lstat(filepath.Dir(*replacement))
		if e != nil || !parent.IsDir() || parent.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("unsafe replacement directory")
		}
		exe := filepath.Join(*install, "syncsrv-agent")
		if strings.HasSuffix(*replacement, ".exe") {
			exe += ".exe"
		}
		tmp := filepath.Join(*install, ".replacement-"+randomID())
		defer os.Remove(tmp)
		if err = protectedCopy(*replacement, tmp); err != nil {
			return err
		}
		if old, e := os.Stat(exe); e == nil {
			if err = os.Chmod(tmp, old.Mode().Perm()); err != nil {
				return err
			}
		}
		if err = replaceUpgrade(tmp, exe); err != nil {
			return err
		}
		return syncDirectory(*install)
	case "activate":
		j.State = "ACTIVATING"
	case "commit":
		j.State = "COMPLETED"
		if err := pruneUpgradeBackups(*install, j); err != nil {
			log.Printf("upgrade backup retention: %v", err)
		}
	case "rollback":
		if err = restoreServiceFiles(j); err != nil {
			return err
		}
		for _, name := range j.Files {
			if filepath.Base(name) != name {
				return fmt.Errorf("unsafe rollback member")
			}
			target := filepath.Join(*install, name)
			tmp := target + ".restore"
			_ = os.Remove(tmp)
			if err = protectedCopy(filepath.Join(j.Backup, name), tmp); err != nil {
				return err
			}
			if err = replaceUpgrade(tmp, target); err != nil {
				return err
			}
		}
		// Keep the newest queue on rollback: schema 1 is unchanged. Offline snapshot is retained for disaster recovery.
		exe := filepath.Join(*install, "syncsrv-agent")
		if strings.HasSuffix(j.Binary, ".exe") || strings.Contains(filepath.Base(j.Binary), ".exe.backup-") {
			exe += ".exe"
		}
		tmp := exe + ".restore"
		_ = os.Remove(tmp)
		if err = protectedCopy(j.Binary, tmp); err != nil {
			return err
		}
		if err = replaceUpgrade(tmp, exe); err != nil {
			return err
		}
		j.State = "ROLLED_BACK"
	default:
		return fmt.Errorf("unknown upgrade transaction action")
	}
	return journalSave(path, j)
}
