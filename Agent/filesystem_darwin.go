package main

import (
	"fmt"
	"golang.org/x/sys/unix"
)

func validateLocalDirectory(path string) error {
	var stat unix.Statfs_t
	if err := unix.Statfs(path, &stat); err != nil {
		return err
	}
	if stat.Flags&unix.MNT_LOCAL == 0 {
		return fmt.Errorf("FSEvents requires a local source volume: %s; install the agent on the storage host", path)
	}
	return nil
}
