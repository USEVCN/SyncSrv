package main

import (
	"context"
	"sync"
	"sync/atomic"
	"time"
)

type fleetBudget struct {
	mu        sync.Mutex
	enabled   atomic.Bool
	chunk     atomic.Int64
	available int
	client    *Client
}
type fleetBudgetKey struct{}

func (c *Client) withFleetBudget(ctx context.Context) context.Context {
	return context.WithValue(ctx, fleetBudgetKey{}, &c.budget)
}
func (b *fleetBudget) acquire(ctx context.Context, n int) error {
	if !b.enabled.Load() || n <= 0 {
		return ctx.Err()
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.available < n {
		// Batching removes control-plane round trips from each SMB/SFTP packet.
		chunk := int(b.chunk.Load())
		if chunk < n {
			chunk = max(n, 64*1024)
		}
		var grant struct {
			WaitMillis int64 `json:"waitMillis"`
		}
		if err := b.client.request(ctx, "POST", "/agent/v1/bandwidth", map[string]int{"bytes": chunk}, &grant, true); err != nil {
			return err
		}
		timer := time.NewTimer(time.Duration(grant.WaitMillis) * time.Millisecond)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-timer.C:
		}
		b.available += chunk
	}
	b.available -= n
	return nil
}
