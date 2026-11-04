package main

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

type Identity struct {
	Server     string `json:"server"`
	ID         string `json:"id"`
	PrivateKey []byte `json:"privateKey"`
	AllowHTTP  bool   `json:"allowHttp,omitempty"`
}
type Client struct {
	budget      fleetBudget
	identity    Identity
	http        *http.Client
	uploadBytes atomic.Int64
	uploadFiles atomic.Int64
}
type Root struct {
	JobID              string  `json:"jobId"`
	Path               string  `json:"path"`
	ReconcileSeconds   int     `json:"reconcileSeconds"`
	SourceMode         string  `json:"sourceMode"`
	Enabled            bool    `json:"enabled"`
	IntervalSeconds    int     `json:"intervalSeconds"`
	BandwidthMiB       float64 `json:"bandwidthMiB"`
	TransferThreads    int     `json:"transferThreads"`
	AdaptiveTransfers  bool    `json:"adaptiveTransfers"`
	MaxTransferThreads int     `json:"maxTransferThreads"`
	FullScanRequired   bool    `json:"fullScanRequired"`
}
type Configuration struct {
	GlobalBandwidthMiB float64 `json:"globalBandwidthMiB"`
	Version            int64   `json:"version"`
	Status             string  `json:"status"`
	Roots              []Root  `json:"roots"`
}
type envelope struct {
	Success bool            `json:"success"`
	Data    json.RawMessage `json:"data"`
	Message string          `json:"message"`
}

func validateServer(server string) (string, error) { return validateCenter(server, false) }
func validateCenter(server string, allowHTTP bool) (string, error) {
	u, err := url.Parse(server)
	if err != nil {
		return "", err
	}
	ip := net.ParseIP(u.Hostname())
	if u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return "", fmt.Errorf("server must be an origin without credentials or a path")
	}
	if u.Scheme != "https" && !(u.Scheme == "http" && ip != nil && (ip.IsLoopback() || (allowHTTP && ip.IsPrivate()))) {
		return "", fmt.Errorf("HTTPS required; HTTP is permitted only for explicit loopback development")
	}
	return strings.TrimRight(server, "/"), nil
}
func newClient(identity Identity) (*Client, error) {
	server, err := validateCenter(identity.Server, identity.AllowHTTP)
	if err != nil {
		return nil, err
	}
	if len(identity.PrivateKey) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("invalid device private key")
	}
	identity.Server = server
	c := &Client{identity: identity, http: &http.Client{Timeout: 30 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }}}
	c.budget.client = c
	return c, nil
}
func (c *Client) request(ctx context.Context, method, path string, input, output any, signed bool) error {
	var body []byte
	var err error
	if input != nil {
		body, err = json.Marshal(input)
		if err != nil {
			return err
		}
	}
	return c.rawRequest(ctx, method, path, body, output, signed)
}
func (c *Client) rawRequest(ctx context.Context, method, path string, body []byte, output any, signed bool) error {
	req, err := http.NewRequestWithContext(ctx, method, c.identity.Server+path, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	if signed {
		stamp := strconv.FormatInt(time.Now().Unix(), 10)
		nonce := randomID()
		sum := sha256.Sum256(body)
		message := method + "\n" + path + "\n" + stamp + "\n" + nonce + "\n" + hex.EncodeToString(sum[:])
		req.Header.Set("X-Agent-Id", c.identity.ID)
		req.Header.Set("X-Agent-Time", stamp)
		req.Header.Set("X-Agent-Nonce", nonce)
		req.Header.Set("X-Agent-Signature", base64.StdEncoding.EncodeToString(ed25519.Sign(c.identity.PrivateKey, []byte(message))))
	}
	res, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 2<<20))
	if err != nil {
		return err
	}
	var e envelope
	if err = json.Unmarshal(raw, &e); err != nil {
		return fmt.Errorf("server returned HTTP %d, invalid JSON", res.StatusCode)
	}
	if res.StatusCode < 200 || res.StatusCode >= 300 || !e.Success {
		return fmt.Errorf("HTTP %d: %s", res.StatusCode, e.Message)
	}
	if strings.HasPrefix(path, "/agent/v1/push/chunk/") {
		c.uploadBytes.Add(int64(len(body)))
	}
	if output != nil {
		err := json.Unmarshal(e.Data, output)
		if config, ok := output.(*Configuration); ok && err == nil {
			c.budget.enabled.Store(config.GlobalBandwidthMiB > 0)
			c.budget.chunk.Store(int64(max(64*1024, min(8*1024*1024, config.GlobalBandwidthMiB*1024*1024/5))))
		}
		return err
	}
	return nil
}
func saveIdentity(dir string, id Identity) error {
	raw, err := json.MarshalIndent(id, "", "  ")
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "identity-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err = tmp.Write(raw); err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err != nil {
		return err
	}
	if closeErr != nil {
		return closeErr
	}
	if err = os.Rename(tmp.Name(), filepath.Join(dir, "identity.json")); err != nil {
		return err
	}
	return syncDirectory(dir)
}
func loadIdentity(dir string) (Identity, error) {
	var id Identity
	raw, err := os.ReadFile(filepath.Join(dir, "identity.json"))
	if err != nil {
		return id, err
	}
	err = json.Unmarshal(raw, &id)
	return id, err
}
func publicKey(id Identity) (string, error) {
	raw, err := x509.MarshalPKIXPublicKey(ed25519.PrivateKey(id.PrivateKey).Public())
	return base64.StdEncoding.EncodeToString(raw), err
}
