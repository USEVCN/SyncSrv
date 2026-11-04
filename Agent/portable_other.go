//go:build !linux

package main

import "fmt"

func portableControl(_ []string) error {
	return fmt.Errorf("portable service mode is only supported on Linux/NAS")
}
