//go:build linux

package main

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/fsnotify/fsnotify"
	"golang.org/x/sys/unix"
)

func capability() string { return "inotify; restart-rescan" }

func watch(ctx context.Context, roots []Root, emit func(Event), fail func(error)) (func(), error) {
	return watchLinux(ctx, roots, emit, fail, registerLinuxTree)
}

func registerLinuxTree(ctx context.Context, w *linuxWatchPaths, path string) error {
	return filepath.WalkDir(path, func(p string, d fs.DirEntry, err error) error {
		if e := ctx.Err(); e != nil {
			return e
		}
		// A moved/deleted subtree will also produce an event for its parent scope.
		if os.IsNotExist(err) && p != path {
			return nil
		}
		if err != nil {
			return err
		}
		if d.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("unsupported symbolic path: %s", p)
		}
		if !d.IsDir() {
			return nil
		}
		if err := validateLocalDirectory(p); err != nil {
			if os.IsNotExist(err) && p != path {
				return nil
			}
			return err
		}
		if err := w.add(p); err != nil && (!os.IsNotExist(err) || p == path) {
			return err
		}
		return nil
	})
}

// Registration performs filesystem I/O; never do it in the inotify receiver.
// Keep the initial baseline fully watched before returning, then register moved-in
// subtrees with bounded workers and rescan their scope after registration closes
// the window in which descendants may have changed before their watch was armed.
func watchLinux(ctx context.Context, roots []Root, emit func(Event), fail func(error),
	register func(context.Context, *linuxWatchPaths, string) error) (func(), error) {
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, err
	}
	tracked := newLinuxWatchPaths(w)
	child, cancel := context.WithCancel(ctx)
	var workers sync.WaitGroup
	var once sync.Once
	stopped := make(chan struct{})
	stop := func() { once.Do(func() { cancel(); _ = w.Close(); workers.Wait(); close(stopped) }) }
	reportPath := func(path string, op fsnotify.Op) {
		for _, root := range roots {
			rel, err := filepath.Rel(root.Path, path)
			if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
				continue
			}
			if rel == "." {
				emit(Event{JobID: root.JobID, Kind: "RESCAN"})
				if op.Has(fsnotify.Remove) || op.Has(fsnotify.Rename) {
					fail(fmt.Errorf("watched root changed: %s", root.Path))
				}
			} else {
				emit(Event{JobID: root.JobID, Path: filepath.ToSlash(rel), Kind: "CHANGE"})
			}
		}
	}
	type registration struct {
		path   string
		retire bool
	}
	registrations := make(chan registration, 256)
	var pendingMu sync.Mutex
	pending := map[registration]bool{}
	schedule := func(path string, retire bool) {
		work := registration{path, retire}
		pendingMu.Lock()
		defer pendingMu.Unlock()
		if _, ok := pending[work]; ok {
			pending[work] = true // repeat after an in-flight scan to close a new arrival window
			return
		}
		select {
		case registrations <- work:
			pending[work] = false
		default:
			fail(fmt.Errorf("watch registration queue overflow; reconciliation required"))
		}
	}
	for range 4 {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for {
				select {
				case <-child.Done():
					return
				case work := <-registrations:
					for {
						path := work.path
						var err error
						if work.retire {
							err = tracked.retireTree(path)
						} else {
							err = register(child, tracked, path)
						}
						pendingMu.Lock()
						repeat := pending[work]
						if repeat {
							pending[work] = false
						} else {
							delete(pending, work)
						}
						pendingMu.Unlock()
						if child.Err() != nil {
							return
						}
						if err != nil && !os.IsNotExist(err) {
							fail(fmt.Errorf("register subtree %s: %w", path, err))
						} else if !work.retire {
							reportPath(path, 0)
						}
						if !repeat {
							break
						}
					}
				}
			}
		}()
	}
	workers.Add(1)
	go func() {
		defer workers.Done()
		for {
			select {
			case <-child.Done():
				return
			case err, ok := <-w.Errors:
				if !ok {
					return
				}
				if errors.Is(err, unix.EINVAL) {
					// inotify_rm_watch races automatic watch deletion when a renamed
					// directory is removed immediately. Rebuild coverage and persist
					// an overflow marker; do not stop every healthy root for a minute.
					log.Printf("inotify watch invalidated; rebuilding coverage: %v", err)
					for _, root := range roots {
						emit(Event{JobID: root.JobID, Kind: "OVERFLOW"})
						schedule(root.Path, false)
					}
				} else {
					fail(err)
				}
			case e, ok := <-w.Events:
				if !ok {
					return
				}
				// Only directory arrivals need registration; file bursts stay cheap.
				if e.Has(fsnotify.Create) {
					if info, err := os.Lstat(e.Name); err == nil && info.IsDir() {
						schedule(e.Name, false)
					}
				}
				if (e.Has(fsnotify.Remove) || e.Has(fsnotify.Rename)) && tracked.contains(e.Name) {
					schedule(e.Name, true)
				}
				reportPath(e.Name, e.Op)
			}
		}
	}()
	for _, root := range roots {
		if err = register(child, tracked, root.Path); err != nil {
			stop()
			return nil, err
		}
	}
	go func() {
		select {
		case <-ctx.Done():
			stop()
		case <-stopped:
		}
	}()
	return stop, nil
}
