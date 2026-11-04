package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	bolt "go.etcd.io/bbolt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestNoticeAckCannotRemoveUploadOrNewerNoticeAndSurvivesRestart(t *testing.T) {
	dir := t.TempDir()
	q, err := openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err = q.add([]Event{{JobID: "job", Path: "file", Kind: "CHANGE"}}); err != nil {
		t.Fatal(err)
	}
	first, _ := q.noticeBatch(10)
	if err = q.add([]Event{{JobID: "job", Path: "file", Kind: "CHANGE"}}); err != nil {
		t.Fatal(err)
	}
	if err = q.acknowledgeNotices(first, []string{first.Events[0].ID}); err != nil {
		t.Fatal(err)
	}
	q.db.Close()
	q, err = openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	latest, _ := q.noticeBatch(10)
	if len(latest.Events) != 1 || latest.Events[0].ID == first.Events[0].ID {
		t.Fatal("new notice lost")
	}
	if err = q.acknowledgeNotices(latest, []string{latest.Events[0].ID}); err != nil {
		t.Fatal(err)
	}
	pending, _ := q.count()
	if pending != 1 {
		t.Fatal("notice acknowledgement removed pending upload")
	}
	if err = q.ack(latest, []string{latest.Events[0].ID}); err != nil {
		t.Fatal(err)
	}
	pending, _ = q.count()
	if pending != 0 {
		t.Fatal("upload acknowledgement not applied")
	}
}

func TestNewNoticeReachesCenterWhileUploadRequestIsBlocked(t *testing.T) {
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	source := t.TempDir()
	if err = os.WriteFile(filepath.Join(source, "file"), []byte("data"), 0600); err != nil {
		t.Fatal(err)
	}
	if err = q.add([]Event{{JobID: "job", Path: "file", Kind: "CHANGE"}}); err != nil {
		t.Fatal(err)
	}
	batch, _ := q.batch(10)
	blocked := make(chan struct{})
	notices := make(chan string, 10)
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		data := any(map[string]any{})
		switch r.URL.Path {
		case "/agent/v1/changes/notice":
			var batch Batch
			json.NewDecoder(r.Body).Decode(&batch)
			ids := []string{}
			for _, e := range batch.Events {
				ids = append(ids, e.ID)
				notices <- e.ID
			}
			data = map[string]any{"acknowledged": ids}
		case "/agent/v1/changes/start":
			data = map[string]any{"allowed": true}
		case "/agent/v1/push/begin":
			close(blocked)
			<-release
			w.WriteHeader(503)
			json.NewEncoder(w).Encode(map[string]any{"success": false, "message": "fixture stopped"})
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": data})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, _ := newClient(Identity{ID: "agent", Server: server.URL, PrivateKey: key})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	notifyDone := make(chan struct{})
	go func() { defer close(notifyDone); client.notifyChanges(ctx, q, func(err error) { t.Error(err) }) }()
	done := make(chan error, 1)
	go func() {
		_, err := client.deliver(ctx, []Root{{JobID: "job", Path: source, SourceMode: "AGENT", Enabled: true}}, batch)
		done <- err
	}()
	select {
	case <-blocked:
	case <-time.After(3 * time.Second):
		close(release)
		t.Fatal("upload did not start")
	}
	if err = q.add([]Event{{JobID: "job", Path: "file", Kind: "CHANGE"}}); err != nil {
		close(release)
		t.Fatal(err)
	}
	newest, _ := q.noticeBatch(10)
	want := newest.Events[0].ID
	deadline := time.After(3 * time.Second)
	for {
		select {
		case id := <-notices:
			if id == want {
				close(release)
				if err := <-done; err == nil {
					t.Fatal("expected failed upload fixture")
				}
				cancel()
				<-notifyDone
				return
			}
		case <-deadline:
			close(release)
			t.Fatal("notification blocked behind file transfer")
		}
	}
}

func TestConnectionModeNoticeCannotBlockDirectJobNotice(t *testing.T) {
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	if err = q.add([]Event{{JobID: "a-connection", Path: "", Kind: "RESTART"}, {JobID: "z-direct", Path: "", Kind: "RESTART"}}); err != nil {
		t.Fatal(err)
	}
	raw, _ := json.Marshal(Configuration{Status: "APPROVED", Roots: []Root{{JobID: "a-connection", SourceMode: "CONNECTION"}, {JobID: "z-direct", SourceMode: "AGENT"}}})
	if err = q.db.Update(func(tx *bolt.Tx) error { return tx.Bucket(metaBucket).Put([]byte("configuration"), raw) }); err != nil {
		t.Fatal(err)
	}
	batch, err := q.noticeBatch(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(batch.Events) != 1 || batch.Events[0].JobID != "z-direct" {
		t.Fatal("legacy notification poisoned early delivery")
	}
}
