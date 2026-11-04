package main

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"path/filepath"
	"strings"
	"sync"
	"time"

	bolt "go.etcd.io/bbolt"
)

type BoundEndpoint struct {
	Endpoint   TargetEndpoint `json:"endpoint"`
	Marker     string         `json:"marker"`
	MarkerHash string         `json:"markerHash"`
}
type StorageWork struct {
	LimitScope      string         `json:"limitScope"`
	ParallelPolling bool           `json:"parallelPolling,omitempty"`
	ID              string         `json:"id"`
	Kind            string         `json:"kind"`
	Source          BoundEndpoint  `json:"source"`
	From            string         `json:"from"`
	Target          *BoundEndpoint `json:"target"`
	To              string         `json:"to"`
	Size            int64          `json:"size"`
	Hash            string         `json:"hash"`
	BandwidthMiB    float64        `json:"bandwidthMiB"`
}
type StorageReport struct {
	ID          string `json:"id"`
	Status      string `json:"status"`
	Phase       string `json:"phase"`
	Transferred int64  `json:"transferred"`
	Verified    int64  `json:"verified"`
	Hash        string `json:"hash"`
	Size        int64  `json:"size"`
	Error       string `json:"error"`
}
type storageJournal struct {
	Fingerprint string         `json:"fingerprint"`
	Result      *StorageReport `json:"result,omitempty"`
	Updated     int64          `json:"updated"`
}

var storageBucket = []byte("storage-execution")

