// Native local storage bridge. No network, credentials, service or shell execution.
package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const chunkLimit = 1024 * 1024

type request struct {
	Op        string `json:"op"`
	Path      string `json:"path"`
	To        string `json:"to"`
	Offset    int64  `json:"offset"`
	Count     int    `json:"count"`
	Handle    int    `json:"handle"`
	Directory bool   `json:"directory"`
	Modified  int64  `json:"modified"`
}
type item struct {
	Name      string `json:"name"`
	Directory bool   `json:"directory"`
	Size      int64  `json:"size"`
	Modified  int64  `json:"modified"`
	Symbolic  bool   `json:"symbolic"`
}
type response struct {
	OK     bool   `json:"ok"`
	Error  string `json:"error,omitempty"`
	Handle int    `json:"handle,omitempty"`
	Length int    `json:"length"`
	End    bool   `json:"end,omitempty"`
	Item   *item  `json:"item,omitempty"`
	Items  []item `json:"items,omitempty"`
}
type handle struct {
	file  *os.File
	write bool
}
type bridge struct {
	root    *os.Root
	handles map[int]handle
	next    int
}

func main() {
	if len(os.Args) != 2 || !filepath.IsAbs(os.Args[1]) {
		os.Exit(2)
	}
	root, err := os.OpenRoot(os.Args[1])
	if err != nil {
		os.Exit(3)
	}
	defer root.Close()
	b := bridge{root: root, handles: map[int]handle{}}
	if err = b.serve(os.Stdin, os.Stdout); err != nil {
		os.Exit(4)
	}
}
func (b *bridge) serve(input io.Reader, output io.Writer) error {
	defer func() {
		for _, h := range b.handles {
			h.file.Close()
		}
	}()
	in := bufio.NewReaderSize(input, 32*1024)
	out := bufio.NewWriterSize(output, 64*1024)
	for {
		line, err := in.ReadSlice('\n')
		if err == io.EOF && len(line) == 0 {
			return nil
		}
		if err != nil {
			return err
		}
		var q request
		if json.Unmarshal(line, &q) != nil {
			return errors.New("invalid request")
		}
		var body []byte
		if q.Op == "write" {
			if q.Count < 0 || q.Count > chunkLimit {
				return errors.New("invalid frame")
			}
			body = make([]byte, q.Count)
			if _, err = io.ReadFull(in, body); err != nil {
				return err
			}
		}
		r, data, err := b.execute(q, body)
		if err != nil {
			r = response{Error: errorCode(err)}
		} else {
			r.OK = true
		}
		if err = json.NewEncoder(out).Encode(r); err != nil {
			return err
		}
		if _, err = out.Write(data); err != nil {
			return err
		}
		if err = out.Flush(); err != nil {
			return err
		}
	}
}
func errorCode(err error) string {
	switch {
	case errors.Is(err, os.ErrNotExist):
		return "missing"
	case errors.Is(err, os.ErrExist):
		return "exists"
	case errors.Is(err, os.ErrPermission):
		return "denied"
	default:
		return "io"
	}
}
func info(a os.FileInfo) item {
	return item{a.Name(), a.IsDir(), a.Size(), a.ModTime().UnixMilli(), a.Mode()&os.ModeSymlink != 0}
}
func (b *bridge) valid(p string) error {
	if p == "" || p == "." {
		return nil
	}
	if !filepath.IsLocal(p) || strings.ContainsAny(p, "\\\x00") || (runtime.GOOS == "windows" && strings.Contains(p, ":")) {
		return errors.New("invalid path")
	}
	parts := strings.Split(p, "/")
	for i, part := range parts {
		if part == "" || part == "." || part == ".." {
			return errors.New("invalid path")
		}
		a, err := b.root.Lstat(strings.Join(parts[:i+1], "/"))
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		if err != nil {
			return err
		}
		if a.Mode()&os.ModeSymlink != 0 {
			return errors.New("symbolic link rejected")
		}
	}
	return nil
}
func path(p string) string {
	if p == "" {
		return "."
	}
	return p
}
func (b *bridge) add(f *os.File, w bool) (response, []byte, error) {
	if len(b.handles) >= 64 {
		f.Close()
		return response{}, nil, errors.New("handle limit")
	}
	b.next++
	b.handles[b.next] = handle{f, w}
	return response{Handle: b.next}, nil, nil
}
func (b *bridge) execute(q request, body []byte) (response, []byte, error) {
	var r response
	if q.Op == "ping" {
		return r, nil, nil
	}
	if q.Op == "read" || q.Op == "write" || q.Op == "list-next" || q.Op == "close" {
		h, ok := b.handles[q.Handle]
		if !ok {
			return r, nil, errors.New("unknown handle")
		}
		switch q.Op {
		case "read":
			if h.write || q.Count < 0 || q.Count > chunkLimit {
				return r, nil, errors.New("invalid read")
			}
			data := make([]byte, q.Count)
			n, e := h.file.Read(data)
			if e != nil && e != io.EOF {
				return r, nil, e
			}
			r.Length = n
			r.End = e == io.EOF
			return r, data[:n], nil
		case "write":
			if !h.write {
				return r, nil, errors.New("not writable")
			}
			n, e := h.file.Write(body)
			if e == nil && n != len(body) {
				e = io.ErrShortWrite
			}
			return r, nil, e
		case "list-next":
			entries, e := h.file.ReadDir(256)
			if e != nil && e != io.EOF {
				return r, nil, e
			}
			r.End = e == io.EOF
			for _, entry := range entries {
				a, e := entry.Info()
				if e != nil {
					return response{}, nil, e
				}
				r.Items = append(r.Items, info(a))
			}
			return r, nil, nil
		case "close":
			delete(b.handles, q.Handle)
			if h.write {
				if e := h.file.Sync(); e != nil {
					h.file.Close()
					return r, nil, e
				}
			}
			return r, nil, h.file.Close()
		}
	}
	if err := b.valid(q.Path); err != nil {
		return r, nil, err
	}
	p := path(q.Path)
	switch q.Op {
	case "stat":
		a, e := b.root.Lstat(p)
		if e != nil {
			return r, nil, e
		}
		v := info(a)
		return response{Item: &v}, nil, nil
	case "list-open":
		f, e := b.root.Open(p)
		if e != nil {
			return r, nil, e
		}
		return b.add(f, false)
	case "read-open", "write-open":
		if q.Offset < 0 {
			return r, nil, errors.New("negative offset")
		}
		flags := os.O_RDONLY | safeOpenFlags
		if q.Op == "write-open" {
			flags = os.O_WRONLY | os.O_CREATE | safeOpenFlags
			if q.Offset == 0 {
				flags |= os.O_TRUNC
			}
		}
		f, e := b.root.OpenFile(p, flags, 0600)
		if e != nil {
			return r, nil, e
		}
		a, e := f.Stat()
		if e != nil || !a.Mode().IsRegular() {
			f.Close()
			return r, nil, errors.New("regular file required")
		}
		if q.Offset > a.Size() {
			f.Close()
			return r, nil, errors.New("offset exceeds file")
		}
		if _, e = f.Seek(q.Offset, io.SeekStart); e != nil {
			f.Close()
			return r, nil, e
		}
		return b.add(f, q.Op == "write-open")
	case "mkdirs":
		return r, nil, b.root.MkdirAll(p, 0700)
	case "move":
		if q.Path == "" || q.To == "" {
			return r, nil, errors.New("root operation rejected")
		}
		if e := b.valid(q.To); e != nil {
			return r, nil, e
		}
		if _, e := b.root.Lstat(q.To); e == nil {
			return r, nil, os.ErrExist
		} else if !errors.Is(e, os.ErrNotExist) {
			return r, nil, e
		}
		e := b.root.Rename(p, q.To)
		if e == nil {
			b.syncParent(q.To)
		}
		return r, nil, e
	case "delete":
		if q.Path == "" || q.Path == "." {
			return r, nil, errors.New("root operation rejected")
		}
		a, e := b.root.Lstat(p)
		if e != nil {
			return r, nil, e
		}
		if a.IsDir() != q.Directory {
			return r, nil, errors.New("type mismatch")
		}
		e = b.root.Remove(p)
		if e == nil {
			b.syncParent(p)
		}
		return r, nil, e
	case "modified":
		t := time.UnixMilli(q.Modified)
		return r, nil, b.root.Chtimes(p, t, t)
	default:
		return r, nil, fmt.Errorf("unknown operation")
	}
}
func (b *bridge) syncParent(p string) {
	f, e := b.root.Open(filepath.Dir(p))
	if e == nil {
		_ = f.Sync()
		f.Close()
	}
}
