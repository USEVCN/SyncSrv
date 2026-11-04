package main

import (
	"io"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestUnixToolReplacementPreservesRunningScript(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows preserves the original DACL instead")
	}
	path := filepath.Join(t.TempDir(), "agent-control.sh")
	if err := os.WriteFile(path, []byte("old controller"), 0755); err != nil {
		t.Fatal(err)
	}
	running, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer running.Close()
	if err = writeTool(path, []byte("new controller"), 0755); err != nil {
		t.Fatal(err)
	}
	old, err := io.ReadAll(running)
	if err != nil || string(old) != "old controller" {
		t.Fatalf("running script was changed: %q, %v", old, err)
	}
	fresh, err := os.ReadFile(path)
	if err != nil || string(fresh) != "new controller" {
		t.Fatalf("next invocation did not get the new script: %q, %v", fresh, err)
	}
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0755 {
		t.Fatalf("executable permissions lost: %v", err)
	}
}
