package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

func upgradeHealth(args []string) error {
	f := flag.NewFlagSet("health-check", flag.ContinueOnError)
	dir := f.String("data-dir", "", "device state directory")
	expected := f.String("expected-version", version, "required running version")
	seconds := f.Int("timeout", 120, "maximum seconds")
	if err := f.Parse(args); err != nil {
		return err
	}
	if !filepath.IsAbs(*dir) || *seconds < 1 || *seconds > 300 {
		return fmt.Errorf("invalid health check")
	}
	deadline := time.Now().Add(time.Duration(*seconds) * time.Second)
	for time.Now().Before(deadline) {
		raw, err := os.ReadFile(filepath.Join(*dir, "health.json"))
		var receipt struct {
			Version string `json:"version"`
			Time    string `json:"centerAcknowledgedAt"`
		}
		if err == nil && json.Unmarshal(raw, &receipt) == nil {
			stamp, e := time.Parse(time.RFC3339Nano, receipt.Time)
			if e == nil && receipt.Version == *expected && time.Since(stamp) >= 0 && time.Since(stamp) < 90*time.Second {
				return nil
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
	return fmt.Errorf("new Agent did not receive a fresh center acknowledgement; upgrade rolled back")
}
