package main

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// Real local target I/O, with only the center's metadata endpoints simulated.
type pushFixture struct {
	mu                                     sync.Mutex
	root                                   string
	inventory, manifest, completed         map[string]SourceNode
	offset, connections, finished, aborted int
	beforePrepare                          func(context.Context)
	batchMode                              bool
	requestDelay                           time.Duration
	requests                               map[string]int
	rejectComplete                         bool
	stats                                  transferStats
}

func newPushFixture(t testing.TB, batchMode ...bool) (*pushFixture, *Client, func()) {
	t.Helper()
	f := &pushFixture{root: t.TempDir(), inventory: map[string]SourceNode{}, manifest: map[string]SourceNode{}, completed: map[string]SourceNode{}}
	f.requests = map[string]int{}
	f.batchMode = len(batchMode) > 0 && batchMode[0]
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if f.requestDelay > 0 {
			time.Sleep(f.requestDelay)
		}
		f.mu.Lock()
		f.requests[r.URL.Path]++
		f.mu.Unlock()
		var in struct {
			PublicKey string          `json:"publicKey"`
			Offset    int             `json:"offset"`
			Entries   json.RawMessage `json:"entries"`
			Entry     SourceNode      `json:"entry"`
			Stats     transferStats   `json:"stats"`
		}
		if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
			t.Error(err)
			http.Error(w, "bad body", 400)
			return
		}
		var entries []SourceNode
		if len(in.Entries) > 0 && in.Entries[0] == '[' {
			if err := json.Unmarshal(in.Entries, &entries); err != nil {
				t.Error(err)
				return
			}
		}
		data := any(map[string]any{})
		switch r.URL.Path {
		case "/agent/v1/push/begin":
			data = map[string]any{"id": "session", "batchDirect": f.batchMode}
		case "/agent/v1/push/inventory":
			f.mu.Lock()
			for _, n := range entries {
				if n.Hash != "" {
					t.Error("inventory read file contents")
				}
				f.inventory[n.Path] = n
			}
			f.mu.Unlock()
		case "/agent/v1/push/page":
			f.mu.Lock()
			if in.Offset != f.offset {
				t.Errorf("unordered manifest %d, want %d", in.Offset, f.offset)
			}
			for _, n := range entries {
				if !n.Directory && len(n.Hash) != 64 {
					t.Error("unhashed content authorized")
				}
				f.manifest[n.Path] = n
			}
			f.offset += len(entries)
			f.mu.Unlock()
		case "/agent/v1/push/direct-open":
			f.mu.Lock()
			f.connections++
			f.mu.Unlock()
			public, _ := base64.StdEncoding.DecodeString(in.PublicKey)
			parsed, err := x509.ParsePKIXPublicKey(public)
			if err != nil {
				t.Error(err)
				return
			}
			key, _ := ecdh.X25519().GenerateKey(rand.Reader)
			secret, _ := key.ECDH(parsed.(*ecdh.PublicKey))
			sum := sha256.Sum256(append(secret, []byte("session")...))
			block, _ := aes.NewCipher(sum[:])
			gcm, _ := cipher.NewGCM(block)
			nonce := make([]byte, gcm.NonceSize())
			rand.Read(nonce)
			plain, _ := json.Marshal(TargetEndpoint{Protocol: "LOCAL", Root: f.root})
			der, _ := x509.MarshalPKIXPublicKey(key.PublicKey())
			data = sealedEnvelope{PublicKey: base64.StdEncoding.EncodeToString(der), Nonce: base64.StdEncoding.EncodeToString(nonce), Ciphertext: base64.StdEncoding.EncodeToString(gcm.Seal(nil, nonce, plain, []byte("session")))}
		case "/agent/v1/push/direct-prepare-batch":
			tickets := []directTicket{}
			for _, n := range entries {
				f.mu.Lock()
				_, ok := f.manifest[n.Path]
				f.mu.Unlock()
				if !ok {
					t.Error("batch file not authorized")
				}
				tickets = append(tickets, directTicket{Path: f.staged(n)})
			}
			data = tickets
		case "/agent/v1/push/direct-complete-batch":
			if f.rejectComplete {
				http.Error(w, "receipt unavailable", 500)
				return
			}
			tickets := []directTicket{}
			for _, n := range entries {
				actual, err := os.ReadFile(filepath.Join(f.root, f.staged(n)))
				if err != nil || hashData(actual) != n.Hash {
					t.Errorf("invalid batch receipt %s: %v", n.Path, err)
				}
				f.mu.Lock()
				f.completed[n.Path] = n
				f.mu.Unlock()
				tickets = append(tickets, directTicket{Path: f.staged(n), Ready: true})
			}
			data = tickets
		case "/agent/v1/push/direct-prepare":
			f.mu.Lock()
			_, ok := f.manifest[in.Entry.Path]
			f.mu.Unlock()
			if !ok {
				t.Error("file not authorized by hashed manifest")
			}
			if f.beforePrepare != nil {
				f.beforePrepare(r.Context())
			}
			data = directTicket{Path: f.staged(in.Entry)}
		case "/agent/v1/push/direct-complete":
			actual, err := os.ReadFile(filepath.Join(f.root, f.staged(in.Entry)))
			if err != nil || hashData(actual) != in.Entry.Hash {
				t.Errorf("invalid receipt %s: %v", in.Entry.Path, err)
			}
			f.mu.Lock()
			f.completed[in.Entry.Path] = in.Entry
			f.mu.Unlock()
		case "/agent/v1/push/finish":
			f.mu.Lock()
			f.finished++
			f.stats = in.Stats
			f.mu.Unlock()
		case "/agent/v1/push/abort":
			f.mu.Lock()
			f.aborted++
			f.mu.Unlock()
		case "/agent/v1/push/progress":
		default:
			t.Errorf("unexpected endpoint %s", r.URL.Path)
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": data})
	}))
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	c, err := newClient(Identity{ID: "agent", Server: server.URL, PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	return f, c, server.Close
}
func (f *pushFixture) staged(n SourceNode) string {
	return ".syncsrv/direct/" + strings.Repeat("a", 64) + "/" + hashData([]byte(n.Path+"\n"+n.Hash))
}

func TestPushOverlapsThreeFilesAndFinishesOnlyAfterAllReceipts(t *testing.T) {
	source := t.TempDir()
	for i := 0; i < 6; i++ {
		if err := os.WriteFile(filepath.Join(source, fmt.Sprint(i)), []byte(strings.Repeat(fmt.Sprint(i), 8192)), 0600); err != nil {
			t.Fatal(err)
		}
	}
	f, c, closeServer := newPushFixture(t)
	defer closeServer()
	arrived := make(chan struct{}, 6)
	release := make(chan struct{})
	defer func() {
		select {
		case <-release:
		default:
			close(release)
		}
	}()
	f.beforePrepare = func(ctx context.Context) {
		arrived <- struct{}{}
		select {
		case <-release:
		case <-ctx.Done():
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- c.pushSource(ctx, Root{JobID: "job", Path: source, FullScanRequired: true, TransferThreads: 3}, nil)
	}()
	for i := 0; i < 3; i++ {
		select {
		case <-arrived:
		case <-ctx.Done():
			t.Fatal("files did not execute concurrently")
		}
	}
	f.mu.Lock()
	connections, planned, finished := f.connections, len(f.manifest), f.finished
	f.mu.Unlock()
	if connections != 3 || planned != 3 || finished != 0 {
		t.Errorf("connections=%d published=%d finish=%d", connections, planned, finished)
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.completed) != 6 || f.finished != 1 || f.aborted != 0 || len(f.inventory) != 6 {
		t.Fatalf("incomplete batch: %+v", f)
	}
}

func TestFirstFileTransfersBeforeLaterFileChecksumAndFailedDraftNeverPublishes(t *testing.T) {
	source := t.TempDir()
	os.WriteFile(filepath.Join(source, "a.txt"), []byte("ready immediately"), 0600)
	bad := filepath.Join(source, "z.iso")
	os.WriteFile(bad, []byte("unreadable later"), 0000)
	defer os.Chmod(bad, 0600)
	if file, err := os.Open(bad); err == nil {
		file.Close()
		t.Skip("read permissions are not enforced")
	}
	// Metadata discovery must not wait for content reads, even if a later file cannot be read.
	_, nodes, err := collectSourceInventory(context.Background(), source, nil, true)
	if err != nil || len(nodes) != 2 {
		t.Fatalf("inventory reads content: %v", err)
	}
	f, c, closeServer := newPushFixture(t)
	defer closeServer()
	err = c.pushSource(context.Background(), Root{JobID: "job", Path: source, FullScanRequired: true, TransferThreads: 1}, nil)
	if err == nil {
		t.Fatal("unreadable file accepted")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, ok := f.completed["a.txt"]; !ok {
		t.Fatal("first transfer still waits for the whole batch checksum")
	}
	if f.finished != 0 || f.aborted != 1 {
		t.Fatalf("failed batch published: finish=%d abort=%d", f.finished, f.aborted)
	}
}

func BenchmarkSourceInitialization(b *testing.B) {
	root := b.TempDir()
	data := []byte(strings.Repeat("x", 1024*1024))
	for i := 0; i < 32; i++ {
		if err := os.WriteFile(filepath.Join(root, fmt.Sprint(i)), data, 0600); err != nil {
			b.Fatal(err)
		}
	}
	for _, metadata := range []bool{false, true} {
		name := "previous-all-checksums"
		if metadata {
			name = "metadata-only"
		}
		b.Run(name, func(b *testing.B) {
			for i := 0; i < b.N; i++ {
				var err error
				if metadata {
					_, _, err = collectSourceInventory(context.Background(), root, nil, true)
				} else {
					_, _, err = collectSource(context.Background(), root, nil, true)
				}
				if err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}
