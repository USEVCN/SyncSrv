package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"
)

func TestPublisherSignatureAndValidity(t *testing.T) {
	pub, key, _ := ed25519.GenerateKey(rand.Reader)
	now := time.Now().UTC().Truncate(time.Second)
	trust, _ := json.Marshal(map[string]string{"test": base64.StdEncoding.EncodeToString(pub)})
	payload, _ := json.Marshal(map[string]any{"product": "SyncSrvAgent", "version": "9.1.0", "channel": "stable", "schema": 1, "issuedAt": now.Format(time.RFC3339), "expiresAt": now.Add(time.Hour).Format(time.RFC3339)})
	e := signedRelease{base64.StdEncoding.EncodeToString(payload), base64.StdEncoding.EncodeToString(ed25519.Sign(key, payload)), "test"}
	if _, err := verifyRelease(e, trust, now); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(*signedRelease){func(e *signedRelease) { e.KeyID = "attacker" }, func(e *signedRelease) { e.Payload = base64.StdEncoding.EncodeToString([]byte(`{}`)) }, func(e *signedRelease) { e.Signature = "" }} {
		bad := e
		change(&bad)
		if _, err := verifyRelease(bad, trust, now); err == nil {
			t.Fatal("untrusted update accepted")
		}
	}
	if _, err := verifyRelease(e, trust, now.Add(2*time.Hour)); err == nil {
		t.Fatal("expired update accepted")
	}
}
func TestLogRotationWhileRunning(t *testing.T) {
	log, err := newRotatingLog(t.TempDir()+"/service.log", 1024, 2)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 80; i++ {
		if _, err = log.Write(make([]byte, 200)); err != nil {
			t.Fatal(err)
		}
	}
	log.Close()
}
