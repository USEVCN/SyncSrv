package main

import (
	"errors"
	"fmt"
	"io/fs"
	"path/filepath"
)

type sourceReadError struct {
	path  string
	cause error
	busy  bool
}

func (e *sourceReadError) Error() string {
	code := "SOURCE_READ_FAILED"
	if e.busy {
		code = "SOURCE_BUSY"
	}
	return fmt.Sprintf("%s: %s: %v", code, e.path, e.cause)
}
func (e *sourceReadError) Unwrap() error { return e.cause }
func sourceFailure(path string, err error) error {
	if err == nil {
		return nil
	}
	var existing *sourceReadError
	if errors.As(err, &existing) {
		return err
	}
	var fileError *fs.PathError
	if path == "" && errors.As(err, &fileError) {
		path = fileError.Path
	}
	return &sourceReadError{path: filepath.ToSlash(path), cause: err, busy: sourceFileBusy(err)}
}
