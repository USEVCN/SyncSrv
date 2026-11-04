package main

import (
	"context"
	"fmt"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/fsnotify/fsevents"
)

func capability() string { return "FSEvents; restart-rescan" }
func watch(ctx context.Context, roots []Root, emit func(Event), fail func(error)) (func(), error) {
	if len(roots) == 0 {
		return func() {}, nil
	}
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	paths := make([]string, 0, len(roots))
	for _, r := range roots {
		paths = append(paths, r.Path)
	}
	stream := &fsevents.EventStream{Paths: paths, Flags: fsevents.FileEvents | fsevents.WatchRoot,
		Latency: 200 * time.Millisecond, Events: make(chan []fsevents.Event, 128)}
	if err := stream.Start(); err != nil {
		return nil, err
	}
	stopped, drained := make(chan struct{}), make(chan struct{})
	var stopping atomic.Bool
	var once sync.Once
	// The native callback sends into Events. Keep draining it until the stream has
	// stopped, including when the parent context was cancelled, or Stop can hang
	// behind a full callback channel. Join the receiver before returning.
	stop := func() {
		once.Do(func() {
			stopping.Store(true)
			stream.Stop()
			close(stopped)
			<-drained
		})
	}
	go func() {
		defer close(drained)
		for {
			select {
			case <-stopped:
				return
			case events, ok := <-stream.Events:
				if !ok {
					return
				}
				for _, e := range events {
					if !stopping.Load() {
						reportDarwinEvent(roots, e, emit, fail)
					}
				}
			}
		}
	}()
	go func() {
		select {
		case <-ctx.Done():
			stop()
		case <-stopped:
		}
	}()
	return stop, nil
}

func reportDarwinEvent(roots []Root, e fsevents.Event, emit func(Event), fail func(error)) {
	abs := filepath.Join("/", e.Path)
	for _, r := range roots {
		rel, err := filepath.Rel(r.Path, abs)
		if err != nil || rel == ".." || strings.HasPrefix(rel, "../") {
			continue
		}
		if e.Flags&(fsevents.UserDropped|fsevents.KernelDropped|fsevents.EventIDsWrapped|fsevents.RootChanged|fsevents.Unmount) != 0 {
			emit(Event{JobID: r.JobID, Kind: "OVERFLOW"})
			fail(fmt.Errorf("FSEvents coverage invalidated for %s", r.JobID))
		} else if rel == "." || e.Flags&fsevents.MustScanSubDirs != 0 {
			// A coalesced notification does not enumerate descendants; force a complete
			// reconciliation instead of treating its reported path as one changed file.
			emit(Event{JobID: r.JobID, Kind: "RESCAN"})
		} else {
			emit(Event{JobID: r.JobID, Path: filepath.ToSlash(rel), Kind: "CHANGE"})
		}
	}
}
