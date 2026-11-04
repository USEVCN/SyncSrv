//go:build !windows

package main

import (
	"errors"
	"syscall"
)

func sourceFileBusy(err error) bool {
	return errors.Is(err, syscall.EBUSY) || errors.Is(err, syscall.EAGAIN)
}
