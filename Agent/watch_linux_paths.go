//go:build linux

package main

import (
	"errors"
	"os"
	"strings"
	"sync"
	"syscall"

	"github.com/fsnotify/fsnotify"
	"golang.org/x/sys/unix"
)

type linuxFileID struct{ device, inode uint64 }

// inotify follows an inode across renames; fsnotify's nonrecursive Watcher keeps
// the old pathname for descendants. Repair that binding before adding a moved
// subtree, otherwise later writes are reported using paths which no longer exist.
type linuxWatchPaths struct {
	watcher    *fsnotify.Watcher
	mu         sync.Mutex
	known      sync.Map
	paths      map[string]linuxFileID
	identities map[linuxFileID]string
}

func newLinuxWatchPaths(w *fsnotify.Watcher) *linuxWatchPaths {
	return &linuxWatchPaths{watcher: w, paths: map[string]linuxFileID{}, identities: map[linuxFileID]string{}}
}

func (p *linuxWatchPaths) add(path string) error {
	info, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if !info.IsDir() {
		return syscall.ENOTDIR
	}
	stat := info.Sys().(*syscall.Stat_t)
	id := linuxFileID{uint64(stat.Dev), uint64(stat.Ino)}
	p.mu.Lock()
	defer p.mu.Unlock()
	if previous, ok := p.paths[path]; ok && previous != id {
		if err = p.remove(path); err != nil {
			return err
		}
	}
	if oldPath, ok := p.identities[id]; ok && oldPath != path {
		if err = p.remove(oldPath); err != nil {
			return err
		}
	}
	if err = p.watcher.Add(path); err != nil {
		return err
	}
	p.paths[path] = id
	p.known.Store(path, true)
	p.identities[id] = path
	return nil
}

func (p *linuxWatchPaths) contains(path string) bool {
	_, ok := p.known.Load(path)
	return ok
}

func (p *linuxWatchPaths) retireTree(path string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	// A queued removal may arrive after the same name has been recreated and
	// registered. Never tear down that replacement's already-correct watch.
	info, err := os.Lstat(path)
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if err == nil && info.IsDir() {
		stat := info.Sys().(*syscall.Stat_t)
		id := linuxFileID{uint64(stat.Dev), uint64(stat.Ino)}
		if current, ok := p.paths[path]; ok && current == id {
			return nil
		}
	}
	for candidate := range p.paths {
		if candidate == path || strings.HasPrefix(candidate, path+string(os.PathSeparator)) {
			if err := p.remove(candidate); err != nil {
				return err
			}
		}
	}
	return nil
}

// Caller holds mu. EINVAL means the kernel has already retired a deleted watch.
func (p *linuxWatchPaths) remove(path string) error {
	err := p.watcher.Remove(path)
	if err != nil && !errors.Is(err, fsnotify.ErrNonExistentWatch) && !errors.Is(err, unix.EINVAL) {
		return err
	}
	if id, ok := p.paths[path]; ok {
		delete(p.identities, id)
		delete(p.paths, path)
		p.known.Delete(path)
	}
	return nil
}
