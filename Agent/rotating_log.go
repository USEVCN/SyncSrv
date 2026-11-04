package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
)

type rotatingLog struct {
	mu    sync.Mutex
	path  string
	limit int64
	keep  int
	file  *os.File
	size  int64
}

func newRotatingLog(path string, limit int64, keep int) (*rotatingLog, error) {
	if limit < 1024 || keep < 1 || keep > 10 {
		return nil, fmt.Errorf("invalid log retention")
	}
	r := &rotatingLog{path: path, limit: limit, keep: keep}
	err := r.open()
	return r, err
}
func (r *rotatingLog) open() error {
	f, e := os.OpenFile(r.path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0600)
	if e != nil {
		return e
	}
	r.file = f
	s, e := f.Stat()
	if e == nil {
		r.size = s.Size()
	}
	return e
}
func (r *rotatingLog) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.size > 0 && r.size+int64(len(p)) > r.limit {
		if e := r.file.Close(); e != nil {
			return 0, e
		}
		r.file = nil
		for i := r.keep; i >= 1; i-- {
			old := r.path
			if i > 1 {
				old = fmt.Sprintf("%s.%d", r.path, i-1)
			}
			next := fmt.Sprintf("%s.%d", r.path, i)
			if i == r.keep {
				_ = os.Remove(next)
			}
			if e := os.Rename(old, next); e != nil && !os.IsNotExist(e) {
				return 0, e
			}
		}
		if e := r.open(); e != nil {
			return 0, e
		}
	}
	if r.file == nil {
		return 0, io.ErrClosedPipe
	}
	n, e := r.file.Write(p)
	r.size += int64(n)
	return n, e
}
func (r *rotatingLog) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.file == nil {
		return nil
	}
	e := r.file.Close()
	r.file = nil
	return e
}
func serviceLog(dir string) (*rotatingLog, error) {
	return newRotatingLog(filepath.Join(dir, "service.log"), 10<<20, 4)
}
