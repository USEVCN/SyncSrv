//go:build !windows

package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"
)

func runService(dir string, c *Client) error {
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	return run(ctx, dir, c)
}
