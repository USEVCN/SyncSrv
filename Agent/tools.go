package main

import (
	"embed"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

//go:embed tools/*
var managementTools embed.FS

func installTools(args []string) error {
	flags := flag.NewFlagSet("install-tools", flag.ContinueOnError)
	install := flags.String("install-dir", "", "existing program directory")
	data := flags.String("data-dir", "", "existing device state directory")
	mode := flags.String("mode", "", "windows, systemd, launchd or portable")
	user := flags.String("service-user", "root", "existing service account")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if !filepath.IsAbs(*install) || !filepath.IsAbs(*data) || strings.ContainsAny(*install+*data+*user, "\r\n") {
		return fmt.Errorf("absolute single-line installation and data paths required")
	}
	if *mode != "windows" && *mode != "systemd" && *mode != "launchd" && *mode != "portable" {
		return fmt.Errorf("unsupported service mode")
	}
	entries, err := managementTools.ReadDir("tools")
	if err != nil {
		return err
	}
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".command") && runtime.GOOS != "darwin" {
			continue
		}
		windows := strings.HasSuffix(entry.Name(), ".bat") || strings.HasSuffix(entry.Name(), ".ps1")
		if windows != (runtime.GOOS == "windows") {
			continue
		}
		raw, err := managementTools.ReadFile("tools/" + entry.Name())
		if err != nil {
			return err
		}
		if err = writeTool(filepath.Join(*install, entry.Name()), raw, 0755); err != nil {
			return err
		}
	}
	if err = writeTool(filepath.Join(*install, "agent-control.conf"), []byte(*install+"\n"+*data+"\n"+*mode+"\n"+*user+"\n"), 0600); err != nil {
		return err
	}
	return migrateInstalledService(*install, *data, *mode)
}
func writeTool(path string, raw []byte, mode os.FileMode) error {
	if info, err := os.Lstat(path); err == nil && !info.Mode().IsRegular() {
		return fmt.Errorf("refusing non-regular control file: %s", path)
	} else if err != nil && !os.IsNotExist(err) {
		return err
	}
	if runtime.GOOS != "windows" {
		// A running shell may still be reading the old controller. Replace its
		// path atomically so that it can finish on the unchanged open file.
		return replaceTool(path, raw, mode)
	}
	// Keep the existing Windows DACL by writing into the existing regular file.
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	_, err = file.Write(raw)
	if err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return err
	}
	return closeErr
}

func replaceTool(path string, raw []byte, mode os.FileMode) error {
	file, err := os.CreateTemp(filepath.Dir(path), ".agent-tool-*")
	if err != nil {
		return err
	}
	defer os.Remove(file.Name())
	defer file.Close()
	if err = file.Chmod(mode); err != nil {
		return err
	}
	if _, err = file.Write(raw); err != nil {
		return err
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	return os.Rename(file.Name(), path)
}
