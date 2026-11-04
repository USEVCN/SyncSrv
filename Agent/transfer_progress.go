package main

import (
	"context"
	"sort"
	"sync"
	"time"
)

type transferProgressKey struct{}
type sourceFileProgress struct {
	Path       string `json:"path"`
	Phase      string `json:"phase"`
	Offset     int64  `json:"offset"`
	PhaseBytes int64  `json:"phaseBytes"`
}
type transferStats struct {
	InitializationMillis int64  `json:"initializationMillis"`
	TransferMillis       int64  `json:"transferMillis"`
	FinalizationMillis   int64  `json:"finalizationMillis"`
	Concurrency          int    `json:"concurrency"`
	MaxConcurrency       int    `json:"maxConcurrency"`
	Automatic            bool   `json:"automatic"`
	Reason               string `json:"reason"`
}
type transferProgress struct {
	Stats         transferStats
	extended      bool
	transferStart time.Time
	parent        *transferProgress
	key           string
	Files         map[string]*sourceFileProgress `json:"files"`
	mu            sync.Mutex
	SessionID     string `json:"sessionId"`
	Path          string `json:"path"`
	Phase         string `json:"phase"`
	Offset        int64  `json:"offset"`
	Transferred   int64  `json:"transferred"`
	Verified      int64  `json:"verified"`
	Sequence      int64  `json:"sequence"`
}

func sourceProgress(ctx context.Context) *transferProgress {
	p, _ := ctx.Value(transferProgressKey{}).(*transferProgress)
	return p
}
func withSourceFile(ctx context.Context, path string) context.Context {
	p := sourceProgress(ctx)
	if p == nil {
		return ctx
	}
	return context.WithValue(ctx, transferProgressKey{}, &transferProgress{parent: p, key: path})
}
func (p *transferProgress) root() *transferProgress {
	if p != nil && p.parent != nil {
		return p.parent
	}
	return p
}
func (p *transferProgress) file(path, phase string, offset int64) {
	if p == nil {
		return
	}
	r := p.root()
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.Files == nil {
		r.Files = make(map[string]*sourceFileProgress)
	}
	r.Files[path] = &sourceFileProgress{Path: path, Phase: phase, Offset: offset}
	r.Path = path
	r.Phase = phase
	r.Offset = offset
}
func (p *transferProgress) read(count int, verify bool) {
	if p == nil || count <= 0 {
		return
	}
	r := p.root()
	r.mu.Lock()
	defer r.mu.Unlock()
	key := p.key
	if key == "" {
		key = r.Path
	}
	file := r.Files[key]
	if verify {
		r.Verified += int64(count)
		if file != nil {
			file.PhaseBytes += int64(count)
		}
	} else {
		r.Transferred += int64(count)
		if file != nil {
			file.Offset += int64(count)
		}
	}
	if file != nil && key == r.Path {
		r.Offset = file.Offset
	}
}
func (p *transferProgress) done() {
	if p == nil {
		return
	}
	r := p.root()
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.Files, p.key)
}
func (p *transferProgress) snapshot() map[string]any {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.Sequence++
	files := make([]sourceFileProgress, 0, len(p.Files))
	for _, f := range p.Files {
		files = append(files, *f)
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Path < files[j].Path })
	result := map[string]any{"sessionId": p.SessionID, "path": p.Path, "phase": p.Phase, "offset": p.Offset, "transferred": p.Transferred, "verified": p.Verified, "sequence": p.Sequence, "files": files}
	if p.extended {
		stats := p.Stats
		if !p.transferStart.IsZero() {
			stats.TransferMillis = time.Since(p.transferStart).Milliseconds()
		}
		result["stats"] = stats
	}
	return result
}
func (p *transferProgress) concurrency(n, maximum int, automatic bool, reason string) {
	if p == nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.Stats.Concurrency = n
	p.Stats.MaxConcurrency = maximum
	p.Stats.Automatic = automatic
	p.Stats.Reason = reason
}
func (p *transferProgress) timing(initialization, transfer time.Duration) {
	if p == nil {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.Stats.InitializationMillis = initialization.Milliseconds()
	p.Stats.TransferMillis = transfer.Milliseconds()
	if transfer == 0 {
		p.transferStart = time.Now()
	} else {
		p.transferStart = time.Time{}
	}
}
func (p *transferProgress) stats() transferStats {
	if p == nil {
		return transferStats{}
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.Stats
}

// At most one small signed update is in flight. Network latency never blocks file reads.
func (c *Client) watchTransfer(ctx context.Context, session string) (context.Context, func()) {
	p := &transferProgress{SessionID: session, Phase: "CONNECTING_B"}
	reporting, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	go func() {
		defer close(done)
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-reporting.Done():
				return
			case <-ticker.C:
			}
			update := p.snapshot()
			request, stop := context.WithTimeout(reporting, 2*time.Second)
			// Older centers may not implement telemetry. Backup receipts remain independent.
			_ = c.request(request, "POST", "/agent/v1/push/progress", update, nil, true)
			stop()
		}
	}()
	return context.WithValue(ctx, transferProgressKey{}, p), func() { cancel(); <-done }
}
