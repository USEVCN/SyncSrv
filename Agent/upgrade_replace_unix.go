//go:build !windows

package main

import "os"

func replaceUpgrade(from, to string) error { return os.Rename(from, to) }
