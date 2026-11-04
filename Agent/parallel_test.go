package main

import (
	"context"
	"errors"
	"io"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestParallelPoolBoundsAndWaitsForCancelledWriters(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	entered := make(chan struct{}, 8)
	release := make(chan struct{})
	done := make(chan error, 1)
	var active, peak, completed atomic.Int32
	files := make([]SourceNode, 8)
	go func() {
		done <- parallelFiles(ctx, 3, files, func(ctx context.Context, q <-chan SourceNode) error {
			for range q {
				n := active.Add(1)
				for old := peak.Load(); n > old; old = peak.Load() {
					if peak.CompareAndSwap(old, n) {
						break
					}
				}
				entered <- struct{}{}
				select {
				case <-release:
				case <-ctx.Done():
					return ctx.Err()
				}
				active.Add(-1)
				completed.Add(1)
			}
			return nil
		})
	}()
	for i := 0; i < 3; i++ {
		select {
		case <-entered:
		case <-ctx.Done():
			t.Fatal("workers did not overlap")
		}
	}
	select {
	case <-entered:
		t.Fatal("concurrency exceeded 3")
	default:
	}
	close(release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if peak.Load() != 3 || completed.Load() != 8 {
		t.Fatalf("peak=%d completed=%d", peak.Load(), completed.Load())
	}

	failure := errors.New("read failed")
	started := make(chan struct{})
	stopped := make(chan struct{})
	err := parallelFiles(ctx, 2, []SourceNode{{Path: "fail"}, {Path: "wait"}}, func(ctx context.Context, q <-chan SourceNode) error {
		for f := range q {
			if f.Path == "fail" {
				<-started
				return failure
			}
			close(started)
			<-ctx.Done()
			close(stopped)
			return ctx.Err()
		}
		return nil
	})
	if !errors.Is(err, failure) {
		t.Fatal(err)
	}
	select {
	case <-stopped:
	default:
		t.Fatal("batch returned while a writer was alive")
	}
}

func TestParallelReadersShareOneBandwidthBudget(t *testing.T) {
	ctx := withTransferLimit(context.Background(), 1)
	start := time.Now()
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := io.Copy(io.Discard, &pacedReader{ctx: ctx, reader: strings.NewReader(strings.Repeat("x", 128*1024)), mib: 1})
			if err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if elapsed := time.Since(start); elapsed < 490*time.Millisecond {
		t.Fatalf("aggregate limit multiplied by workers: %s", elapsed)
	}
	cancelled, cancel := context.WithCancel(withTransferLimit(context.Background(), 0.00001))
	cancel()
	if err := cancelled.Value(transferLimitKey{}).(*transferLimit).acquire(cancelled, 100000); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}

func TestConcurrentTelemetryKeepsEachFileAndAggregateCounters(t *testing.T) {
	p := &transferProgress{SessionID: "session"}
	ctx := context.WithValue(context.Background(), transferProgressKey{}, p)
	a, b := sourceProgress(withSourceFile(ctx, "a")), sourceProgress(withSourceFile(ctx, "b"))
	a.file("a", "UPLOADING_B", 10)
	b.file("b", "HASHING_SOURCE", 0)
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for i := 0; i < 100; i++ {
			a.read(2, false)
		}
	}()
	go func() {
		defer wg.Done()
		for i := 0; i < 100; i++ {
			b.read(3, true)
		}
	}()
	wg.Wait()
	s := p.snapshot()
	files := s["files"].([]sourceFileProgress)
	if len(files) != 2 || files[0].Offset != 210 || files[1].PhaseBytes != 300 || s["transferred"] != int64(200) || s["verified"] != int64(300) {
		t.Fatalf("mixed file counters: %+v", s)
	}
	a.done()
	if got := p.snapshot()["files"].([]sourceFileProgress); len(got) != 1 || got[0].Path != "b" {
		t.Fatalf("completed file persisted: %v", got)
	}
}

func TestParentsAcceptsAConcurrentCreatorButRejectsUnsafePath(t *testing.T) {
	calls := 0
	r := &remoteTarget{stat: func(string) (targetInfo, error) {
		calls++
		if calls == 1 {
			return targetInfo{}, errors.New("missing")
		}
		return targetInfo{directory: true}, nil
	}, mkdir: func(string) error { return errors.New("exists") }}
	if err := r.parents("parent/file"); err != nil {
		t.Fatal(err)
	}
	r.stat = func(string) (targetInfo, error) { return targetInfo{directory: true, symbolic: true}, nil }
	if err := r.parents("parent/file"); err == nil {
		t.Fatal("symbolic parent accepted")
	}
}
