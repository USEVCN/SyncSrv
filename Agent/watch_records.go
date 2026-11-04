package main

import (
	"encoding/binary"
	"fmt"
	"strings"
	"unicode/utf16"
)

type directoryChange struct {
	action uint32
	path   string
}

// FILE_NOTIFY_INFORMATION uses byte lengths and DWORD-aligned record offsets.
// Decode a whole completion before publishing it: truncated/unknown data must
// invalidate coverage rather than silently dropping a deletion or rename.
func decodeDirectoryChanges(data []byte) ([]directoryChange, error) {
	var changes []directoryChange
	for {
		if len(data) < 12 {
			return nil, fmt.Errorf("truncated directory notification")
		}
		next := binary.LittleEndian.Uint32(data)
		action := binary.LittleEndian.Uint32(data[4:])
		size := binary.LittleEndian.Uint32(data[8:])
		if action < 1 || action > 5 || size == 0 || size%2 != 0 || uint64(size)+12 > uint64(len(data)) {
			return nil, fmt.Errorf("invalid directory notification")
		}
		name := make([]uint16, int(size)/2)
		for i := range name {
			name[i] = binary.LittleEndian.Uint16(data[12+2*i:])
		}
		path := strings.ReplaceAll(string(utf16.Decode(name)), "\\", "/")
		if path == "" || strings.HasPrefix(path, "/") || strings.ContainsAny(path, ":\x00\r\n") {
			return nil, fmt.Errorf("invalid notification path: %q", path)
		}
		for _, part := range strings.Split(path, "/") {
			if part == "" || part == "." || part == ".." {
				return nil, fmt.Errorf("invalid notification path: %q", path)
			}
		}
		changes = append(changes, directoryChange{action: action, path: path})
		if next == 0 {
			return changes, nil
		}
		if next%4 != 0 || uint64(next) < uint64(size)+12 || uint64(next)+12 > uint64(len(data)) {
			return nil, fmt.Errorf("invalid directory notification offset")
		}
		data = data[next:]
	}
}
