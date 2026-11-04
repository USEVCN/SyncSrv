package main

import (
	"errors"
	"io"
)

// SFTP's normal ReaderFrom falls back to sequential requests for paced readers,
// which intentionally do not expose a source size. Use its bounded native pipeline.
func copyRemote(target seekWriteCloser, input io.Reader) (int64, error) {
	if parallel, ok := target.(interface {
		ReadFromWithConcurrency(io.Reader, int) (int64, error)
	}); ok {
		n, err := parallel.ReadFromWithConcurrency(input, 4)
		if err != nil {
			// The SFTP library drains requests and sets its offset to the earliest
			// failure. Remove any later writes so they cannot leave resume holes.
			if truncate, ok := target.(interface{ Truncate(int64) error }); ok {
				offset, seekErr := target.Seek(0, io.SeekCurrent)
				if seekErr == nil {
					err = errors.Join(err, truncate.Truncate(offset))
				} else {
					err = errors.Join(err, seekErr)
				}
			}
		}
		return n, err
	}
	return io.CopyBuffer(target, input, make([]byte, 256*1024))
}
