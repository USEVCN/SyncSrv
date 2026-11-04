package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func windowsWatchFixture(t *testing.T, path string) (chan Event, chan error, func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	events, failures := make(chan Event, 16384), make(chan error, 16)
	stop, err := watch(ctx, []Root{{JobID: "test", Path: path}}, func(e Event) { events <- e }, func(e error) { failures <- e })
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		cancel()
		done := make(chan struct{})
		go func() { stop(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Error("watch cancellation deadlocked")
		}
	})
	return events, failures, stop
}

func awaitWindowsPath(t *testing.T, events <-chan Event, failures <-chan error, paths ...string) {
	t.Helper()
	wanted := map[string]bool{}
	for _, path := range paths {
		wanted[path] = true
	}
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for len(wanted) > 0 {
		select {
		case e := <-events:
			delete(wanted, e.Path)
		case err := <-failures:
			t.Fatal(err)
		case <-timer.C:
			t.Fatalf("missing events (possible watcher deadlock): %v", wanted)
		}
	}
}

func TestWindowsWatcherDeleteRenameRecreateWhileRunning(t *testing.T) {
	root := t.TempDir()
	old := filepath.Join(root, "已有目录", "子目录")
	if err := os.MkdirAll(old, 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(old, "file.txt"), []byte("existing"), 0600); err != nil {
		t.Fatal(err)
	}
	events, failures, _ := windowsWatchFixture(t, root)
	if err := os.Rename(filepath.Join(root, "已有目录"), filepath.Join(root, "renamed")); err != nil {
		t.Fatalf("watch blocked rename: %v", err)
	}
	awaitWindowsPath(t, events, failures, "已有目录", "renamed")
	if err := os.RemoveAll(filepath.Join(root, "renamed")); err != nil {
		t.Fatalf("watch blocked recursive deletion: %v", err)
	}
	awaitWindowsPath(t, events, failures, "renamed")
	if err := os.MkdirAll(filepath.Join(root, "renamed", "new", "deep"), 0700); err != nil {
		t.Fatalf("deleted directory remains held open: %v", err)
	}
	if err := os.WriteFile(filepath.Join(root, "renamed", "new", "deep", "next.txt"), []byte("next"), 0600); err != nil {
		t.Fatal(err)
	}
	awaitWindowsPath(t, events, failures, "renamed/new/deep/next.txt")
	if err := os.RemoveAll(filepath.Join(root, "renamed")); err != nil {
		t.Fatal(err)
	}
	awaitWindowsPath(t, events, failures, "renamed")
}

func TestWindowsWatcherBurstDoesNotDeadlockOrKeepChildHandles(t *testing.T) {
	root := t.TempDir()
	events, failures, _ := windowsWatchFixture(t, root)
	for i := 0; i < 24; i++ {
		dir := filepath.Join(root, fmt.Sprintf("burst-%d", i))
		if err := os.MkdirAll(filepath.Join(dir, "nested", "deep"), 0700); err != nil {
			t.Fatal(err)
		}
		for j := 0; j < 8; j++ {
			if err := os.WriteFile(filepath.Join(dir, "nested", "deep", fmt.Sprintf("%d.txt", j)), []byte("data"), 0600); err != nil {
				t.Fatal(err)
			}
		}
		if err := os.Rename(dir, dir+"-moved"); err != nil {
			t.Fatal(err)
		}
		if err := os.RemoveAll(dir + "-moved"); err != nil {
			t.Fatalf("iteration %d: watcher retained child handle: %v", i, err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "still-watching.txt"), []byte("alive"), 0600); err != nil {
		t.Fatal(err)
	}
	awaitWindowsPath(t, events, failures, "still-watching.txt")
}

func TestWindowsWatcherDetectsRootRename(t *testing.T) {
	root := filepath.Join(t.TempDir(), "source")
	if err := os.Mkdir(root, 0700); err != nil {
		t.Fatal(err)
	}
	_, failures, _ := windowsWatchFixture(t, root)
	if err := os.Rename(root, root+"-moved"); err != nil {
		t.Fatalf("watch blocked root rename: %v", err)
	}
	select {
	case <-failures:
	case <-time.After(5 * time.Second):
		t.Fatal("renamed root did not invalidate coverage")
	}
}

func TestWindowsWatcherCancelIdleReadAndStopRepeatedly(t *testing.T) {
	root := t.TempDir()
	for i := 0; i < 20; i++ {
		_, _, stop := windowsWatchFixture(t, root)
		done := make(chan struct{})
		go func() { stop(); stop(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("idle ReadDirectoryChangesW did not cancel")
		}
	}
}
