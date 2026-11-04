package main

import (
	"context"
	"sync"
	"time"
)

func fileConcurrency(n int) int {
	if n <= 0 {
		return 4
	}
	return min(n, 16)
}

// A bounded worker pool. Cancellation waits for all writers before the batch is aborted.
func parallelFiles(ctx context.Context, n int, files []SourceNode, worker func(context.Context, <-chan SourceNode) error) error {
	if len(files) == 0 {
		return ctx.Err()
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	queue := make(chan SourceNode)
	var wg sync.WaitGroup
	var once sync.Once
	var first error
	for i := 0; i < min(fileConcurrency(n), len(files)); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := worker(ctx, queue); err != nil {
				once.Do(func() { first = err; cancel() })
			}
		}()
	}
send:
	for _, f := range files {
		select {
		case queue <- f:
		case <-ctx.Done():
			break send
		}
	}
	close(queue)
	wg.Wait()
	if first != nil {
		return first
	}
	return ctx.Err()
}

type transferLimitKey struct{}
type transferLimit struct {
	mu      sync.Mutex
	rate    float64
	charged int64
	next    time.Time
}

func withTransferLimit(ctx context.Context, mib float64) context.Context {
	return context.WithValue(ctx, transferLimitKey{}, &transferLimit{rate: mib * 1024 * 1024})
}
func (l *transferLimit) acquire(ctx context.Context, n int) error {
	if l == nil || l.rate <= 0 || n <= 0 {
		return ctx.Err()
	}
	l.mu.Lock()
	l.charged += int64(n)
	now := time.Now()
	if l.next.Before(now) {
		l.next = now
	}
	l.next = l.next.Add(time.Duration(float64(n) / l.rate * float64(time.Second)))
	delay := time.Until(l.next)
	l.mu.Unlock()
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}

func (l *transferLimit) usage() int64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.charged
}
