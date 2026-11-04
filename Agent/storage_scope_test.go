package main

import (
	"context"
	"testing"
)

func TestLimitedStorageCommandsRunInParallelWithOneSharedLimiter(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	first, second := storageQueueFixture(1), storageQueueFixture(2)
	first.LimitScope = "same-run"
	second.LimitScope = "same-run"
	first.BandwidthMiB = 1
	second.BandwidthMiB = 1
	if !storageCanStart(second, map[string]StorageWork{first.ID: first}) {
		t.Fatal("scoped limiter unnecessarily serialized unrelated files")
	}
	limits := make(chan *transferLimit, 2)
	release := make(chan struct{})
	done := make(chan struct{})
	claims := 0
	go func() {
		defer close(done)
		dispatchStorage(ctx, 2, func(ctx context.Context, _ []string) (StorageWork, error) {
			claims++
			if claims == 1 {
				return first, nil
			}
			if claims == 2 {
				return second, nil
			}
			<-ctx.Done()
			return StorageWork{}, ctx.Err()
		}, func(ctx context.Context, _ StorageWork) error {
			limiter, ok := ctx.Value(transferLimitKey{}).(*transferLimit)
			if !ok {
				t.Error("limited worker has no shared limiter")
			}
			limits <- limiter
			select {
			case <-release:
			case <-ctx.Done():
			}
			return nil
		}, func(e error) {
			if ctx.Err() == nil {
				t.Error(e)
			}
		})
	}()
	a, b := awaitStorage(t, limits), awaitStorage(t, limits)
	if a != b {
		t.Fatal("parallel files received different rate budgets")
	}
	close(release)
	cancel()
	awaitStorage(t, done)
}
