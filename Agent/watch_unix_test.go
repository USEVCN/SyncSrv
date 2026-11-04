//go:build linux || darwin

package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func awaitUnixWatchPath(t *testing.T, events <-chan Event, failures <-chan error, job, path string) {
	t.Helper()
	timer := time.NewTimer(5 * time.Second)
	defer timer.Stop()
	for {
		select {
		case e := <-events:
			if e.JobID == job && e.Path == path {
				return
			}
		case err := <-failures:
			t.Fatal(err)
		case <-timer.C:
			t.Fatalf("missing native notification %s:%s", job, path)
		}
	}
}

func unixWatchFixture(t *testing.T, root string) (chan Event, chan error, context.CancelFunc, func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	events, failures := make(chan Event, 16384), make(chan error, 64)
	stop, err := watch(ctx, []Root{{JobID: "job", Path: root}}, func(e Event) { events <- e }, func(e error) { failures <- e })
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
			t.Error("native watcher stop blocked")
		}
	})
	return events, failures, cancel, stop
}

func TestUnixNativeWatcherRenameDeleteAndRecreate(t *testing.T) {
	root, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err = os.MkdirAll(filepath.Join(root, "old", "nested"), 0700); err != nil {
		t.Fatal(err)
	}
	events, failures, _, _ := unixWatchFixture(t, root)
	if err = os.Rename(filepath.Join(root, "old"), filepath.Join(root, "renamed")); err != nil {
		t.Fatal(err)
	}
	awaitUnixWatchPath(t, events, failures, "job", "renamed")
	if err = os.RemoveAll(filepath.Join(root, "renamed")); err != nil {
		t.Fatal(err)
	}
	awaitUnixWatchPath(t, events, failures, "job", "renamed")
	if err = os.MkdirAll(filepath.Join(root, "renamed", "fresh"), 0700); err != nil {
		t.Fatal(err)
	}
	// An immediate file can precede inotify child registration. The directory
	// scope is observable; subsequently verify steady-state descendant watching.
	awaitUnixWatchPath(t, events, failures, "job", "renamed")
	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		if err = os.WriteFile(filepath.Join(root, "renamed", "fresh", "alive.txt"), []byte(time.Now().String()), 0600); err != nil {
			t.Fatal(err)
		}
		timer := time.NewTimer(100 * time.Millisecond)
		for {
			select {
			case e := <-events:
				if e.Path == "renamed/fresh/alive.txt" {
					timer.Stop()
					return
				}
			case err := <-failures:
				timer.Stop()
				t.Fatal(err)
			case <-timer.C:
				goto again
			}
		}
	again:
	}
	t.Fatal("recreated descendant is not watched")
}

func TestUnixNativeWatcherCancelUnderLoadAndRepeatedStop(t *testing.T) {
	for attempt := 0; attempt < 5; attempt++ {
		root, err := filepath.EvalSymlinks(t.TempDir())
		if err != nil {
			t.Fatal(err)
		}
		_, _, cancel, stop := unixWatchFixture(t, root)
		producer := make(chan struct{})
		go func() {
			defer close(producer)
			for i := 0; i < 300; i++ {
				_ = os.WriteFile(filepath.Join(root, fmt.Sprintf("file-%d", i)), []byte("change"), 0600)
			}
		}()
		time.Sleep(10 * time.Millisecond)
		done := make(chan struct{})
		go func() { cancel(); stop(); stop(); close(done) }()
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("shutdown blocked while events were arriving")
		}
		<-producer
	}
}

func TestUnixNativeWatcherDetectsRootRename(t *testing.T) {
	parent, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(parent, "source")
	if err = os.Mkdir(root, 0700); err != nil {
		t.Fatal(err)
	}
	_, failures, _, _ := unixWatchFixture(t, root)
	if err = os.Rename(root, root+"-moved"); err != nil {
		t.Fatal(err)
	}
	select {
	case <-failures:
	case <-time.After(5 * time.Second):
		t.Fatal("root rename did not invalidate coverage")
	}
}

func TestUnixNativeWatcherMovedDirectoryKeepsWatchingDescendants(t *testing.T) {
	parent, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	root := filepath.Join(parent, "source")
	if err = os.MkdirAll(filepath.Join(root, "old", "nested", "deep"), 0700); err != nil {
		t.Fatal(err)
	}
	events, failures, _, _ := unixWatchFixture(t, root)
	if err = os.Rename(filepath.Join(root, "old"), filepath.Join(root, "renamed")); err != nil {
		t.Fatal(err)
	}
	awaitUnixWatchPath(t, events, failures, "job", "renamed")
	// The moved-in scope itself must be registered before relying on later events.
	time.Sleep(150 * time.Millisecond)
	if err = os.WriteFile(filepath.Join(root, "renamed", "nested", "deep", "alive.txt"), []byte("still watched"), 0600); err != nil {
		t.Fatal(err)
	}
	awaitUnixWatchPath(t, events, failures, "job", "renamed/nested/deep/alive.txt")
}
