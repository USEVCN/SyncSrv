//go:build !windows

package main

import "syscall"

const safeOpenFlags = syscall.O_NONBLOCK | syscall.O_NOFOLLOW
