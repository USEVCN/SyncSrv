package main

import (
	"fmt"
	"os"
	"path/filepath"
)

func prepareDataDirectory(path string) error {
	clean := filepath.Clean(path)
	if !filepath.IsAbs(clean) || filepath.Dir(clean) == clean {
		return fmt.Errorf("choose a dedicated agent data directory, not a filesystem root")
	}
	info, err := os.Lstat(clean)
	if err == nil {
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("data directory must be a real local directory")
		}
		entries, e := os.ReadDir(clean)
		if e != nil {
			return e
		}
		if len(entries) > 0 {
			if _, e = os.Stat(filepath.Join(clean, "identity.json")); e != nil {
				return fmt.Errorf("existing nonempty directory is not an agent data directory")
			}
		}
	} else if !os.IsNotExist(err) {
		return err
	}
	if err = os.MkdirAll(clean, 0700); err != nil {
		return err
	}
	if err = validateLocalDirectory(clean); err != nil {
		return err
	}
	return protectDirectory(clean)
}
