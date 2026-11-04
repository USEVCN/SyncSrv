package main

import (
	"errors"
	"golang.org/x/sys/windows"
)

func sourceFileBusy(err error) bool {
	return errors.Is(err, windows.ERROR_SHARING_VIOLATION) || errors.Is(err, windows.ERROR_LOCK_VIOLATION)
}
