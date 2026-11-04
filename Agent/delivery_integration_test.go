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
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func TestDeliveryPublishesReadableFileButKeepsUnreadableEvent(t *testing.T) {
	source, target := t.TempDir(), t.TempDir()
	bad := filepath.Join(source, "bad.iso")
	os.WriteFile(bad, []byte("private"), 0000)
	defer os.Chmod(bad, 0600)
	if f, e := os.Open(bad); e == nil {
		f.Close()
		t.Skip("this platform does not enforce fixture read permissions")
	}
	os.WriteFile(filepath.Join(source, "ok.txt"), []byte("verified data"), 0600)
	staged := ".syncsrv/direct/" + strings.Repeat("a", 64) + "/" + strings.Repeat("b", 64)
	published, confirmed := false, false
	var activeScopes []string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var input map[string]json.RawMessage
		json.NewDecoder(r.Body).Decode(&input)
		data := any(map[string]any{})
		switch r.URL.Path {
		case "/agent/v1/changes/start":
			data = map[string]any{"allowed": true}
		case "/agent/v1/push/begin":
			var scopes []string
			json.Unmarshal(input["scopes"], &scopes)
			// Beginning an observation is no longer publication: unreadable files
			// are discovered by the workers, then the entire draft is aborted.
			activeScopes = scopes
			data = map[string]any{"id": "session"}
		case "/agent/v1/push/direct-open":
			var encoded string
			json.Unmarshal(input["publicKey"], &encoded)
			der, _ := base64.StdEncoding.DecodeString(encoded)
			peer, _ := x509.ParsePKIXPublicKey(der)
			key, _ := ecdh.X25519().GenerateKey(rand.Reader)
			secret, _ := key.ECDH(peer.(*ecdh.PublicKey))
			sum := sha256.Sum256(append(secret, []byte("session")...))
			block, _ := aes.NewCipher(sum[:])
			gcm, _ := cipher.NewGCM(block)
			nonce := make([]byte, gcm.NonceSize())
			rand.Read(nonce)
			plain, _ := json.Marshal(TargetEndpoint{Protocol: "LOCAL", Root: target})
			public, _ := x509.MarshalPKIXPublicKey(key.PublicKey())
			data = map[string]string{"publicKey": base64.StdEncoding.EncodeToString(public), "nonce": base64.StdEncoding.EncodeToString(nonce), "ciphertext": base64.StdEncoding.EncodeToString(gcm.Seal(nil, nonce, plain, []byte("session")))}
		case "/agent/v1/push/direct-prepare":
			data = directTicket{Path: staged}
		case "/agent/v1/push/direct-complete":
			actual, err := os.ReadFile(filepath.Join(target, staged))
			if err != nil || string(actual) != "verified data" {
				t.Error("bad transfer confirmation")
			}
			confirmed = true
		case "/agent/v1/push/finish":
			if len(activeScopes) != 1 || activeScopes[0] != "ok.txt" {
				t.Errorf("incomplete scope published: %v", activeScopes)
			}
			if !confirmed {
				t.Error("manifest published before content confirmation")
			}
			published = true
		case "/agent/v1/events":
			if !published {
				t.Error("event acknowledged before publication")
			}
			var events []Event
			json.Unmarshal(input["events"], &events)
			var ids []string
			for _, e := range events {
				if e.Path != "ok.txt" {
					t.Errorf("unreadable event acknowledged: %v", e)
				}
				ids = append(ids, e.ID)
			}
			data = map[string]any{"acknowledged": ids}
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": data})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, _ := newClient(Identity{ID: "a", Server: server.URL, PrivateKey: key})
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	q.add([]Event{{JobID: "job", Path: "bad.iso"}, {JobID: "job", Path: "ok.txt"}})
	batch, _ := q.batch(10)
	ack, err := client.deliver(context.Background(), []Root{{JobID: "job", SourceMode: "AGENT", Path: source}}, batch)
	if err == nil || len(ack) != 1 || !published {
		t.Fatalf("delivery ack=%v published=%v err=%v", ack, published, err)
	}
	if err = q.ack(batch, ack); err != nil {
		t.Fatal(err)
	}
	pending, _ := q.batch(10)
	if len(pending.Events) != 1 || pending.Events[0].Path != "bad.iso" {
		t.Fatalf("lost blocked event: %+v", pending)
	}
}

func TestCancelledTransferAbortsDraftWithoutReportingFailure(t *testing.T) {
	source := t.TempDir()
	if err := os.WriteFile(filepath.Join(source, "file"), []byte("data"), 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var aborts, failures atomic.Int64
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var data any = map[string]any{}
		switch r.URL.Path {
		case "/agent/v1/changes/start":
			data = map[string]any{"allowed": true}
		case "/agent/v1/push/begin":
			data = map[string]any{"id": "session"}
		case "/agent/v1/push/inventory":
			cancel()
		case "/agent/v1/push/abort":
			aborts.Add(1)
		case "/agent/v1/changes/failed":
			failures.Add(1)
		}
		json.NewEncoder(w).Encode(map[string]any{"success": true, "data": data})
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, _ := newClient(Identity{ID: "a", Server: server.URL, PrivateKey: key})
	_, err := client.deliverPart(ctx, Root{JobID: "job", Path: source, SourceMode: "AGENT"}, Batch{Events: []Event{{JobID: "job", Path: "file"}}})
	if !errors.Is(err, context.Canceled) || aborts.Load() != 1 || failures.Load() != 0 {
		t.Fatalf("pause misreported as failure: err=%v aborts=%d failures=%d", err, aborts.Load(), failures.Load())
	}
}
