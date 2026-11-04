package main

import (
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func fixture(t *testing.T) (*bridge, string) {
	t.Helper()
	dir := t.TempDir()
	r, e := os.OpenRoot(dir)
	if e != nil {
		t.Fatal(e)
	}
	b := &bridge{root: r, handles: map[int]handle{}}
	t.Cleanup(func() {
		for _, h := range b.handles {
			h.file.Close()
		}
		r.Close()
	})
	return b, dir
}
func TestOffsetsSyncAndFraming(t *testing.T) {
	b, dir := fixture(t)
	if _, _, e := b.execute(request{Op: "mkdirs", Path: "sub"}, nil); e != nil {
		t.Fatal(e)
	}
	r, _, e := b.execute(request{Op: "write-open", Path: "sub/中文", Offset: 0}, nil)
	if e != nil {
		t.Fatal(e)
	}
	if _, _, e = b.execute(request{Op: "write", Handle: r.Handle}, []byte("abc")); e != nil {
		t.Fatal(e)
	}
	if _, _, e = b.execute(request{Op: "close", Handle: r.Handle}, nil); e != nil {
		t.Fatal(e)
	}
	r, _, e = b.execute(request{Op: "read-open", Path: "sub/中文", Offset: 1}, nil)
	if e != nil {
		t.Fatal(e)
	}
	_, data, e := b.execute(request{Op: "read", Handle: r.Handle, Count: 10}, nil)
	if e != nil || string(data) != "bc" {
		t.Fatal(string(data), e)
	}
	if _, _, e = b.execute(request{Op: "write-open", Path: "sub/中文", Offset: 9}, nil); e == nil {
		t.Fatal("accepted invalid offset")
	}
	if _, _, e = b.execute(request{Op: "delete", Path: "sub/中文", Directory: true}, nil); e == nil {
		t.Fatal("accepted wrong type")
	}
	if raw, _ := os.ReadFile(filepath.Join(dir, "sub/中文")); string(raw) != "abc" {
		t.Fatal("changed file")
	}
	var output bytes.Buffer
	if e = b.serve(bytes.NewBufferString("{\"op\":\"ping\"}\n"), &output); e != nil {
		t.Fatal(e)
	}
	var reply response
	if json.Unmarshal(output.Bytes(), &reply) != nil || !reply.OK {
		t.Fatal(output.String())
	}
}
func TestMalformedFrameFailsClosed(t *testing.T) {
	for _, input := range []string{"{\"op\":\"write\",\"count\":1048577}\n", "{\"op\":\"write\",\"count\":3}\na"} {
		b, _ := fixture(t)
		if b.serve(bytes.NewBufferString(input), io.Discard) == nil {
			t.Fatal("accepted malformed frame")
		}
	}
}
func TestConcurrentParentReplacementCannotEscape(t *testing.T) {
	b, root := fixture(t)
	outside := t.TempDir()
	pivot := filepath.Join(root, "pivot")
	hold := filepath.Join(root, "hold")
	if e := os.Mkdir(pivot, 0700); e != nil {
		t.Fatal(e)
	}
	marker := []byte("OUTSIDE_PRIVATE_FIXTURE")
	for _, name := range []string{"read", "delete", "move"} {
		if e := os.WriteFile(filepath.Join(outside, name), marker, 0600); e != nil {
			t.Fatal(e)
		}
	}
	probe := filepath.Join(root, "probe")
	if e := os.Symlink(outside, probe); e != nil {
		t.Skip("symlink privilege unavailable")
	}
	os.Remove(probe)
	done := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-done:
				return
			default:
			}
			if os.Rename(pivot, hold) == nil {
				_ = os.Symlink(outside, pivot)
				_ = os.Remove(pivot)
				_ = os.Rename(hold, pivot)
			}
		}
	}()
	defer func() { close(done); wg.Wait() }()
	for i := 0; i < 1200; i++ {
		r, _, e := b.execute(request{Op: "read-open", Path: "pivot/read"}, nil)
		if e == nil {
			_, data, _ := b.execute(request{Op: "read", Handle: r.Handle, Count: 100}, nil)
			b.execute(request{Op: "close", Handle: r.Handle}, nil)
			if bytes.Contains(data, marker) {
				t.Fatal("escaped read")
			}
		}
		r, _, e = b.execute(request{Op: "write-open", Path: "pivot/new"}, nil)
		if e == nil {
			b.execute(request{Op: "write", Handle: r.Handle}, []byte("inside"))
			b.execute(request{Op: "close", Handle: r.Handle}, nil)
		}
		b.execute(request{Op: "delete", Path: "pivot/delete"}, nil)
		b.execute(request{Op: "move", Path: "pivot/move", To: "moved"}, nil)
		b.execute(request{Op: "mkdirs", Path: "pivot/created"}, nil)
		b.execute(request{Op: "modified", Path: "pivot/read", Modified: 1}, nil)
	}
	for _, name := range []string{"read", "delete", "move"} {
		raw, e := os.ReadFile(filepath.Join(outside, name))
		if e != nil || !bytes.Equal(raw, marker) {
			t.Fatal("outside changed", name, e)
		}
	}
	if _, e := os.Stat(filepath.Join(outside, "new")); !os.IsNotExist(e) {
		t.Fatal("escaped write")
	}
	if _, e := os.Stat(filepath.Join(outside, "created")); !os.IsNotExist(e) {
		t.Fatal("escaped mkdir")
	}
	if a, e := os.Stat(filepath.Join(outside, "read")); e != nil || a.ModTime().UnixMilli() == 1 {
		t.Fatal("escaped mtime")
	}
}

func TestDirectoryMetadataUsesLogicalBytes(t *testing.T) {
	b, _ := fixture(t)
	if _, _, e := b.execute(request{Op: "mkdirs", Path: "folder/nested"}, nil); e != nil {
		t.Fatal(e)
	}
	r, _, e := b.execute(request{Op: "stat", Path: "folder"}, nil)
	if e != nil || !r.Item.Directory || r.Item.Size != 0 {
		t.Fatal(r, e)
	}
}
