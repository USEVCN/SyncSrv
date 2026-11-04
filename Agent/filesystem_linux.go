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
	switch uint64(stat.Type) {
	case unix.NFS_SUPER_MAGIC, unix.SMB_SUPER_MAGIC, unix.SMB2_SUPER_MAGIC,
		0xff534d42, unix.CEPH_SUPER_MAGIC, unix.FUSE_SUPER_MAGIC:
		return fmt.Errorf("local native monitoring is not certified for this network/FUSE mount: %s; install the agent on the storage host", path)
	}
	return nil
}
