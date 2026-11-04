package main

import (
	"context"
	"errors"
	"golang.org/x/sys/windows"
	"os"
	"path/filepath"
	"testing"
)

func TestExclusiveWindowsFileIsRetainedUntilReleased(t *testing.T) {
	dir := t.TempDir()
	name := filepath.Join(dir, "locked.iso")
	if err := os.WriteFile(name, []byte("original"), 0600); err != nil {
		t.Fatal(err)
	}
	ptr, _ := windows.UTF16PtrFromString(name)
	handle, err := windows.CreateFile(ptr, windows.GENERIC_READ, 0, nil, windows.OPEN_EXISTING, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if handle != windows.InvalidHandle {
			windows.CloseHandle(handle)
		}
	}()
	_, _, err = collectSource(context.Background(), dir, []Event{{Path: "locked.iso"}}, false)
	var failure *sourceReadError
	if !errors.As(sourceFailure("locked.iso", err), &failure) || !failure.busy {
		t.Fatalf("not classified as occupied: %v", err)
	}
	windows.CloseHandle(handle)
	handle = windows.InvalidHandle
	_, nodes, err := collectSource(context.Background(), dir, []Event{{Path: "locked.iso"}}, false)
	if err != nil || len(nodes) != 1 || nodes[0].Size != 8 {
		t.Fatalf("failed retry after unlock: %v %+v", err, nodes)
	}
	if sourceFileBusy(windows.ERROR_ACCESS_DENIED) {
		t.Fatal("permission error mislabeled as lock")
	}
}
