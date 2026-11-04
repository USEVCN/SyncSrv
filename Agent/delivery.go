package main

import (
	"context"
	"errors"
	"sync"
	"time"
)

func (c *Client) deliver(ctx context.Context, roots []Root, batch Batch) ([]string, error) {
	return deliverWith(ctx, roots, batch, c.deliverPart)
}

func deliverWith(ctx context.Context, roots []Root, batch Batch, send func(context.Context, Root, Batch) ([]string, error)) ([]string, error) {
	var acknowledged []string
	var firstError error
	for _, root := range roots {
		part := Batch{Epoch: batch.Epoch}
		for _, event := range batch.Events {
			if event.JobID == root.JobID {
				part.Events = append(part.Events, event)
			}
		}
		if len(part.Events) == 0 {
			continue
		}
		ids, err := send(ctx, root, part)
		var sourceError *sourceReadError
		// Retry independent scopes after a local read failure. A full baseline remains atomic.
		if errors.As(err, &sourceError) && len(part.Events) > 1 && !root.FullScanRequired {
			ids, err = nil, nil
			for _, event := range part.Events {
				done, failure := send(ctx, root, Batch{Epoch: part.Epoch, Events: []Event{event}})
				ids = append(ids, done...)
				if failure != nil && !errors.Is(failure, errDebouncing) && err == nil {
					err = failure
				}
				if ctx.Err() != nil {
					break
				}
			}
		}
		acknowledged = append(acknowledged, ids...)
		if err != nil && !errors.Is(err, errDebouncing) && firstError == nil {
			firstError = err
		}
		if ctx.Err() != nil {
			return acknowledged, ctx.Err()
		}
	}
	if firstError != nil {
		return acknowledged, firstError
	}
	if len(acknowledged) == 0 {
		return nil, errDebouncing
	}
	return acknowledged, nil
}

func (c *Client) deliverPart(ctx context.Context, root Root, part Batch) ([]string, error) {
	if root.SourceMode == "AGENT" {
		var start struct {
			Allowed    bool  `json:"allowed"`
			WaitMillis int64 `json:"waitMillis"`
		}
		if err := c.request(ctx, "POST", "/agent/v1/changes/start", part, &start, true); err != nil {
			return nil, err
		}
		if !start.Allowed {
			return nil, errDebouncing
		}
		progress := c.changeProgress(ctx, part)
		if err := c.pushSource(ctx, root, part.Events, progress); err != nil {
			// Pause/reassignment/service shutdown already aborts its push draft.
			// Do not replace the center's deliberate pause with a retry failure.
			if ctx.Err() != nil {
				return nil, err
			}
			cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			var sourceError *sourceReadError
			reported := false
			if errors.As(err, &sourceError) && sourceError.busy && validRelative(sourceError.path) == nil {
				reported = c.request(cleanup, "POST", "/agent/v1/changes/progress", map[string]any{"batch": part, "phase": "SOURCE_BUSY", "path": sourceError.path}, nil, true) == nil
			}
			if !reported {
				_ = c.request(cleanup, "POST", "/agent/v1/changes/failed", part, nil, true)
			}
			return nil, err
		}
	}
	var reply struct {
		IDs []string `json:"acknowledged"`
	}
	if err := c.request(ctx, "POST", "/agent/v1/events", part, &reply, true); err != nil {
		return nil, err
	}
	return reply.IDs, nil
}

// Durable start/finish receipts are separate; coalesce observational updates across file workers.
func (c *Client) changeProgress(ctx context.Context, part Batch) func(string, string) error {
	var mu sync.Mutex
	var last time.Time
	var failure error
	inFlight := false
	return func(phase, path string) error {
		mu.Lock()
		if failure != nil {
			err := failure
			mu.Unlock()
			return err
		}
		if inFlight || time.Since(last) < time.Second {
			mu.Unlock()
			return ctx.Err()
		}
		inFlight = true
		mu.Unlock()
		// One observer sends the update; other file workers keep reading/writing.
		err := c.request(ctx, "POST", "/agent/v1/changes/progress", map[string]any{"batch": part, "phase": phase, "path": path}, nil, true)
		mu.Lock()
		defer mu.Unlock()
		inFlight = false
		failure = err
		if err == nil {
			last = time.Now()
		}
		return err
	}
}
