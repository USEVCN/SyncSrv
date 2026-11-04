package main

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestMultipleRootsKeepIdenticalRelativePathsAndAcknowledgementsSeparate(t *testing.T) {
	roots := []Root{{JobID: "documents", Path: t.TempDir()}, {JobID: "projects", Path: t.TempDir()}}
	for i := range roots {
		real, err := filepath.EvalSymlinks(roots[i].Path)
		if err != nil {
			t.Fatal(err)
		}
		roots[i].Path = real
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	events, failures := make(chan Event, 100), make(chan error, 10)
	stop, err := watch(ctx, roots, func(e Event) { events <- e }, func(e error) { failures <- e })
	if err != nil {
		t.Fatal(err)
	}
	defer stop()
	for _, root := range roots {
		if err = os.WriteFile(filepath.Join(root.Path, "same.txt"), []byte(root.JobID), 0600); err != nil {
			t.Fatal(err)
		}
	}
	observed := map[string]Event{}
	deadline := time.After(8 * time.Second)
	for len(observed) < 2 {
		select {
		case e := <-events:
			if e.Path == "same.txt" {
				observed[e.JobID] = e
			}
		case e := <-failures:
			t.Fatal(e)
		case <-deadline:
			t.Fatalf("missing task notification: %v", observed)
		}
	}
	queue, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer queue.db.Close()
	if err = queue.add([]Event{observed["documents"], observed["projects"]}); err != nil {
		t.Fatal(err)
	}
	batch, err := queue.batch(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(batch.Events) != 2 {
		t.Fatal("distinct tasks were coalesced")
	}
	for _, e := range batch.Events {
		if e.JobID == "documents" {
			if err = queue.ack(batch, []string{e.ID}); err != nil {
				t.Fatal(err)
			}
		}
	}
	pending, err := queue.batch(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(pending.Events) != 1 || pending.Events[0].JobID != "projects" {
		t.Fatalf("other task lost: %+v", pending)
	}
}