func safeStoragePath(p string) bool {
	if p == "" || strings.ContainsAny(p, "\\\x00\r\n:") || strings.HasPrefix(p, "/") {
		return false
	}
	for _, part := range strings.Split(p, "/") {
		if part == "" || part == "." || part == ".." {
			return false
		}
	}
	return len(p) <= 4096
}
func openBoundTarget(ctx context.Context, bound BoundEndpoint) (*remoteTarget, error) {
	if bound.Endpoint.Protocol == "LOCAL" && (bound.Marker == "" || !strings.HasPrefix(bound.Marker, ".syncsrv/device-bindings/") || !safeStoragePath(bound.Marker)) {
		return nil, fmt.Errorf("local storage requires a verified device binding")
	}
	target, err := openTarget(ctx, bound.Endpoint)
	if err != nil {
		return nil, err
	}
	if bound.Endpoint.Protocol == "LOCAL" {
		input, e := target.read(bound.Marker)
		if e == nil {
			data, readErr := io.ReadAll(io.LimitReader(input, 129))
			input.Close()
			e = readErr
			sum := sha256.Sum256(data)
			if e == nil && hex.EncodeToString(sum[:]) != bound.MarkerHash {
				e = fmt.Errorf("device directory mapping does not match the storage connection")
			}
		}
		if e != nil {
			target.close()
			return nil, fmt.Errorf("device directory binding failed: %w", e)
		}
	}
	return target, nil
}
func (c *Client) storagePoll(ctx context.Context) (StorageWork, error) {
	return c.storagePollActive(ctx, nil)
}
func (c *Client) storagePollActive(ctx context.Context, active []string) (StorageWork, error) {
	var work StorageWork
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return work, err
	}
	der, err := x509.MarshalPKIXPublicKey(key.PublicKey())
	if err != nil {
		return work, err
	}
	id := randomID()
	var sealed sealedEnvelope
	request := map[string]any{"publicKey": base64.StdEncoding.EncodeToString(der), "requestId": id}
	if len(active) > 0 {
		request["activeIds"] = active
	}
	err = c.request(ctx, "POST", "/agent/v1/storage/poll", request, &sealed, true)
	if err != nil {
		return work, err
	}
	plain, err := decryptEnvelope(key, id, sealed)
	if err != nil {
		return work, err
	}
	err = json.Unmarshal(plain, &work)
	return work, err
}
func (c *Client) storageReport(ctx context.Context, report StorageReport) (bool, error) {
	var ack struct {
		Proceed bool `json:"proceed"`
	}
	err := c.request(ctx, "POST", "/agent/v1/storage/report", report, &ack, true)
	return ack.Proceed, err
}
func (q *Queue) storageRecord(id string, mutate func(*storageJournal) error) error {
	return q.db.Update(func(tx *bolt.Tx) error {
		b, err := tx.CreateBucketIfNotExists(storageBucket)
		if err != nil {
			return err
		}
		var item storageJournal
		if raw := b.Get([]byte(id)); raw != nil {
			if err = json.Unmarshal(raw, &item); err != nil {
				return err
			}
		}
		if err = mutate(&item); err != nil {
			return err
		}
		raw, err := json.Marshal(item)
		if err != nil {
			return err
		}
		return b.Put([]byte(id), raw)
	})
}
func (c *Client) executeStorage(ctx context.Context, q *Queue, dir string, work StorageWork) error {
	if !safeStoragePath(work.From) || (work.Kind != "HASH" && work.Kind != "COPY") || len(work.ID) != 32 || work.Size < -1 || work.BandwidthMiB < 0 {
		return fmt.Errorf("invalid device storage instruction")
	}
	if work.Kind == "COPY" && (work.Target == nil || !safeStoragePath(work.To) || work.Size < 0 || len(work.Hash) != 64) {
		return fmt.Errorf("invalid device copy instruction")
	}
	// No device command may read or overwrite the Agent's identity and durable queues.
	for _, b := range []*BoundEndpoint{&work.Source, work.Target} {
		if b != nil && b.Endpoint.Protocol == "LOCAL" {
			root, e := filepath.EvalSymlinks(b.Endpoint.Root)
			if e != nil {
				return e
			}
			state, e := filepath.EvalSymlinks(dir)
			if e != nil {
				return e
			}
			if pathsOverlap(root, state) {
				return fmt.Errorf("storage root overlaps agent state")
			}
		}
	}
	// Poll capability is transport metadata, not part of a durable command's identity.
	fingerprintWork := work
	fingerprintWork.ParallelPolling = false
	raw, err := json.Marshal(fingerprintWork)
	if err != nil {
		return err
	}
	fingerprint := sha256.Sum256(raw)
	var cached *StorageReport
	err = q.storageRecord(work.ID, func(item *storageJournal) error {
		hash := hex.EncodeToString(fingerprint[:])
		if item.Fingerprint != "" && item.Fingerprint != hash {
			return fmt.Errorf("command ID reused with different content")
		}
		item.Fingerprint = hash
		cached = item.Result
		item.Updated = time.Now().Unix()
		return nil
	})
	if err != nil {
		return err
	}
	if cached != nil {
		_, err = c.storageReport(ctx, *cached)
		return err
	}
	report := StorageReport{ID: work.ID, Status: "RUNNING", Phase: "HASHING"}
	if work.Kind == "COPY" {
		report.Phase = "COPYING"
	}
	child, cancel := context.WithCancel(ctx)
	defer cancel()
	proceed, err := c.storageReport(child, report)
	if err != nil {
		return err
	}
	var mutex sync.Mutex
	snapshot := func() StorageReport { mutex.Lock(); defer mutex.Unlock(); return report }
	add := func(phase string, copied, verified int64) {
		mutex.Lock()
		report.Phase = phase
		report.Transferred += copied
		report.Verified += verified
		mutex.Unlock()
	}
	done := make(chan struct{})
	var wg sync.WaitGroup
	if proceed {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ticker := time.NewTicker(time.Second)
			defer ticker.Stop()
			lastAck := time.Now()
			for {
				select {
				case <-child.Done():
					return
				case <-done:
					return
				case <-ticker.C:
					ping, finish := context.WithTimeout(child, 4*time.Second)
					accepted, e := c.storageReport(ping, snapshot())
					finish()
					if e == nil {
						lastAck = time.Now()
						if !accepted {
							cancel()
							return
						}
					}
					if time.Since(lastAck) > 10*time.Second {
						cancel()
						return
					}
				}
			}
		}()
		hash, size, runErr := runStorageWork(child, work, add)
		close(done)
		wg.Wait()
		report = snapshot()
		report.Hash = hash
		report.Size = size
		if runErr == nil {
			report.Status = "COMPLETED"
		} else {
			report.Status = "FAILED"
			if errors.Is(runErr, context.Canceled) || child.Err() != nil {
				report.Status = "CANCELLED"
			}
			report.Error = runErr.Error()
			if len(report.Error) > 900 {
				report.Error = report.Error[:900]
			}
		}
	} else {
		report.Status = "CANCELLED"
		report.Error = "Center cancelled this command before execution"
	}
	err = q.storageRecord(work.ID, func(item *storageJournal) error { item.Result = &report; item.Updated = time.Now().Unix(); return nil })
	if err != nil {
		return err
	}
	_, err = c.storageReport(ctx, report)
	return err
}

