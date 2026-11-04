package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pkg/sftp"
)

type pipelineStore struct {
	mu         sync.Mutex
	data       []byte
	arrived    chan struct{}
	release    chan struct{}
	releaseOne sync.Once
	failOffset atomic.Int64
	active     atomic.Int32
	peak       atomic.Int32
}

func (s *pipelineStore) unblock()                                     { s.releaseOne.Do(func() { close(s.release) }) }
func (s *pipelineStore) Filewrite(*sftp.Request) (io.WriterAt, error) { return s, nil }
func (s *pipelineStore) Filecmd(r *sftp.Request) error {
	if r.Method != "Setstat" {
		return errors.New("unsupported test command")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.data = s.data[:r.Attributes().Size]
	return nil
}
func (s *pipelineStore) WriteAt(data []byte, offset int64) (int, error) {
	n := s.active.Add(1)
	defer s.active.Add(-1)
	for old := s.peak.Load(); n > old; old = s.peak.Load() {
		if s.peak.CompareAndSwap(old, n) {
			break
		}
	}
	select {
	case s.arrived <- struct{}{}:
	default:
	}
	<-s.release
	if offset == s.failOffset.Load() {
		return 0, errors.New("fixture write failure")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	end := int(offset) + len(data)
	if end > len(s.data) {
		s.data = append(s.data, make([]byte, end-len(s.data))...)
	}
	return copy(s.data[int(offset):], data), nil
}
func (s *pipelineStore) snapshot() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	return bytes.Clone(s.data)
}

// RequestServer has no fsync extension. Hide only Sync in this fixture;
// production writeRemote still uses the real SFTP handle's durable flush.
type pipelineFile struct{ f *sftp.File }

func (f pipelineFile) Write(p []byte) (int, error)        { return f.f.Write(p) }
func (f pipelineFile) Seek(n int64, w int) (int64, error) { return f.f.Seek(n, w) }
func (f pipelineFile) Close() error                       { return f.f.Close() }
func (f pipelineFile) Truncate(n int64) error             { return f.f.Truncate(n) }
func (f pipelineFile) ReadFromWithConcurrency(r io.Reader, n int) (int64, error) {
	return f.f.ReadFromWithConcurrency(r, n)
}
func newPipelineFixture(t *testing.T) (*pipelineStore, *sftp.Client) {
	t.Helper()
	s := &pipelineStore{arrived: make(chan struct{}, 8), release: make(chan struct{})}
	s.failOffset.Store(-1)
	clientWire, serverWire := net.Pipe()
	server := sftp.NewRequestServer(serverWire, sftp.Handlers{FilePut: s, FileCmd: s})
	served := make(chan error, 1)
	go func() { served <- server.Serve() }()
	client, err := sftp.NewClientPipe(clientWire, clientWire)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		s.unblock()
		client.Close()
		server.Close()
		awaitStorage(t, served)
	})
	return s, client
}
func openPipelineFile(t *testing.T, client *sftp.Client) pipelineFile {
	t.Helper()
	f, err := client.OpenFile("archive.part", os.O_CREATE|os.O_WRONLY)
	if err != nil {
		t.Fatal(err)
	}
	return pipelineFile{f}
}
func TestSFTPPipelineOverlapsRequestsAndKeepsSHA(t *testing.T) {
	s, client := newPipelineFixture(t)
	f := openPipelineFile(t, client)
	data := bytes.Repeat([]byte("archive"), 64*1024)
	done := make(chan error, 1)
	go func() {
		done <- writeRemote(f, &pacedReader{ctx: context.Background(), reader: bytes.NewReader(data)}, 0)
	}()
	// No write response is released until several real SFTP writes arrived.
	for i := 0; i < 4; i++ {
		awaitStorage(t, s.arrived)
	}
	s.unblock()
	if err := awaitStorage(t, done); err != nil {
		t.Fatal(err)
	}
	if s.peak.Load() < 4 || s.active.Load() != 0 || hashData(s.snapshot()) != hashData(data) {
		t.Fatalf("pipeline peak=%d active=%d or SHA mismatch", s.peak.Load(), s.active.Load())
	}
}
func TestSFTPPipelineFailureDrainsAndTruncatesBeforeResume(t *testing.T) {
	s, client := newPipelineFixture(t)
	data := bytes.Repeat([]byte("resume"), 64*1024)
	s.failOffset.Store(32 * 1024)
	f := openPipelineFile(t, client)
	done := make(chan error, 1)
	go func() {
		done <- writeRemote(f, &pacedReader{ctx: context.Background(), reader: bytes.NewReader(data)}, 0)
	}()
	for i := 0; i < 4; i++ {
		awaitStorage(t, s.arrived)
	}
	s.unblock()
	if err := awaitStorage(t, done); err == nil {
		t.Fatal("failed write was accepted")
	}
	prefix := s.snapshot()
	if s.active.Load() != 0 || len(prefix) != 32*1024 || !bytes.Equal(prefix, data[:len(prefix)]) {
		t.Fatalf("unsafe resume prefix: size=%d active=%d", len(prefix), s.active.Load())
	}
	s.failOffset.Store(-1)
	f = openPipelineFile(t, client)
	if err := writeRemote(f, &pacedReader{ctx: context.Background(), reader: bytes.NewReader(data[len(prefix):])}, int64(len(prefix))); err != nil {
		t.Fatal(err)
	}
	if hashData(s.snapshot()) != hashData(data) {
		t.Fatal("resumed content SHA differs")
	}
}
func TestSFTPPipelineCancellationWaitsForIssuedRequests(t *testing.T) {
	s, client := newPipelineFixture(t)
	f := openPipelineFile(t, client)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	go func() {
		done <- writeRemote(f, &pacedReader{ctx: ctx, reader: bytes.NewReader(bytes.Repeat([]byte("x"), 1024*1024))}, 0)
	}()
	for i := 0; i < 4; i++ {
		awaitStorage(t, s.arrived)
	}
	cancel()
	select {
	case <-done:
		t.Fatal("returned before outstanding SFTP requests settled")
	case <-time.After(50 * time.Millisecond):
	}
	s.unblock()
	if err := awaitStorage(t, done); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancellation hidden: %v", err)
	}
	if s.active.Load() != 0 {
		t.Fatal("a write was still running")
	}
}
func TestSFTPPipelineDoesNotMultiplyBandwidthCap(t *testing.T) {
	s, client := newPipelineFixture(t)
	s.unblock()
	f := openPipelineFile(t, client)
	data := bytes.Repeat([]byte("x"), 256*1024)
	ctx := withTransferLimit(context.Background(), 1)
	started := time.Now()
	if err := writeRemote(f, &pacedReader{ctx: ctx, reader: bytes.NewReader(data), mib: 1}, 0); err != nil {
		t.Fatal(err)
	}
	if elapsed := time.Since(started); elapsed < 240*time.Millisecond {
		t.Fatalf("four requests multiplied the bandwidth cap: %s", elapsed)
	}
	if hashData(s.snapshot()) != hashData(data) {
		t.Fatal("rate-limited content differs")
	}
}
