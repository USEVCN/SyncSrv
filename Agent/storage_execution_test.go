package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func boundFixture(t *testing.T) BoundEndpoint {
	t.Helper()
	root := t.TempDir()
	marker := ".syncsrv/device-bindings/test"
	if err := os.MkdirAll(filepath.Join(root, filepath.Dir(marker)), 0700); err != nil {
		t.Fatal(err)
	}
	token := []byte(strings.Repeat("a", 64))
	if err := os.WriteFile(filepath.Join(root, marker), token, 0600); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(token)
	return BoundEndpoint{Endpoint: TargetEndpoint{Protocol: "LOCAL", Root: root}, Marker: marker, MarkerHash: hex.EncodeToString(sum[:])}
}
func hashData(data []byte) string { sum := sha256.Sum256(data); return hex.EncodeToString(sum[:]) }
func TestStorageLocalCopyResumeAndHash(t *testing.T) {
	a, b := boundFixture(t), boundFixture(t)
	data := []byte(strings.Repeat("backup-history", 80000))
	os.WriteFile(filepath.Join(a.Endpoint.Root, "source"), data, 0600)
	os.WriteFile(filepath.Join(b.Endpoint.Root, "copy"), data[:127], 0600)
	var copied, verified int64
	work := StorageWork{ID: strings.Repeat("a", 32), Kind: "COPY", Source: a, From: "source", Target: &b, To: "copy", Size: int64(len(data)), Hash: hashData(data)}
	got, n, err := runStorageWork(context.Background(), work, func(_ string, c, v int64) { copied += c; verified += v })
	if err != nil || got != work.Hash || n != work.Size {
		t.Fatalf("copy %s %d %v", got, n, err)
	}
	if copied != work.Size || verified < work.Size {
		t.Fatalf("progress %d %d", copied, verified)
	}
	actual, _ := os.ReadFile(filepath.Join(b.Endpoint.Root, "copy"))
	if string(actual) != string(data) {
		t.Fatal("copy differs")
	}
	work.Kind = "HASH"
	work.Source = b
	work.From = "copy"
	work.Target = nil
	got, _, err = runStorageWork(context.Background(), work, func(string, int64, int64) {})
	if err != nil || got != hashData(data) {
		t.Fatalf("local checksum: %v", err)
	}
}
func TestStorageRejectsWrongMappingTraversalAndSymlink(t *testing.T) {
	a := boundFixture(t)
	a.MarkerHash = strings.Repeat("0", 64)
	if _, err := openBoundTarget(context.Background(), a); err == nil {
		t.Fatal("wrong mapping accepted")
	}
	for _, p := range []string{"../x", "/etc/passwd", "x/../../y", "x\\y", "x:ads"} {
		if safeStoragePath(p) {
			t.Fatal(p)
		}
	}
	a = boundFixture(t)
	outside := t.TempDir()
	os.WriteFile(filepath.Join(outside, "secret"), []byte("private"), 0600)
	if err := os.Symlink(outside, filepath.Join(a.Endpoint.Root, "link")); err != nil {
		t.Skip(err)
	}
	s, err := openBoundTarget(context.Background(), a)
	if err != nil {
		t.Fatal(err)
	}
	defer s.close()
	if _, err = s.read("link/secret"); err == nil {
		t.Fatal("symlink read allowed")
	}
}
func TestStorageCopyRejectsWrongHashAndCancelledWork(t *testing.T) {
	a, b := boundFixture(t), boundFixture(t)
	os.WriteFile(filepath.Join(a.Endpoint.Root, "file"), []byte("good"), 0600)
	work := StorageWork{Kind: "COPY", Source: a, From: "file", Target: &b, To: "out", Size: 4, Hash: strings.Repeat("0", 64)}
	if _, _, err := runStorageWork(context.Background(), work, func(string, int64, int64) {}); err == nil {
		t.Fatal("wrong checksum accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	work.Hash = hashData([]byte("good"))
	work.To = "cancelled"
	if _, _, err := runStorageWork(ctx, work, func(string, int64, int64) {}); err == nil {
		t.Fatal("cancelled copy succeeded")
	}
}
func TestStorageDurableReceiptReplayDoesNotWriteAgain(t *testing.T) {
	a, b := boundFixture(t), boundFixture(t)
	dir := t.TempDir()
	q, err := openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	os.WriteFile(filepath.Join(a.Endpoint.Root, "file"), []byte("data"), 0600)
	reports := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		var result StorageReport
		json.Unmarshal(raw, &result)
		if result.Status == "COMPLETED" {
			reports++
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]bool{"proceed": true}})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, err := newClient(Identity{Server: server.URL, PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	work := StorageWork{ID: strings.Repeat("b", 32), Kind: "COPY", Source: a, From: "file", Target: &b, To: "out", Size: 4, Hash: hashData([]byte("data"))}
	if err = client.executeStorage(context.Background(), q, dir, work); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(b.Endpoint.Root, "out"), []byte("changed after completion"), 0600)
	work.ParallelPolling = true // A center upgrade must not change a durable command identity.
	if err = client.executeStorage(context.Background(), q, dir, work); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(filepath.Join(b.Endpoint.Root, "out"))
	if string(data) != "changed after completion" || reports != 2 {
		t.Fatal("receipt replay executed a second write")
	}
	work.To = "other"
	if err = client.executeStorage(context.Background(), q, dir, work); err == nil {
		t.Fatal("reused ID accepted different work")
	}
}

func TestStorageReceiptSurvivesRestartAfterLostAcknowledgment(t *testing.T) {
	a, b := boundFixture(t), boundFixture(t)
	dir := t.TempDir()
	q, err := openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(a.Endpoint.Root, "file"), []byte("data"), 0600)
	var failReceipt atomic.Bool
	failReceipt.Store(true)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var result StorageReport
		json.NewDecoder(r.Body).Decode(&result)
		if result.Status == "COMPLETED" && failReceipt.Load() {
			w.WriteHeader(503)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]bool{"proceed": result.Status == "RUNNING"}})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, err := newClient(Identity{Server: server.URL, PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	work := StorageWork{ID: strings.Repeat("c", 32), Kind: "COPY", Source: a, From: "file", Target: &b, To: "out", Size: 4, Hash: hashData([]byte("data"))}
	if err = client.executeStorage(context.Background(), q, dir, work); err == nil {
		t.Fatal("receipt failure hidden")
	}
	q.db.Close()
	q, err = openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	failReceipt.Store(false)
	os.WriteFile(filepath.Join(b.Endpoint.Root, "out"), []byte("preserve later version"), 0600)
	if err = client.executeStorage(context.Background(), q, dir, work); err != nil {
		t.Fatal(err)
	}
	data, _ := os.ReadFile(filepath.Join(b.Endpoint.Root, "out"))
	if string(data) != "preserve later version" {
		t.Fatal("restart repeated completed write")
	}
}

func TestStorageCancelledBeforeCopyDoesNotWrite(t *testing.T) {
	a, b := boundFixture(t), boundFixture(t)
	dir := t.TempDir()
	q, err := openQueue(dir)
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	os.WriteFile(filepath.Join(a.Endpoint.Root, "file"), []byte("data"), 0600)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]bool{"proceed": false}})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, err := newClient(Identity{Server: server.URL, PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	work := StorageWork{ID: strings.Repeat("d", 32), Kind: "COPY", Source: a, From: "file", Target: &b, To: "out", Size: 4, Hash: hashData([]byte("data"))}
	if err = client.executeStorage(context.Background(), q, dir, work); err != nil {
		t.Fatal(err)
	}
	if _, err = os.Stat(filepath.Join(b.Endpoint.Root, "out")); !os.IsNotExist(err) {
		t.Fatal("cancelled instruction wrote target")
	}
}
