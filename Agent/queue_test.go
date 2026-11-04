package main

import (
	"context"
	"fmt"
	bolt "go.etcd.io/bbolt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestAcknowledgementDoesNotDeleteNewerChange(t *testing.T) {
	q, e := openQueue(t.TempDir())
	if e != nil {
		t.Fatal(e)
	}
	defer q.db.Close()
	event := Event{JobID: "job", Path: "file", Kind: "CHANGE"}
	if e = q.add([]Event{event}); e != nil {
		t.Fatal(e)
	}
	sent, e := q.batch(100)
	if e != nil {
		t.Fatal(e)
	}
	if e = q.add([]Event{event}); e != nil {
		t.Fatal(e)
	}
	if e = q.ack(sent, []string{sent.Events[0].ID}); e != nil {
		t.Fatal(e)
	}
	newer, _ := q.batch(100)
	if len(newer.Events) != 1 || newer.Events[0].ID == sent.Events[0].ID {
		t.Fatal("new generation was removed")
	}
	if e = q.ack(newer, []string{"unrequested"}); e == nil {
		t.Fatal("unknown acknowledgement accepted")
	}
	if e = q.ack(newer, []string{newer.Events[0].ID}); e != nil {
		t.Fatal(e)
	}
	if n, _ := q.count(); n != 0 {
		t.Fatal(n)
	}
}
func TestDurableQueueSurvivesReopenAndCoalescesPaths(t *testing.T) {
	dir := t.TempDir()
	q, e := openQueue(dir)
	if e != nil {
		t.Fatal(e)
	}
	for i := 0; i < 20; i++ {
		if e = q.add([]Event{{JobID: "job", Path: "file", Kind: "CHANGE"}}); e != nil {
			t.Fatal(e)
		}
	}
	first, _ := q.batch(100)
	if first.Events[0].Coalesced != 19 {
		t.Fatal("local coalescing evidence missing")
	}
	q.db.Close()
	q, e = openQueue(dir)
	if e != nil {
		t.Fatal(e)
	}
	defer q.db.Close()
	resumed, _ := q.batch(100)
	if resumed.Epoch != first.Epoch || len(resumed.Events) != 1 || resumed.Events[0].ID != first.Events[0].ID {
		t.Fatal("outbox changed after restart")
	}
}
func TestNativeWatcherObservesNestedFile(t *testing.T) {
	root := t.TempDir()
	root, _ = filepath.EvalSymlinks(root)
	if e := os.Mkdir(filepath.Join(root, "nested"), 0700); e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	events := make(chan Event, 100)
	errors := make(chan error, 10)
	stop, e := watch(ctx, []Root{{JobID: "job", Path: root}}, func(e Event) { events <- e }, func(e error) { errors <- e })
	if e != nil {
		t.Fatal(e)
	}
	defer stop()
	path := filepath.Join(root, "nested", "file")
	if e = os.WriteFile(path, []byte("first"), 0600); e != nil {
		t.Fatal(e)
	}
	deadline := time.After(8 * time.Second)
	for {
		select {
		case e := <-events:
			if e.Path == "nested/file" {
				return
			}
		case e := <-errors:
			t.Fatal(e)
		case <-deadline:
			t.Fatal("native file event not received")
		}
	}
}
func TestCenterTrustAndPathOverlap(t *testing.T) {
	for _, server := range []string{"http://example.com", "https://user:password@example.com", "https://example.com/path", "file:///tmp/server"} {
		if _, e := validateServer(server); e == nil {
			t.Fatal(server)
		}
	}
	for _, server := range []string{"https://example.com:443", "http://127.0.0.1:18021"} {
		if _, e := validateServer(server); e != nil {
			t.Fatal(e)
		}
	}
	if !pathsOverlap("/tmp/a", "/tmp/a/db") || pathsOverlap("/tmp/a", "/tmp/abc") {
		t.Fatal("path boundary")
	}
}

