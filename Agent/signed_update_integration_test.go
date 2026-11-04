package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestOnlineUpdateRejectsUnsignedManifestBeforeRunningDownloadedCode(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Unix harmless script fixture; Windows PE lifecycle is manual acceptance")
	}
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	saved := releaseTrust
	defer func() { releaseTrust = saved }()
	releaseTrust, _ = json.Marshal(map[string]string{"fixture": base64.StdEncoding.EncodeToString(pub)})
	marker := filepath.Join(t.TempDir(), "executed")
	quote := "'" + strings.ReplaceAll(marker, "'", "'\\''") + "'"
	body := []byte(fmt.Sprintf("#!/bin/sh\nprintf executed > %s\nprintf '9.9.9 %s %s\\n'\n", quote, runtime.GOOS, runtime.GOARCH))
	sum := sha256.Sum256(body)
	name := "syncsrv-agent-" + runtime.GOOS + "-" + runtime.GOARCH
	var downloads atomic.Int32
	var unsigned atomic.Bool
	unsigned.Store(true)
	var centerVersion atomic.Value
	centerVersion.Store("0.4.0")
	release := AgentRelease{Version: "9.9.9", Product: "SyncSrvAgent", Channel: "stable", Schema: 1, IssuedAt: time.Now().UTC().Format(time.RFC3339), ExpiresAt: time.Now().Add(time.Hour).UTC().Format(time.RFC3339), MinCenterVersion: "0.4.0", Assets: []ReleaseAsset{{Platform: runtime.GOOS, Arch: runtime.GOARCH, Filename: name, SHA256: hex.EncodeToString(sum[:]), Size: int64(len(body))}}}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/agent/releases/latest":
			raw, _ := json.Marshal(release)
			signature := base64.StdEncoding.EncodeToString(ed25519.Sign(key, raw))
			if unsigned.Load() {
				signature = ""
			}
			json.NewEncoder(w).Encode(map[string]any{"success": true, "data": signedRelease{base64.StdEncoding.EncodeToString(raw), signature, "fixture"}})
		case "/api/health":
			json.NewEncoder(w).Encode(map[string]any{"success": true, "data": map[string]string{"version": centerVersion.Load().(string)}})
		default:
			downloads.Add(1)
			w.Write(body)
		}
	}))
	defer server.Close()
	data := t.TempDir()
	_, deviceKey, _ := ed25519.GenerateKey(rand.Reader)
	if err := saveIdentity(data, Identity{Server: server.URL, ID: "fixture", PrivateKey: deviceKey}); err != nil {
		t.Fatal(err)
	}
	update := func() error {
		return prepareUpdate([]string{"--data-dir", data, "--output-dir", t.TempDir(), "--current-version", "0.16.0"})
	}
	if err := update(); err == nil {
		t.Fatal("unsigned update authorized code execution")
	}
	if downloads.Load() != 0 {
		t.Fatal("untrusted content was downloaded")
	}
	if _, err := os.Stat(marker); !os.IsNotExist(err) {
		t.Fatal("untrusted code ran")
	}
	unsigned.Store(false)
	centerVersion.Store("0.3.0")
	if err := update(); err == nil {
		t.Fatal("incompatible center accepted")
	}
	if downloads.Load() != 0 {
		t.Fatal("download began before compatibility check")
	}
	centerVersion.Store("0.4.0")
	if err := update(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(marker); err != nil {
		t.Fatal("signed update did not complete version validation", err)
	}
}
