package main

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"time"
)

type directTicket struct {
	Path  string `json:"path"`
	Ready bool   `json:"ready"`
}

func (c *Client) directTarget(ctx context.Context, session string) (*remoteTarget, error) {
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	der, err := x509.MarshalPKIXPublicKey(key.PublicKey())
	if err != nil {
		return nil, err
	}
	var sealed sealedEnvelope
	if err = c.request(ctx, "POST", "/agent/v1/push/direct-open", map[string]any{"sessionId": session, "publicKey": base64.StdEncoding.EncodeToString(der)}, &sealed, true); err != nil {
		return nil, err
	}
	plain, err := decryptEnvelope(key, session, sealed)
	if err != nil {
		return nil, err
	}
	var mapped BoundEndpoint
	if err = json.Unmarshal(plain, &mapped); err != nil {
		return nil, err
	}
	if mapped.Endpoint.Protocol != "" {
		return openBoundTarget(ctx, mapped)
	}
	var endpoint TargetEndpoint
	if err = json.Unmarshal(plain, &endpoint); err != nil {
		return nil, err
	}
	return openTarget(ctx, endpoint)
}

type sealedEnvelope struct{ PublicKey, Nonce, Ciphertext string }

func decryptEnvelope(key *ecdh.PrivateKey, session string, sealed sealedEnvelope) ([]byte, error) {
	public, err := base64.StdEncoding.DecodeString(sealed.PublicKey)
	if err != nil {
		return nil, err
	}
	parsed, err := x509.ParsePKIXPublicKey(public)
	if err != nil {
		return nil, err
	}
	peer, ok := parsed.(*ecdh.PublicKey)
	if !ok {
		return nil, fmt.Errorf("invalid target envelope key")
	}
	secret, err := key.ECDH(peer)
	if err != nil {
		return nil, err
	}
	hash := sha256.New()
	hash.Write(secret)
	hash.Write([]byte(session))
	block, err := aes.NewCipher(hash.Sum(nil))
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	nonce, err := base64.StdEncoding.DecodeString(sealed.Nonce)
	if err != nil || len(nonce) != gcm.NonceSize() {
		return nil, fmt.Errorf("invalid target envelope nonce")
	}
	encrypted, err := base64.StdEncoding.DecodeString(sealed.Ciphertext)
	if err != nil {
		return nil, err
	}
	plain, err := gcm.Open(nil, nonce, encrypted, []byte(session))
	if err != nil {
		return nil, err
	}
	return plain, nil
}

var directPath = regexp.MustCompile(`^\.syncsrv/direct/[a-f0-9]{64}/[a-f0-9]{64}$`)

func (c *Client) directUpload(ctx context.Context, target *remoteTarget, session string, root Root, n SourceNode, observers ...func(string, string) error) error {
	telemetry := sourceProgress(ctx)
	telemetry.file(n.Path, "UPLOADING_B", 0)
	request := map[string]any{"sessionId": session, "entry": n}
	var ticket directTicket
	if err := c.request(ctx, "POST", "/agent/v1/push/direct-prepare", request, &ticket, true); err != nil {
		return err
	}
	if err := c.writeDirectFile(ctx, target, root, n, ticket, observers...); err != nil {
		return err
	}
	if ticket.Ready {
		return nil
	}
	if err := c.request(ctx, "POST", "/agent/v1/push/direct-complete", request, &ticket, true); err != nil {
		return err
	}
	c.uploadFiles.Add(1)
	return nil
}

