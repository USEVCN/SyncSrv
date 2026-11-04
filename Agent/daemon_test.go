package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	bolt "go.etcd.io/bbolt"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestDaemonKeepsHealthyCoverageAndDoesNotRescanOnSettingsUpdate(t *testing.T) {
	dir := t.TempDir()
	roots := []Root{{JobID: "a-unavailable", Path: filepath.Join(t.TempDir(), "missing"), Enabled: true, SourceMode: "CONNECTION"}, {JobID: "b-healthy", Path: t.TempDir(), Enabled: true, SourceMode: "CONNECTION"}}
	var configs, delivered, starts atomic.Int64
	seen := make(chan map[string]json.RawMessage, 10)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var data any = map[string]any{}
		switch r.URL.Path {
		case "/agent/v1/config":
			count := configs.Add(1)
			updated := append([]Root(nil), roots...)
			updated[1].TransferThreads = int(count)
			data = Configuration{Version: count, Status: "APPROVED", Roots: updated}
		case "/agent/v1/events":
			var batch Batch
			json.NewDecoder(r.Body).Decode(&batch)
			var ids []string
			for _, event := range batch.Events {
				if event.JobID != "b-healthy" {
					t.Errorf("unavailable root was delivered: %+v", event)
				}
				ids = append(ids, event.ID)
			}
			delivered.Add(1)
			data = map[string]any{"acknowledged": ids}
		case "/agent/v1/heartbeat":
			var input map[string]json.RawMessage
			json.NewDecoder(r.Body).Decode(&input)
			seen <- input
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": data})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, err := newClient(Identity{ID: "a", Server: server.URL, PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- runWithWatcher(ctx, dir, client, func(_ context.Context, r []Root, _ func(Event), _ func(error)) (func(), error) {
			starts.Add(1)
			return func() {}, nil
		})
	}()
	deadline := time.After(5 * time.Second)
ready:
	for {
		select {
		case report := <-seen:
			var version int64
			json.Unmarshal(report["appliedVersion"], &version)
			if version < 2 {
				continue
			}
			var jobs []string
			json.Unmarshal(report["watchingJobs"], &jobs)
			if len(jobs) != 1 || jobs[0] != "b-healthy" {
				t.Errorf("inaccurate watcher coverage: %v", jobs)
			}
			var health string
			json.Unmarshal(report["error"], &health)
			if health == "" {
				t.Error("missing root reported no error")
			}
			break ready
		case <-deadline:
			t.Fatal("healthy source did not deliver while another root was unavailable")
		}
	}
	cancel()
	select {
	case err = <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("daemon shutdown timed out")
	}
	if starts.Load() != 1 || delivered.Load() != 1 {
		t.Fatalf("settings triggered unnecessary baseline: watches=%d batches=%d", starts.Load(), delivered.Load())
	}
}

func TestDaemonPersistsRevocationAndKeepsPendingQueue(t *testing.T) {
	dir := t.TempDir()
	q, err := openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	previous := Configuration{Version: 1, Status: "APPROVED", Roots: []Root{{JobID: "job", Path: t.TempDir(), Enabled: false, SourceMode: "AGENT"}}}
	raw, _ := json.Marshal(previous)
	q.db.Update(func(tx *bolt.Tx) error { return tx.Bucket(metaBucket).Put([]byte("configuration"), raw) })
	q.add([]Event{{JobID: "job", Path: "keep.txt"}})
	q.db.Close()
	revoked := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var data any = map[string]any{}
		if r.URL.Path == "/agent/v1/config" {
			data = Configuration{Version: 2, Status: "REVOKED"}
		}
		if r.URL.Path == "/agent/v1/heartbeat" {
			select {
			case revoked <- struct{}{}:
			default:
			}
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": data})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, _ := newClient(Identity{ID: "a", Server: server.URL, PrivateKey: key})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- runWithWatcher(ctx, dir, client, func(context.Context, []Root, func(Event), func(error)) (func(), error) { return func() {}, nil })
	}()
	select {
	case <-revoked:
	case <-time.After(5 * time.Second):
		t.Fatal("no revocation heartbeat")
	}
	cancel()
	select {
	case err = <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("shutdown timeout")
	}
	q, err = openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	var stored Configuration
	q.db.View(func(tx *bolt.Tx) error {
		return json.Unmarshal(tx.Bucket(metaBucket).Get([]byte("configuration")), &stored)
	})
	if stored.Status != "REVOKED" {
		t.Fatal("restart would reload old authorization")
	}
	pending, _ := q.batch(20)
	if len(pending.Events) < 1 {
		t.Fatal("revocation discarded pending source changes")
	}
	notice, _ := q.noticeBatch(20)
	if len(notice.Events) != 0 {
		t.Fatal("revoked agent continues issuing notices")
	}
}
