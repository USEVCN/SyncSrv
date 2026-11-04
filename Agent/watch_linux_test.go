package main

import (
	"context"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestLinuxRegistrationDoesNotBlockOtherRoots(t *testing.T) {
	roots := []Root{{JobID: "slow", Path: t.TempDir()}, {JobID: "fast", Path: t.TempDir()}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	events, failures := make(chan Event, 1024), make(chan error, 16)
	entered, release := make(chan struct{}), make(chan struct{})
	var blocked sync.Once
	arrival := filepath.Join(roots[0].Path, "arriving")
	register := func(ctx context.Context, w *linuxWatchPaths, path string) error {
		if path == arrival {
			blocked.Do(func() { close(entered) })
			select {
			case <-release:
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		return registerLinuxTree(ctx, w, path)
	}
	stop, err := watchLinux(ctx, roots, func(e Event) { events <- e }, func(e error) { failures <- e }, register)
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	if err = os.MkdirAll(filepath.Join(arrival, "deep"), 0700); err != nil {
		t.Fatal(err)
	}
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("registration did not start")
	}
	if err = os.WriteFile(filepath.Join(roots[1].Path, "immediate.txt"), []byte("data"), 0600); err != nil {
		t.Fatal(err)
	}
	awaitUnixWatchPath(t, events, failures, "fast", "immediate.txt")
	// Drain the arrival notification so the next one proves reconciliation after
	// the delayed registration, including descendants created in its blind window.
	for len(events) > 0 {
		<-events
	}
	if err = os.WriteFile(filepath.Join(arrival, "deep", "before.txt"), []byte("before watch"), 0600); err != nil {
		t.Fatal(err)
	}
	close(release)
	awaitUnixWatchPath(t, events, failures, "slow", "arriving")
	if err = os.WriteFile(filepath.Join(arrival, "deep", "after.txt"), []byte("after watch"), 0600); err != nil {
		t.Fatal(err)
	}
	awaitUnixWatchPath(t, events, failures, "slow", "arriving/deep/after.txt")
}

func TestLinuxStopsBlockedRegistration(t *testing.T) {
	root := t.TempDir()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	entered := make(chan struct{})
	var once sync.Once
	register := func(ctx context.Context, w *linuxWatchPaths, path string) error {
		if path != root {
			once.Do(func() { close(entered) })
			<-ctx.Done()
			return ctx.Err()
		}
		return registerLinuxTree(ctx, w, path)
	}
	stop, err := watchLinux(ctx, []Root{{JobID: "job", Path: root}}, func(Event) {}, func(error) {}, register)
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	if err = os.Mkdir(filepath.Join(root, "new"), 0700); err != nil {
		t.Fatal(err)
	}
	select {
	case <-entered:
	case <-time.After(3 * time.Second):
		t.Fatal("registration did not start")
	}
	done := make(chan struct{})
	go func() { cancel(); stop(); stop(); close(done) }()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("registration did not cancel")
	}
}
