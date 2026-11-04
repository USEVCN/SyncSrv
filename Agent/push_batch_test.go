package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func smallFiles(t testing.TB, count int) string {
	t.Helper()
	root := t.TempDir()
	for i := 0; i < count; i++ {
		if err := os.WriteFile(filepath.Join(root, fmt.Sprintf("%05d.txt", i)), make([]byte, 4096), 0600); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

func TestSmallFileBatchReceiptsAndLegacyCompatibility(t *testing.T) {
	for _, batch := range []bool{false, true} {
		t.Run(fmt.Sprint(batch), func(t *testing.T) {
			f, c, closeServer := newPushFixture(t, batch)
			defer closeServer()
			if err := c.pushSource(context.Background(), Root{JobID: "job", Path: smallFiles(t, 256), FullScanRequired: true, TransferThreads: 4, AdaptiveTransfers: true, MaxTransferThreads: 8}, nil); err != nil {
				t.Fatal(err)
			}
			f.mu.Lock()
			defer f.mu.Unlock()
			if len(f.completed) != 256 || f.finished != 1 || f.aborted != 0 {
				t.Fatalf("lost receipts: %d %d %d", len(f.completed), f.finished, f.aborted)
			}
			if batch {
				if f.requests["/agent/v1/push/direct-complete"] != 0 || f.requests["/agent/v1/push/direct-complete-batch"] >= 128 {
					t.Fatal("small files not batched")
				}
				if f.stats.TransferMillis <= 0 || !f.stats.Automatic || f.stats.MaxConcurrency != 8 {
					t.Fatalf("missing statistics: %+v", f.stats)
				}
			} else if f.requests["/agent/v1/push/direct-complete"] != 256 {
				t.Fatal("legacy protocol changed")
			}
		})
	}
}

func TestBatchReceiptFailureAbortsWithoutPublishing(t *testing.T) {
	f, c, closeServer := newPushFixture(t, true)
	defer closeServer()
	f.rejectComplete = true
	err := c.pushSource(context.Background(), Root{JobID: "job", Path: smallFiles(t, 64), FullScanRequired: true, TransferThreads: 4}, nil)
	if err == nil {
		t.Fatal("failed receipt accepted")
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.finished != 0 || f.aborted != 1 || c.uploadFiles.Load() != 0 {
		t.Fatal("unacknowledged batch published")
	}
}

func TestAdaptiveProbeRollbackCooldownAndBandwidth(t *testing.T) {
	now := time.Now()
	tuner := concurrencyTuner{limit: 4, maximum: 8}
	tuner.sample(now, 100, 0.04, false, true)
	if tuner.limit != 6 {
		t.Fatal("no probe")
	}
	tuner.sample(now.Add(3*time.Second), 101, 0.06, false, true)
	if tuner.limit != 4 || tuner.probing {
		t.Fatal("unproductive probe retained")
	}
	tuner.sample(now.Add(6*time.Second), 101, 0.06, false, true)
	if tuner.limit != 4 {
		t.Fatal("cooldown ignored")
	}
	tuner.sample(now.Add(time.Minute), 101, 0.06, true, true)
	if tuner.limit != 4 {
		t.Fatal("bandwidth saturation increased concurrency")
	}
	tuner.sample(now.Add(2*time.Minute), 101, 0.06, false, true)
	tuner.sample(now.Add(2*time.Minute+3*time.Second), 140, 0.065, false, true)
	if tuner.limit != 6 {
		t.Fatal("productive concurrency not retained")
	}
	tuner.sample(now.Add(2*time.Minute+6*time.Second), 50, 0.2, false, true)
	if tuner.limit != 3 {
		t.Fatal("overloaded storage did not back off")
	}
}

func TestBatchPoolBoundsCancellationAndConnectionLifetime(t *testing.T) {
	files := make([]SourceNode, 128)
	ctx, cancel := context.WithCancel(context.Background())
	var active, peak, opened, closed atomic.Int32
	started := make(chan struct{}, 4)
	done := make(chan error, 1)
	go func() {
		done <- parallelBatches(ctx, Root{AdaptiveTransfers: true, MaxTransferThreads: 3}, files, func(ctx context.Context) (batchWorker, func(), error) {
			opened.Add(1)
			return func(ctx context.Context, _ []SourceNode) error {
				n := active.Add(1)
				for old := peak.Load(); n > old; old = peak.Load() {
					if peak.CompareAndSwap(old, n) {
						break
					}
				}
				started <- struct{}{}
				<-ctx.Done()
				active.Add(-1)
				return ctx.Err()
			}, func() { closed.Add(1) }, nil
		})
	}()
	for i := 0; i < 3; i++ {
		select {
		case <-started:
		case <-time.After(time.Second):
			t.Fatal("pool did not start")
		}
	}
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("cancellation blocked")
	}
	if peak.Load() != 3 || active.Load() != 0 || opened.Load() != closed.Load() {
		t.Fatal("writer or connection leaked")
	}
}

func TestAutomaticPoolActuallyAddsWorkersWhileBacklogged(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	var active, peak atomic.Int32
	err := parallelBatches(ctx, Root{AdaptiveTransfers: true, MaxTransferThreads: 8}, make([]SourceNode, 3200), func(ctx context.Context) (batchWorker, func(), error) {
		return func(ctx context.Context, _ []SourceNode) error {
			n := active.Add(1)
			defer active.Add(-1)
			for old := peak.Load(); n > old; old = peak.Load() {
				if peak.CompareAndSwap(old, n) {
					break
				}
			}
			timer := time.NewTimer(200 * time.Millisecond)
			defer timer.Stop()
			select {
			case <-timer.C:
				return nil
			case <-ctx.Done():
				return ctx.Err()
			}
		}, func() {}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if peak.Load() <= 4 || peak.Load() > 8 {
		t.Fatalf("adaptive worker count: %d", peak.Load())
	}
}

func BenchmarkSmallFileEndToEnd(b *testing.B) {
	for _, batch := range []bool{false, true} {
		b.Run(fmt.Sprintf("batch=%v", batch), func(b *testing.B) {
			root := smallFiles(b, 256)
			b.ResetTimer()
			for i := 0; i < b.N; i++ {
				b.StopTimer()
				f, c, closeServer := newPushFixture(b, batch)
				f.requestDelay = 2 * time.Millisecond
				b.StartTimer()
				err := c.pushSource(context.Background(), Root{JobID: "job", Path: root, FullScanRequired: true, TransferThreads: 4}, nil)
				b.StopTimer()
				if err != nil {
					b.Fatal(err)
				}
				f.mu.Lock()
				requests := 0
				for path, n := range f.requests {
					if path != "/agent/v1/push/progress" {
						requests += n
					}
				}
				f.mu.Unlock()
				b.ReportMetric(float64(requests), "requests/op")
				closeServer()
				b.StartTimer()
			}
		})
	}
}
