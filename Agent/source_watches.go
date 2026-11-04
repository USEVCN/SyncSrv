package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

type watchFailure struct {
	jobID      string
	generation uint64
	cause      error
}

func (e *watchFailure) Error() string { return fmt.Sprintf("watch %s: %v", e.jobID, e.cause) }
func (e *watchFailure) Unwrap() error { return e.cause }

type sourceWatchStarter func(context.Context, []Root, func(Event), func(error)) (func(), error)

type sourceWatch struct {
	root       Root
	generation uint64
	stop       func()
}

// Owned by the daemon loop. One unavailable root must not remove coverage from
// healthy tasks, and changing bandwidth must not restart file discovery.
type sourceWatches struct {
	ctx        context.Context
	dataDir    string
	emit       func(Event)
	fail       func(error)
	start      sourceWatchStarter
	active     map[string]sourceWatch
	generation uint64
	problems   map[string]string
}

func newSourceWatches(ctx context.Context, dir string, emit func(Event), fail func(error)) *sourceWatches {
	return &sourceWatches{ctx: ctx, dataDir: dir, emit: emit, fail: fail, start: watch, active: map[string]sourceWatch{}, problems: map[string]string{}}
}
func (w *sourceWatches) stopJob(id string) {
	if old, ok := w.active[id]; ok {
		old.stop()
		delete(w.active, id)
	}
}
func (w *sourceWatches) stopAll() {
	for id := range w.active {
		w.stopJob(id)
	}
}
func (w *sourceWatches) roots() []Root {
	roots := make([]Root, 0, len(w.active))
	for _, active := range w.active {
		roots = append(roots, active.root)
	}
	sort.Slice(roots, func(i, j int) bool { return roots[i].JobID < roots[j].JobID })
	return roots
}
func (w *sourceWatches) health() string {
	ids := make([]string, 0, len(w.problems))
	for id := range w.problems {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	var messages []string
	for _, id := range ids {
		messages = append(messages, w.problems[id])
	}
	return strings.Join(messages, "; ")
}
func (w *sourceWatches) invalidate(err error) []Root {
	var failure *watchFailure
	if errors.As(err, &failure) {
		active, ok := w.active[failure.jobID]
		if !ok || active.generation != failure.generation {
			return nil
		}
		w.problems[failure.jobID] = err.Error()
		w.stopJob(failure.jobID)
		return []Root{active.root}
	}
	roots := w.roots()
	for _, r := range roots {
		w.problems[r.JobID] = fmt.Sprintf("watch %s: %v", r.JobID, err)
	}
	w.stopAll()
	return roots
}
func (w *sourceWatches) apply(roots []Root) []Root {
	wanted := make(map[string]bool, len(roots))
	for _, r := range roots {
		wanted[r.JobID] = true
	}
	for id := range w.active {
		if !wanted[id] {
			w.stopJob(id)
		}
	}
	for id := range w.problems {
		if !wanted[id] {
			delete(w.problems, id)
		}
	}
	var started []Root
	for _, root := range roots {
		resolved, err := filepath.EvalSymlinks(root.Path)
		if err == nil && !filepath.IsAbs(root.Path) {
			err = fmt.Errorf("source root must be absolute")
		}
		if err == nil {
			var state string
			state, err = filepath.EvalSymlinks(w.dataDir)
			if err == nil && pathsOverlap(resolved, state) {
				err = fmt.Errorf("source root overlaps agent state")
			}
		}
		if err == nil {
			var info os.FileInfo
			info, err = os.Stat(resolved)
			if err == nil && !info.IsDir() {
				err = fmt.Errorf("source root is not a directory")
			}
		}
		if err == nil {
			err = validateLocalDirectory(resolved)
		}
		if err != nil {
			w.stopJob(root.JobID)
			w.problems[root.JobID] = fmt.Sprintf("watch %s: %v", root.JobID, err)
			continue
		}
		root.Path = resolved
		if old, ok := w.active[root.JobID]; ok && old.root.Path == root.Path {
			old.root = root
			w.active[root.JobID] = old
			delete(w.problems, root.JobID)
			continue
		}
		w.stopJob(root.JobID)
		w.generation++
		generation := w.generation
		stop, err := w.start(w.ctx, []Root{root}, w.emit, func(err error) { w.fail(&watchFailure{root.JobID, generation, err}) })
		if err != nil {
			w.problems[root.JobID] = fmt.Sprintf("watch %s: %v", root.JobID, err)
			continue
		}
		w.active[root.JobID] = sourceWatch{root: root, generation: generation, stop: stop}
		delete(w.problems, root.JobID)
		started = append(started, root)
	}
	return started
}

// A paused/reassigned job must not keep an old transfer running until completion.
func deliveryConfigurationChanged(previous []Root, next Configuration, batch Batch) bool {
	if next.Status != "APPROVED" {
		return true
	}
	oldByID := map[string]Root{}
	for _, root := range previous {
		oldByID[root.JobID] = root
	}
	newByID := map[string]Root{}
	for _, root := range next.Roots {
		newByID[root.JobID] = root
	}
	for _, event := range batch.Events {
		old := oldByID[event.JobID]
		current, ok := newByID[event.JobID]
		if !ok || !current.Enabled || old.Path != current.Path || old.SourceMode != current.SourceMode || old.BandwidthMiB != current.BandwidthMiB || old.TransferThreads != current.TransferThreads || old.AdaptiveTransfers != current.AdaptiveTransfers || old.MaxTransferThreads != current.MaxTransferThreads {
			return true
		}
	}
	return false
}