func runStorageWork(ctx context.Context, w StorageWork, progress func(string, int64, int64)) (string, int64, error) {
	source, err := openBoundTarget(ctx, w.Source)
	if err != nil {
		if w.Source.Endpoint.Protocol != "LOCAL" {
			err = fmt.Errorf("DIRECT_CONNECTION_UNAVAILABLE: %w", err)
		}
		return "", 0, err
	}
	defer source.close()
	before, err := source.stat(w.From)
	if err != nil {
		return "", 0, err
	}
	if before.directory || before.symbolic || (w.Size >= 0 && before.size != w.Size) {
		return "", 0, fmt.Errorf("source type or length changed")
	}
	if w.Kind == "HASH" {
		hash, size, err := storageHash(ctx, source, w.From, w.BandwidthMiB, func(n int) { progress("HASHING", 0, int64(n)) })
		if err == nil {
			after, e := source.stat(w.From)
			if e != nil {
				return "", size, e
			}
			if after != before || size != before.size {
				return "", size, fmt.Errorf("source changed during checksum")
			}
		}
		return hash, size, err
	}
	target, err := openBoundTarget(ctx, *w.Target)
	if err != nil {
		if w.Target.Endpoint.Protocol != "LOCAL" {
			err = fmt.Errorf("DIRECT_CONNECTION_UNAVAILABLE: %w", err)
		}
		return "", 0, err
	}
	defer target.close()
	if w.Source.Endpoint.Protocol == "LOCAL" && w.Target.Endpoint.Protocol == "LOCAL" {
		a := filepath.Join(w.Source.Endpoint.Root, filepath.FromSlash(w.From))
		b := filepath.Join(w.Target.Endpoint.Root, filepath.FromSlash(w.To))
		if pathsOverlap(a, b) {
			return "", 0, fmt.Errorf("source and target overlap")
		}
	}
	if err = target.parents(w.To); err != nil {
		return "", 0, err
	}
	in, err := source.read(w.From)
	if err != nil {
		return "", 0, err
	}
	defer in.Close()
	// Resume only after validating both prefixes. A failed comparison safely restarts the private output.
	offset := int64(0)
	if old, e := target.stat(w.To); e == nil {
		if old.directory || old.symbolic {
			return "", 0, fmt.Errorf("unsafe target file")
		}
		if target.resumable && old.size > 0 && old.size <= w.Size {
			remote, e := target.read(w.To)
			if e != nil {
				return "", 0, e
			}
			a, b := sha256.New(), sha256.New()
			n, e := io.CopyN(a, &pacedReader{ctx: ctx, reader: remote, mib: w.BandwidthMiB, onRead: func(n int) { progress("VERIFYING", 0, int64(n)) }}, old.size)
			remote.Close()
			if e != nil {
				return "", 0, e
			}
			_, e = io.CopyN(b, &pacedReader{ctx: ctx, reader: in, mib: w.BandwidthMiB, onRead: func(n int) { progress("VERIFYING", 0, int64(n)) }}, n)
			if e != nil {
				return "", 0, e
			}
			if hex.EncodeToString(a.Sum(nil)) == hex.EncodeToString(b.Sum(nil)) {
				offset = n
			} else {
				in.Close()
				in, e = source.read(w.From)
				if e != nil {
					return "", 0, e
				}
				defer in.Close()
			}
		}
	}
	if offset > 0 {
		progress("COPYING", offset, 0)
	}
	if offset < w.Size || w.Size == 0 {
		reader := &pacedReader{ctx: ctx, reader: io.LimitReader(in, w.Size-offset), mib: w.BandwidthMiB, onRead: func(n int) { progress("COPYING", int64(n), 0) }}
		if err = target.write(w.To, reader, offset, w.Size); err != nil {
			return "", 0, err
		}
		if int64(reader.count) != w.Size-offset {
			return "", 0, fmt.Errorf("source shortened during copy")
		}
	}
	if err = ctx.Err(); err != nil {
		return "", 0, err
	}
	after, err := source.stat(w.From)
	if err != nil {
		return "", 0, err
	}
	if after != before {
		return "", 0, fmt.Errorf("source changed during copy")
	}
	hash, size, err := storageHash(ctx, target, w.To, w.BandwidthMiB, func(n int) { progress("VERIFYING", 0, int64(n)) })
	if err != nil {
		return "", size, err
	}
	if size != w.Size || hash != w.Hash {
		return "", size, fmt.Errorf("target SHA-256 verification failed")
	}
	return hash, size, nil
}
func storageHash(ctx context.Context, target *remoteTarget, path string, rate float64, onRead func(int)) (string, int64, error) {
	in, err := target.read(path)
	if err != nil {
		return "", 0, err
	}
	defer in.Close()
	hash := sha256.New()
	n, err := io.CopyBuffer(hash, &pacedReader{ctx: ctx, reader: in, mib: rate, onRead: onRead}, make([]byte, 256*1024))
	if err != nil {
		return "", n, err
	}
	if err = ctx.Err(); err != nil {
		return "", n, err
	}
	return hex.EncodeToString(hash.Sum(nil)), n, nil
}
func (c *Client) storageWorker(ctx context.Context, q *Queue, dir string) {
	ctx = c.withFleetBudget(ctx)
	dispatchStorage(ctx, 4, c.storagePollActive, func(ctx context.Context, work StorageWork) error {
		err := c.executeStorage(ctx, q, dir, work)
		if err != nil && ctx.Err() == nil {
			fallback := StorageReport{ID: work.ID, Status: "FAILED", Phase: "HASHING", Error: err.Error()}
			if len(fallback.Error) > 900 {
				fallback.Error = fallback.Error[:900]
			}
			recordErr := q.storageRecord(work.ID, func(item *storageJournal) error {
				if item.Result != nil {
					fallback = *item.Result
				} else {
					item.Result = &fallback
					item.Updated = time.Now().Unix()
				}
				return nil
			})
			if recordErr == nil {
				_, _ = c.storageReport(ctx, fallback)
			}
		}
		return err
	}, func(err error) { log.Printf("Storage execution: %v", err) })
}
