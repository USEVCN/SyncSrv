package main

import (
	"context"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"sync"

	"golang.org/x/sys/windows"
)

func capability() string { return "ReadDirectoryChangesW subtree; restart-rescan" }

// One recursive handle watches the whole source tree. Holding a watch on every
// child directory can keep deleted children pending deletion; adding watches
// from fsnotify's event receiver can also deadlock its Windows I/O thread.
// A separate, nonrecursive parent watch detects replacement/removal of the root
// itself (ReadDirectoryChangesW does not report changes to its own directory).
func watch(ctx context.Context, roots []Root, emit func(Event), fail func(error)) (func(), error) {
	var watches []*windowsTreeWatch
	var once sync.Once
	stopped := make(chan struct{})
	stop := func() {
		once.Do(func() {
			// Cancel every outstanding read before waiting for any worker.
			for _, w := range watches {
				w.cancel()
			}
			for _, w := range watches {
				<-w.done
			}
			close(stopped)
		})
	}
	for _, root := range roots {
		if err := ctx.Err(); err != nil {
			stop()
			return nil, err
		}
		if err := validateLocalDirectory(root.Path); err != nil {
			stop()
			return nil, err
		}
		root.Path = filepath.Clean(root.Path)
		report := func(err error) {
			emit(Event{JobID: root.JobID, Kind: "OVERFLOW"})
			fail(fmt.Errorf("Windows watch coverage invalidated for %s: %w", root.JobID, err))
		}
		parent := filepath.Dir(root.Path)
		if parent != root.Path {
			w, err := startWindowsTreeWatch(parent, false, func(changes []directoryChange) {
				for _, change := range changes {
					if strings.EqualFold(change.path, filepath.Base(root.Path)) &&
						(change.action == windows.FILE_ACTION_REMOVED || change.action == windows.FILE_ACTION_RENAMED_OLD_NAME) {
						report(fmt.Errorf("watched root changed: %s", root.Path))
						return
					}
				}
			}, report)
			if err != nil {
				stop()
				return nil, err
			}
			watches = append(watches, w)
		}
		w, err := startWindowsTreeWatch(root.Path, true, func(changes []directoryChange) {
			for _, change := range changes {
				// Both sides of a rename are dirty scopes. Newly created or moved-in
				// directories are already covered by the kernel's recursive watch.
				emit(Event{JobID: root.JobID, Path: change.path, Kind: "CHANGE"})
			}
		}, report)
		if err != nil {
			stop()
			return nil, err
		}
		watches = append(watches, w)
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

type windowsTreeWatch struct {
	mu         sync.Mutex
	directory  windows.Handle
	overlapped windows.Overlapped
	buffer     []byte
	recursive  bool
	filter     uint32
	pending    bool
	stopping   bool
	done       chan struct{}
}

func startWindowsTreeWatch(path string, recursive bool, emit func([]directoryChange), fail func(error)) (*windowsTreeWatch, error) {
	name, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return nil, err
	}
	handle, err := windows.CreateFile(name, windows.FILE_LIST_DIRECTORY,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil, windows.OPEN_EXISTING, windows.FILE_FLAG_BACKUP_SEMANTICS|windows.FILE_FLAG_OVERLAPPED, 0)
	if err != nil {
		return nil, fmt.Errorf("open watch %s: %w", path, err)
	}
	event, err := windows.CreateEvent(nil, 1, 0, nil)
	if err != nil {
		windows.CloseHandle(handle)
		return nil, err
	}
	w := &windowsTreeWatch{
		directory: handle, overlapped: windows.Overlapped{HEvent: event},
		buffer: make([]byte, 64*1024), recursive: recursive, done: make(chan struct{}),
		filter: windows.FILE_NOTIFY_CHANGE_FILE_NAME | windows.FILE_NOTIFY_CHANGE_DIR_NAME,
	}
	if recursive {
		w.filter |= windows.FILE_NOTIFY_CHANGE_SIZE | windows.FILE_NOTIFY_CHANGE_LAST_WRITE | windows.FILE_NOTIFY_CHANGE_ATTRIBUTES | windows.FILE_NOTIFY_CHANGE_SECURITY
	}
	// Arm synchronously before returning: the caller can immediately establish
	// its baseline without an unmonitored startup window.
	if err = w.arm(); err != nil {
		w.release()
		return nil, fmt.Errorf("start watch %s: %w", path, err)
	}
	go w.run(emit, fail)
	return w, nil
}

func (w *windowsTreeWatch) arm() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.stopping {
		return context.Canceled
	}
	if err := windows.ResetEvent(w.overlapped.HEvent); err != nil {
		return err
	}
	err := windows.ReadDirectoryChanges(w.directory, &w.buffer[0], uint32(len(w.buffer)), w.recursive,
		w.filter, nil, &w.overlapped, 0)
	if err != nil && !errors.Is(err, windows.ERROR_IO_PENDING) {
		return err
	}
	w.pending = true
	return nil
}

func (w *windowsTreeWatch) cancel() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.stopping = true
	if w.pending {
		// The worker owns closing. CancelIoEx only requests cancellation; closing
		// the handle/buffer here would race an outstanding kernel write.
		_ = windows.CancelIoEx(w.directory, &w.overlapped)
	}
}

func (w *windowsTreeWatch) release() {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.stopping = true
	w.pending = false
	windows.CloseHandle(w.directory)
	windows.CloseHandle(w.overlapped.HEvent)
	close(w.done)
}

func (w *windowsTreeWatch) run(emit func([]directoryChange), fail func(error)) {
	defer w.release()
	for {
		var size uint32
		err := windows.GetOverlappedResult(w.directory, &w.overlapped, &size, true)
		w.mu.Lock()
		w.pending = false
		stopping := w.stopping
		w.mu.Unlock()
		if stopping {
			return
		}
		if err != nil {
			fail(fmt.Errorf("read directory changes: %w", err))
			return
		}
		if size == 0 || size > uint32(len(w.buffer)) {
			fail(fmt.Errorf("directory notification buffer overflow; reconciliation required"))
			return
		}
		changes, err := decodeDirectoryChanges(w.buffer[:size])
		if err != nil {
			fail(err)
			return
		}
		// Reissue I/O before handing records to the queue. No Add/Remove calls,
		// directory walks or network requests run inside this completion loop.
		if err = w.arm(); err != nil {
			if !errors.Is(err, context.Canceled) {
				fail(err)
			}
			return
		}
		emit(changes)
	}
}
