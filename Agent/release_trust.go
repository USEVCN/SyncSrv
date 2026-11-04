package main

import (
	"crypto/ed25519"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"time"
)

//go:embed release-trust.json
var releaseTrust []byte

type signedRelease struct {
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
	KeyID     string `json:"keyId"`
}

func verifyRelease(envelope signedRelease, trust []byte, now time.Time) (AgentRelease, error) {
	var keys map[string]string
	var release AgentRelease
	if err := json.Unmarshal(trust, &keys); err != nil {
		return release, err
	}
	key, err := base64.StdEncoding.DecodeString(keys[envelope.KeyID])
	if err != nil || len(key) != ed25519.PublicKeySize {
		return release, fmt.Errorf("untrusted release publisher")
	}
	raw, err := base64.StdEncoding.DecodeString(envelope.Payload)
	if err != nil || len(raw) > 65536 {
		return release, fmt.Errorf("invalid signed release payload")
	}
	signature, err := base64.StdEncoding.DecodeString(envelope.Signature)
	if err != nil || !ed25519.Verify(key, raw, signature) {
		return release, fmt.Errorf("release publisher signature rejected")
	}
	if err = json.Unmarshal(raw, &release); err != nil {
		return release, err
	}
	if release.Product != "SyncSrvAgent" || release.Channel != "stable" || release.Schema != 1 {
		return release, fmt.Errorf("incompatible release product/channel/schema")
	}
	issued, e1 := time.Parse(time.RFC3339, release.IssuedAt)
	expires, e2 := time.Parse(time.RFC3339, release.ExpiresAt)
	if e1 != nil || e2 != nil || issued.After(now.Add(5*time.Minute)) || !expires.After(now) || expires.Sub(issued) > 370*24*time.Hour {
		return release, fmt.Errorf("release validity period rejected; check system clock")
	}
	return release, nil
}
