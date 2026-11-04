package main

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestReleaseOrderingAndDowngradeProtection(t *testing.T) {
	for _, pair := range [][2]string{{"0.7.0-dev", "0.6.0-dev"}, {"0.7.0", "0.7.0-dev"}, {"0.7.0-dev.2", "0.7.0-dev.1"}, {"0.10.0-dev", "0.9.0"}} {
		n, err := compareRelease(pair[0], pair[1])
		if err != nil || n <= 0 {
			t.Fatalf("newer: %v %v %v", pair, n, err)
		}
		n, err = compareRelease(pair[1], pair[0])
		if err != nil || n >= 0 {
			t.Fatalf("downgrade: %v", pair)
		}
	}
	if n, err := compareRelease("0.7.0-dev", "0.7.0-dev"); err != nil || n != 0 {
		t.Fatal(n, err)
	}
	if _, err := compareRelease("../../escape", "0.7.0"); err == nil {
		t.Fatal("invalid version accepted")
	}
}
func TestUpdateDownloadRejectsCorruptionOversizeAndRedirect(t *testing.T) {
	body := []byte("release-data")
	sum := sha256.Sum256(body)
	for _, tc := range []struct {
		name, body string
		status     int
		size       int64
		hash       string
		ok         bool
	}{
		{"valid", string(body), 200, int64(len(body)), hex.EncodeToString(sum[:]), true},
		{"corrupt", "release-DATA", 200, int64(len(body)), hex.EncodeToString(sum[:]), false},
		{"oversize", string(body) + "extra", 200, int64(len(body)), hex.EncodeToString(sum[:]), false},
		{"short", "short", 200, int64(len(body)), hex.EncodeToString(sum[:]), false},
		{"redirect", "", 302, int64(len(body)), hex.EncodeToString(sum[:]), false},
		{"bad-hash", string(body), 200, int64(len(body)), "bad", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Location", "http://127.0.0.1:1/should-not-follow")
				w.WriteHeader(tc.status)
				w.Write([]byte(tc.body))
			}))
			defer server.Close()
			client, err := newClient(Identity{Server: server.URL, PrivateKey: make([]byte, ed25519.PrivateKeySize)})
			if err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(t.TempDir(), "agent")
			err = downloadUpdate(context.Background(), client, "0.7.0-dev", ReleaseAsset{Filename: "syncsrv-agent-linux-amd64", Size: tc.size, SHA256: tc.hash}, path)
			if (err == nil) != tc.ok {
				t.Fatalf("got %v success=%v", err, tc.ok)
			}
			_, statErr := os.Stat(path)
			if tc.ok && statErr != nil {
				t.Fatal(statErr)
			}
			if !tc.ok && !os.IsNotExist(statErr) {
				t.Fatal("failed download left executable")
			}
		})
	}
}
func TestToolInstallPreservesIdentityAndQueue(t *testing.T) {
	install, data := t.TempDir(), t.TempDir()
	for _, name := range []string{"identity.json", "events.db"} {
		if err := os.WriteFile(filepath.Join(data, name), []byte("keep"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := installTools([]string{"--install-dir", install, "--data-dir", data, "--mode", "systemd"}); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"identity.json", "events.db"} {
		raw, _ := os.ReadFile(filepath.Join(data, name))
		if string(raw) != "keep" {
			t.Fatal("data changed")
		}
	}
	for _, name := range []string{"update.sh", "start.sh", "stop.sh", "restart.sh", "uninstall.sh", "agent-uninstall.sh", "agent-control.sh", "agent-control.conf"} {
		if _, err := os.Stat(filepath.Join(install, name)); err != nil {
			t.Fatal(err)
		}
	}
}
