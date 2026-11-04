package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSystemdMigrationPreservesStateAndRollsBack(t *testing.T) {
	for _, tc := range []struct {
		name                        string
		running, enabled, failStart bool
	}{
		{"running", true, true, false}, {"stopped", false, false, false}, {"rollback", true, true, true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			old := filepath.Join(dir, legacySystemdService)
			fresh := filepath.Join(dir, systemdService)
			unit := fmt.Sprintf("[Service]\nExecStart=\"%s\" run --data-dir \"/var/lib/agent\"\n", filepath.Join("/opt/agent", "syncsrv-agent"))
			if err := os.WriteFile(old, []byte(unit), 0644); err != nil {
				t.Fatal(err)
			}
			var actions []string
			run := func(args ...string) (string, error) {
				actions = append(actions, strings.Join(args, " "))
				if args[0] == "show" {
					switch args[2] {
					case "--property=FragmentPath":
						return old, nil
					case "--property=DropInPaths":
						return "", nil
					case "--property=ActiveState":
						if tc.running {
							return "active", nil
						}
						return "inactive", nil
					case "--property=UnitFileState":
						if tc.enabled {
							return "enabled", nil
						}
						return "disabled", nil
					}
				}
				if tc.failStart && args[0] == "start" && args[1] == systemdService {
					return "", fmt.Errorf("start failed")
				}
				return "", nil
			}
			err := migrateSystemd(dir, "/opt/agent", "/var/lib/agent", run)
			if (err != nil) != tc.failStart {
				t.Fatalf("unexpected result: %v", err)
			}
			kept, removed := fresh, old
			if tc.failStart {
				kept, removed = old, fresh
			}
			content, err := os.ReadFile(kept)
			if err != nil || string(content) != unit {
				t.Fatalf("unit not preserved: %v", err)
			}
			if _, err := os.Stat(removed); !os.IsNotExist(err) {
				t.Fatal("duplicate registration remains")
			}
			joined := strings.Join(actions, "\n")
			if !tc.running && strings.Contains(joined, "start "+systemdService) {
				t.Fatal("stopped service was started")
			}
			if !tc.enabled && strings.Contains(joined, "enable "+systemdService) {
				t.Fatal("autostart was enabled")
			}
			if tc.failStart && !strings.HasSuffix(joined, "start "+legacySystemdService) {
				t.Fatal("old service was not restarted")
			}
		})
	}
}

func TestSystemdMigrationRejectsForeignOrDuplicateUnits(t *testing.T) {
	for _, duplicate := range []bool{false, true} {
		dir := t.TempDir()
		os.WriteFile(filepath.Join(dir, legacySystemdService), []byte("ExecStart=/other/service"), 0644)
		if duplicate {
			os.WriteFile(filepath.Join(dir, systemdService), []byte("keep"), 0644)
		}
		err := migrateSystemd(dir, "/opt/agent", "/data", func(...string) (string, error) {
			t.Fatal("must not control a foreign or duplicate service")
			return "", nil
		})
		if err == nil {
			t.Fatal("unsafe registration accepted")
		}
		if _, err := os.Stat(filepath.Join(dir, legacySystemdService)); err != nil {
			t.Fatal("old unit removed")
		}
	}
}
