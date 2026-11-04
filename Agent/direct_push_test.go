package main

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDirectRetryValidatesPrefixAndAcknowledgesOnlyVerifiedContent(t *testing.T) {
	content := bytes.Repeat([]byte("durable content"), 100000)
	for _, scenario := range []struct {
		name   string
		prefix []byte
		offset int64
	}{
		{"partial", content[:10000], 10000},
		{"wrong-prefix", bytes.Repeat([]byte("x"), 10000), 0},
		{"lost-ack", content, -1},
		{"oversized", append(append([]byte{}, content...), 'x'), 0},
	} {
		t.Run(scenario.name, func(t *testing.T) {
			source, targetDir := t.TempDir(), t.TempDir()
			if err := os.WriteFile(filepath.Join(source, "file"), content, 0600); err != nil {
				t.Fatal(err)
			}
			_, nodes, err := collectSource(context.Background(), source, nil, true)
			if err != nil {
				t.Fatal(err)
			}
			staged := ".syncsrv/direct/" + strings.Repeat("a", 64) + "/" + strings.Repeat("b", 64)
			if err = os.MkdirAll(filepath.Dir(filepath.Join(targetDir, staged)), 0700); err != nil {
				t.Fatal(err)
			}
			if err = os.WriteFile(filepath.Join(targetDir, staged), scenario.prefix, 0600); err != nil {
				t.Fatal(err)
			}
			completed := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/agent/v1/push/direct-complete" {
					actual, err := os.ReadFile(filepath.Join(targetDir, staged))
					if err != nil || !bytes.Equal(actual, content) {
						t.Error("acknowledged unverified content")
					}
					completed++
				} else if r.URL.Path != "/agent/v1/push/direct-prepare" {
					t.Error("unexpected payload relay")
				}
				json.NewEncoder(w).Encode(map[string]any{"success": true, "data": directTicket{Path: staged}})
			}))
			defer server.Close()
			_, private, _ := ed25519.GenerateKey(rand.Reader)
			client, err := newClient(Identity{ID: "test", Server: server.URL, PrivateKey: private})
			if err != nil {
				t.Fatal(err)
			}
			target, err := openTarget(context.Background(), TargetEndpoint{Protocol: "LOCAL", Root: targetDir})
			if err != nil {
				t.Fatal(err)
			}
			defer target.close()
			offset := int64(-1)
			write := target.write
			target.write = func(p string, in io.Reader, off, size int64) error { offset = off; return write(p, in, off, size) }
			if err = client.directUpload(context.Background(), target, "session", Root{Path: source}, nodes[0]); err != nil {
				t.Fatal(err)
			}
			if offset != scenario.offset || completed != 1 {
				t.Fatalf("offset=%d acknowledgements=%d", offset, completed)
			}
			// A newer observation cannot be acknowledged under the stale manifest.
			os.WriteFile(filepath.Join(source, "file"), []byte("new"), 0600)
			if err = client.directUpload(context.Background(), target, "session", Root{Path: source}, nodes[0]); err == nil {
				t.Fatal("changed source accepted")
			}
			if completed != 1 {
				t.Fatal("stale source acknowledged")
			}
		})
	}
}

func TestDirectReadbackReleasesSourceAndRejectsDeletedOrReplacedFile(t *testing.T) {
	for _, replacement := range []bool{false, true} {
		name := "deleted"
		if replacement {
			name = "replaced"
		}
		t.Run(name, func(t *testing.T) {
			source, targetDir := t.TempDir(), t.TempDir()
			path := filepath.Join(source, "nested", "file")
			if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
				t.Fatal(err)
			}
			if err := os.WriteFile(path, []byte("original"), 0600); err != nil {
				t.Fatal(err)
			}
			originalInfo, err := os.Stat(path)
			if err != nil {
				t.Fatal(err)
			}
			_, nodes, err := collectSource(context.Background(), source, nil, true)
			if err != nil {
				t.Fatal(err)
			}
			var file SourceNode
			for _, n := range nodes {
				if !n.Directory {
					file = n
				}
			}
			target, err := openTarget(context.Background(), TargetEndpoint{Protocol: "LOCAL", Root: targetDir})
			if err != nil {
				t.Fatal(err)
			}
			defer target.close()
			read := target.read
			target.read = func(p string) (io.ReadCloser, error) {
				// A is no longer read during verification of B. On Windows this also
				// checks that the deleted child can be recreated while verification runs.
				if err := os.RemoveAll(filepath.Dir(path)); err != nil {
					t.Fatalf("source still occupied during B verification: %v", err)
				}
				if err := os.Mkdir(filepath.Dir(path), 0700); err != nil {
					t.Fatalf("source directory is still pending deletion: %v", err)
				}
				if replacement {
					if err := os.WriteFile(path, []byte("replaced"), 0600); err != nil {
						t.Fatal(err)
					}
					// Even an exact preserved mtime must not hide a reused inode.
					if err := os.Chtimes(path, originalInfo.ModTime(), originalInfo.ModTime()); err != nil {
						t.Fatal(err)
					}
				}
				return read(p)
			}
			ticket := directTicket{Path: ".syncsrv/direct/" + strings.Repeat("a", 64) + "/" + strings.Repeat("b", 64)}
			client := &Client{}
			if err := client.writeDirectFile(context.Background(), target, Root{Path: source}, file, ticket); err == nil {
				t.Fatal("source removed during verification was incorrectly accepted")
			}
		})
	}
}
