package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func storageQueueFixture(id int) StorageWork {
	return StorageWork{ID: fmt.Sprintf("%032x", id), Kind: "HASH", ParallelPolling: true,
		Source: BoundEndpoint{Endpoint: TargetEndpoint{Protocol: "LOCAL", Root: "/fixture"}}, From: fmt.Sprint(id)}
}
func awaitStorage[T any](t *testing.T, c <-chan T) T {
	t.Helper()
	select {
	case v := <-c:
		return v
	case <-time.After(3 * time.Second):
		t.Fatal("storage scheduler timed out")
		var zero T
		return zero
	}
}
func TestStorageDispatchRunsBoundedParallelCommandsAndExcludesActiveIDs(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	started, finished := make(chan string, 8), make(chan struct{})
	release := make(chan struct{})
	var polled atomic.Int32
	var activeSeen atomic.Bool
	go func() {
		dispatchStorage(ctx, 4, func(ctx context.Context, ids []string) (StorageWork, error) {
			n := int(polled.Add(1))
			if n == 2 && slices.Contains(ids, storageQueueFixture(1).ID) {
				activeSeen.Store(true)
			}
			if n > 5 {
				<-ctx.Done()
				return StorageWork{}, ctx.Err()
			}
			return storageQueueFixture(n), nil
		}, func(ctx context.Context, work StorageWork) error {
			started <- work.ID
			select {
			case <-release:
			case <-ctx.Done():
			}
			return ctx.Err()
		}, func(err error) { t.Error(err) })
		close(finished)
	}()
	for i := 0; i < 4; i++ {
		awaitStorage(t, started)
	}
	if polled.Load() != 4 || !activeSeen.Load() {
		t.Fatalf("claims=%d activeIds=%v", polled.Load(), activeSeen.Load())
	}
	close(release)
	awaitStorage(t, started)
	cancel()
	awaitStorage(t, finished)
}
func TestStorageDispatchDrainsConflictingAndLimitedCommands(t *testing.T) {
	for _, kind := range []string{"same-path", "first-limited", "second-limited"} {
		t.Run(kind, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			first, second := storageQueueFixture(1), storageQueueFixture(2)
			switch kind {
			case "same-path":
				second.From = first.From
			case "first-limited":
				first.BandwidthMiB = 1
			case "second-limited":
				second.BandwidthMiB = 1
			}
			ready, claimed, release, done := make(chan string, 2), make(chan struct{}), make(chan struct{}), make(chan struct{})
			claims := 0
			go func() {
				dispatchStorage(ctx, 4, func(ctx context.Context, _ []string) (StorageWork, error) {
					claims++
					if claims == 1 {
						return first, nil
					}
					if claims == 2 {
						close(claimed)
						return second, nil
					}
					<-ctx.Done()
					return StorageWork{}, ctx.Err()
				}, func(ctx context.Context, work StorageWork) error {
					ready <- work.ID
					if work.ID == first.ID {
						select {
						case <-release:
						case <-ctx.Done():
						}
					}
					return nil
				}, func(err error) { t.Error(err) })
				close(done)
			}()
			if awaitStorage(t, ready) != first.ID {
				t.Fatal("out of order first command")
			}
			awaitStorage(t, claimed)
			select {
			case id := <-ready:
				t.Fatalf("conflicting command started before drain: %s", id)
			case <-time.After(50 * time.Millisecond):
			}
			close(release)
			if awaitStorage(t, ready) != second.ID {
				t.Fatal("pending command did not resume")
			}
			cancel()
			awaitStorage(t, done)
		})
	}
}
func TestStorageDispatchOldCenterStaysSerialAndWaitsForWritersOnCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	started, stopping, drain, done := make(chan struct{}), make(chan struct{}), make(chan struct{}), make(chan struct{})
	var claims atomic.Int32
	go func() {
		dispatchStorage(ctx, 4, func(ctx context.Context, ids []string) (StorageWork, error) {
			if len(ids) != 0 {
				t.Error("sent an unsupported activeIds field to an old center")
			}
			work := storageQueueFixture(int(claims.Add(1)))
			work.ParallelPolling = false
			return work, nil
		}, func(ctx context.Context, work StorageWork) error {
			close(started)
			<-ctx.Done()
			close(stopping)
			<-drain
			return ctx.Err()
		}, func(err error) { t.Error(err) })
		close(done)
	}()
	awaitStorage(t, started)
	cancel()
	awaitStorage(t, stopping)
	select {
	case <-done:
		t.Fatal("returned while a writer was still draining")
	case <-time.After(50 * time.Millisecond):
	}
	close(drain)
	awaitStorage(t, done)
	if claims.Load() != 1 {
		t.Fatal("old-center command claimed more than once")
	}
}
func TestStorageDispatchRejectsConcurrentDuplicateClaim(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	started, duplicate, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var claims, executions atomic.Int32
	go func() {
		dispatchStorage(ctx, 4, func(context.Context, []string) (StorageWork, error) {
			if claims.Add(1) == 2 {
				close(duplicate)
			}
			return storageQueueFixture(1), nil
		}, func(ctx context.Context, _ StorageWork) error {
			if executions.Add(1) == 1 {
				close(started)
			}
			<-ctx.Done()
			return ctx.Err()
		}, func(err error) { t.Error(err) })
		close(done)
	}()
	awaitStorage(t, started)
	awaitStorage(t, duplicate)
	cancel()
	awaitStorage(t, done)
	if executions.Load() != 1 {
		t.Fatal("duplicate ID started a second writer")
	}
}

