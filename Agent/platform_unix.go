//go:build !windows

package main

import (
	"os"
	"runtime"
	"syscall"
)

func syncDirectory(path string) error {
	f, e := os.Open(path)
	if e != nil {
		return e
	}
	defer f.Close()
	return f.Sync()
}
func processUsage() (float64, int64) {
	var r syscall.Rusage
	if syscall.Getrusage(syscall.RUSAGE_SELF, &r) != nil {
		return 0, 0
	}
	rss := r.Maxrss
	if runtime.GOOS != "darwin" {
		rss *= 1024
	}
	return float64(r.Utime.Sec+r.Stime.Sec) + float64(r.Utime.Usec+r.Stime.Usec)/1e6, rss
}

func protectDirectory(path string) error { return os.Chmod(path, 0700) }