func (c *Client) writeDirectFile(ctx context.Context, target *remoteTarget, root Root, n SourceNode, ticket directTicket, observers ...func(string, string) error) error {
	telemetry := sourceProgress(ctx)
	if ticket.Ready {
		return nil
	}
	if !directPath.MatchString(ticket.Path) {
		return fmt.Errorf("unsafe target upload instruction")
	}
	_, info, err := inspectSource(root.Path, n.Path)
	if err != nil {
		return err
	}
	if info.Size() != n.Size || info.ModTime().UnixMilli() != n.Modified {
		return fmt.Errorf("source superseded before direct upload: %s", n.Path)
	}
	base, err := os.OpenRoot(root.Path)
	if err != nil {
		return err
	}
	f, err := base.Open(filepath.FromSlash(n.Path))
	// The file has its own handle. Do not keep the source root open throughout
	// network transfer; Windows os.OpenRoot does not share root deletion.
	base.Close()
	if err != nil {
		return sourceFailure(n.Path, err)
	}
	defer f.Close()
	if err = target.parents(ticket.Path); err != nil {
		return err
	}
	offset := int64(0)
	if current, e := target.stat(ticket.Path); e == nil {
		if current.directory || current.symbolic {
			return fmt.Errorf("unsafe target upload path")
		}
		if target.resumable && current.size <= n.Size {
			// Validate the durable prefix against A before resuming, including an ACK-lost full object.
			remote, e := target.read(ticket.Path)
			if e != nil {
				return e
			}
			rh := sha256.New()
			_, e = io.CopyN(rh, &pacedReader{ctx: ctx, reader: remote, mib: root.BandwidthMiB, onRead: func(n int) { telemetry.read(n, true) }}, current.size)
			remote.Close()
			if e == nil {
				local := sha256.New()
				_, e = io.CopyN(local, f, current.size)
				if e == nil && hex.EncodeToString(rh.Sum(nil)) == hex.EncodeToString(local.Sum(nil)) {
					offset = current.size
				}
			}
		}
	}
	if _, err = f.Seek(offset, io.SeekStart); err != nil {
		return err
	}
	telemetry.file(n.Path, "UPLOADING_B", offset)
	if offset < n.Size || n.Size == 0 {
		source := &pacedReader{ctx: ctx, reader: io.LimitReader(f, n.Size-offset), mib: root.BandwidthMiB, onRead: func(n int) { telemetry.read(n, false); c.uploadBytes.Add(int64(n)) }}
		if err = target.write(ticket.Path, source, offset, n.Size); err != nil {
			return err
		}
	}
	after, err := f.Stat()
	if err != nil {
		return err
	}
	if !sameSourceVersion(info, after) || after.Size() != n.Size {
		return fmt.Errorf("source changed during direct upload: %s", n.Path)
	}
	// Windows must release the file before readback so a deleted parent can
	// be recreated. Unix can retain the inode without blocking unlink; this
	// prevents immediate inode reuse even on filesystems with coarse timestamps.
	if runtime.GOOS == "windows" {
		if err = f.Close(); err != nil {
			return err
		}
	}
	actual, err := target.stat(ticket.Path)
	if err != nil {
		return err
	}
	if actual.size != n.Size || actual.directory || actual.symbolic {
		return fmt.Errorf("direct upload length mismatch")
	}
	telemetry.file(n.Path, "VERIFYING_B", n.Size)
	for _, observer := range observers {
		if err = observer("VERIFYING_B", n.Path); err != nil {
			return err
		}
	}
	remote, err := target.read(ticket.Path)
	if err != nil {
		return err
	}
	hash := sha256.New()
	_, err = io.CopyN(hash, &pacedReader{ctx: ctx, reader: remote, mib: root.BandwidthMiB, onRead: func(n int) { telemetry.read(n, true) }}, n.Size)
	remote.Close()
	if err != nil {
		return err
	}
	if hex.EncodeToString(hash.Sum(nil)) != n.Hash {
		return fmt.Errorf("Backup upload SHA-256 mismatch")
	}
	_, after, err = inspectSource(root.Path, n.Path)
	if err != nil {
		return sourceFailure(n.Path, err)
	}
	if !sameSourceVersion(info, after) || after.Size() != n.Size {
		return fmt.Errorf("source changed during direct upload: %s", n.Path)
	}
	return nil
}

type pacedReader struct {
	ctx    context.Context
	reader io.Reader
	mib    float64
	count  uint64
	onRead func(int)
}

func (p *pacedReader) Read(b []byte) (int, error) {
	if err := p.ctx.Err(); err != nil {
		return 0, err
	}
	if len(b) > 256*1024 {
		b = b[:256*1024]
	}
	start := time.Now()
	n, err := p.reader.Read(b)
	if budget, ok := p.ctx.Value(fleetBudgetKey{}).(*fleetBudget); ok && n > 0 {
		if e := budget.acquire(p.ctx, n); e != nil {
			return n, e
		}
	}
	p.count += uint64(n)
	if limiter, ok := p.ctx.Value(transferLimitKey{}).(*transferLimit); ok && p.mib > 0 && n > 0 {
		if e := limiter.acquire(p.ctx, n); e != nil {
			return n, e
		}
	} else if n > 0 && p.mib > 0 {
		delay := time.Duration(float64(n)/(p.mib*1024*1024)*float64(time.Second)) - time.Since(start)
		if delay > 0 {
			timer := time.NewTimer(delay)
			select {
			case <-p.ctx.Done():
				timer.Stop()
				return n, p.ctx.Err()
			case <-timer.C:
			}
		}
	}
	if n > 0 && p.onRead != nil {
		p.onRead(n)
	}
	return n, err
}