func TestStorageDispatchCopiesAndPersistsIndependentReceipts(t *testing.T) {
	a, b := boundFixture(t), boundFixture(t)
	dir := t.TempDir()
	q, err := openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]bool{"proceed": true}})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, err := newClient(Identity{Server: server.URL, PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	var work []StorageWork
	data := []byte(strings.Repeat("verified archive", 4096))
	for i := 1; i <= 4; i++ {
		name := fmt.Sprint(i)
		if err = os.WriteFile(filepath.Join(a.Endpoint.Root, name), data, 0600); err != nil {
			t.Fatal(err)
		}
		w := storageQueueFixture(i)
		w.Kind, w.Source, w.Target, w.To, w.Size, w.Hash = "COPY", a, &b, name, int64(len(data)), hashData(data)
		work = append(work, w)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	gate, complete, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	var entered, settled atomic.Int32
	next := 0
	go func() {
		dispatchStorage(ctx, 4, func(ctx context.Context, _ []string) (StorageWork, error) {
			if next == len(work) {
				<-ctx.Done()
				return StorageWork{}, ctx.Err()
			}
			w := work[next]
			next++
			return w, nil
		}, func(ctx context.Context, w StorageWork) error {
			if entered.Add(1) == 4 {
				close(gate)
			}
			select {
			case <-gate:
			case <-ctx.Done():
				return ctx.Err()
			}
			err := client.executeStorage(ctx, q, dir, w)
			if err != nil {
				t.Error(err)
			}
			if settled.Add(1) == 4 {
				close(complete)
			}
			return err
		}, func(err error) { t.Error(err) })
		close(done)
	}()
	awaitStorage(t, complete)
	cancel()
	awaitStorage(t, done)
	for _, w := range work {
		got, err := os.ReadFile(filepath.Join(b.Endpoint.Root, w.To))
		if err != nil || string(got) != string(data) {
			t.Fatalf("copy %s differs: %v", w.ID, err)
		}
		if err = q.storageRecord(w.ID, func(item *storageJournal) error {
			if item.Result == nil || item.Result.Status != "COMPLETED" || item.Result.Hash != w.Hash {
				return fmt.Errorf("missing durable completion receipt for %s", w.ID)
			}
			return nil
		}); err != nil {
			t.Fatal(err)
		}
	}
}
