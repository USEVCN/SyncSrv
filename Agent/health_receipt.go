package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

func writeHealthReceipt(dir string) error {
	raw, err := json.Marshal(map[string]any{"version": version, "centerAcknowledgedAt": time.Now().UTC().Format(time.RFC3339Nano)})
	if err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".health-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, err = tmp.Write(raw); err == nil {
		err = tmp.Sync()
	}
	closeErr := tmp.Close()
	if err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	// Windows Rename cannot replace an existing destination; receipts can be transiently absent.
	path := filepath.Join(dir, "health.json")
	_ = os.Remove(path)
	return os.Rename(name, path)
}