func TestRemovedAssignmentsRetainEvidenceWithoutBlockingOtherJobs(t *testing.T) {
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	if err = q.add([]Event{{JobID: "old", Path: "a", Kind: "CHANGE"}, {JobID: "active", Path: "b", Kind: "CHANGE"}}); err != nil {
		t.Fatal(err)
	}
	retired, err := q.retainJobs([]Root{{JobID: "active"}})
	if err != nil || retired != 1 {
		t.Fatalf("retired=%d err=%v", retired, err)
	}
	if err = q.retireEvent(Event{JobID: "old", Path: "late", Kind: "CHANGE"}); err != nil {
		t.Fatal(err)
	}
	batch, err := q.batch(100)
	if err != nil || len(batch.Events) != 1 || batch.Events[0].JobID != "active" {
		t.Fatalf("%+v %v", batch, err)
	}
	if count, err := q.count(); err != nil || count != 1 {
		t.Fatalf("count=%d err=%v", count, err)
	}
	if err = q.db.View(func(tx *bolt.Tx) error {
		if n := tx.Bucket([]byte("superseded-configuration")).Stats().KeyN; n != 2 {
			t.Errorf("retained=%d", n)
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func TestDataDirectoryDoesNotChangeUnrelatedDirectories(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "unrelated"), []byte("keep"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := prepareDataDirectory(dir); err == nil {
		t.Fatal("accepted unrelated directory")
	}
	if err := prepareDataDirectory(filepath.VolumeName(dir) + string(filepath.Separator)); err == nil {
		t.Fatal("accepted filesystem root")
	}
	fresh := filepath.Join(t.TempDir(), "new")
	if err := prepareDataDirectory(fresh); err != nil {
		t.Fatal(err)
	}
	if err := command([]string{"unknown", "--data-dir", filepath.Join(dir, "must-not-create")}); err == nil {
		t.Fatal("unknown command accepted")
	}
	if _, err := os.Stat(filepath.Join(dir, "must-not-create")); !os.IsNotExist(err) {
		t.Fatal("unknown command mutated filesystem")
	}
}

func TestFailedBatchCannotStarveLaterFilesOrTasks(t *testing.T) {
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	events := []Event{{JobID: "z-healthy", Path: "ready"}}
	for i := 0; i < 300; i++ {
		events = append(events, Event{JobID: "a-failing", Path: fmt.Sprintf("locked-%03d", i)})
	}
	if err = q.add(events); err != nil {
		t.Fatal(err)
	}
	first, err := q.batchFor(256, map[string]bool{"a-failing": true, "z-healthy": true})
	if err != nil {
		t.Fatal(err)
	}
	if len(first.Events) != 256 {
		t.Fatal(len(first.Events))
	}
	second, err := q.batchFor(256, map[string]bool{"a-failing": true, "z-healthy": true})
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, event := range second.Events {
		if event.JobID == "z-healthy" {
			found = true
		}
	}
	if !found {
		t.Fatal("failed first batch starved the later healthy task")
	}
	if n, err := q.count(); err != nil || n != 301 {
		t.Fatalf("rotation removed unacknowledged data: %d %v", n, err)
	}
}
func TestQueueRotationHandlesAcknowledgedCursorAndDisabledJobs(t *testing.T) {
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	q.add([]Event{{JobID: "a", Path: "a"}, {JobID: "b", Path: "b"}, {JobID: "c", Path: "c"}})
	first, _ := q.batchFor(1, map[string]bool{"a": true})
	if err = q.ack(first, []string{first.Events[0].ID}); err != nil {
		t.Fatal(err)
	}
	next, err := q.batchFor(10, map[string]bool{"c": true})
	if err != nil || len(next.Events) != 1 || next.Events[0].JobID != "c" {
		t.Fatalf("bad wrap after cursor deletion: %+v %v", next, err)
	}
	empty, err := q.batchFor(10, map[string]bool{})
	if err != nil || len(empty.Events) != 0 {
		t.Fatalf("disabled task selected: %+v %v", empty, err)
	}
}

func TestQueueRotationDoesNotSkipThePayloadBoundaryEvent(t *testing.T) {
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	var events []Event
	for i := 0; i < 256; i++ {
		events = append(events, Event{JobID: "job", Path: fmt.Sprintf("%03d-%s", i, strings.Repeat("p", 4090))})
	}
	if err = q.add(events); err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for i := 0; i < 4; i++ {
		batch, err := q.batch(256)
		if err != nil {
			t.Fatal(err)
		}
		for _, event := range batch.Events {
			seen[event.ID] = true
		}
	}
	if len(seen) != len(events) {
		t.Fatalf("payload boundary skipped events: got %d / %d", len(seen), len(events))
	}
}
