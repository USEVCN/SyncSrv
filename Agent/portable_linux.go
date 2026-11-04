//go:build linux

package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"golang.org/x/sys/unix"
)

type portableProcess struct {
	PID                       int
	Started, Executable, Data string
}

func processStart(pid int) (string, error) {
	raw, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return "", err
	}
	tail := strings.LastIndex(string(raw), ") ")
	if tail < 0 {
		return "", fmt.Errorf("invalid process state")
	}
	fields := strings.Fields(string(raw)[tail+2:])
	if len(fields) < 20 {
		return "", fmt.Errorf("invalid process state")
	}
	if fields[0] == "Z" {
		return "", os.ErrNotExist
	}
	return fields[19], nil
}
func checkPortable(p portableProcess) error {
	if p.PID < 2 {
		return fmt.Errorf("invalid saved PID")
	}
	started, err := processStart(p.PID)
	if err != nil {
		return err
	}
	if started != p.Started {
		return fmt.Errorf("PID was reused; refusing to signal it")
	}
	exe, err := os.Readlink(fmt.Sprintf("/proc/%d/exe", p.PID))
	if err != nil {
		return err
	}
	if exe != p.Executable {
		return fmt.Errorf("process executable mismatch")
	}
	cmd, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", p.PID))
	if err != nil {
		return err
	}
	if !bytes.Equal(cmd, []byte(p.Executable+"\x00run\x00--data-dir\x00"+p.Data+"\x00")) {
		return fmt.Errorf("process arguments mismatch")
	}
	return nil
}
func portableControl(args []string) error {
	flags := flag.NewFlagSet("portable-control", flag.ContinueOnError)
	action := flags.String("action", "status", "start, stop or status")
	packageService := flags.Bool("package-service", false, "run under the DSM package account")
	install := flags.String("install-dir", "", "protected installation directory")
	data := flags.String("data-dir", "", "persistent data directory")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if os.Geteuid() != 0 && !*packageService {
		return fmt.Errorf("portable NAS service management requires root")
	}
	if !filepath.IsAbs(*install) || !filepath.IsAbs(*data) {
		return fmt.Errorf("absolute directories required")
	}
	exe, err := filepath.EvalSymlinks(filepath.Join(*install, "syncsrv-agent"))
	if err != nil {
		return err
	}
	stateDir := *install
	if *packageService {
		stateDir = *data
	}
	lock, err := os.OpenFile(filepath.Join(stateDir, ".portable-control.lock"), os.O_CREATE|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	defer lock.Close()
	if err = unix.Flock(int(lock.Fd()), unix.LOCK_EX|unix.LOCK_NB); err != nil {
		return fmt.Errorf("portable management already active: %w", err)
	}
	defer unix.Flock(int(lock.Fd()), unix.LOCK_UN)
	statePath := filepath.Join(stateDir, ".portable-process.json")
	var saved portableProcess
	raw, readErr := os.ReadFile(statePath)
	if readErr == nil {
		if err := json.Unmarshal(raw, &saved); err != nil {
			return err
		}
	} else if !os.IsNotExist(readErr) {
		return readErr
	}
	running := false
	if readErr == nil {
		err = checkPortable(saved)
		if err == nil {
			running = true
		} else if !os.IsNotExist(err) {
			return err
		}
	}
	switch *action {
	case "status":
		if !running {
			return fmt.Errorf("service is stopped")
		}
		return nil
	case "stop":
		if !running {
			err := os.Remove(statePath)
			if os.IsNotExist(err) {
				return nil
			}
			return err
		}
		if saved.Executable != exe || saved.Data != *data {
			return fmt.Errorf("saved service belongs to another installation")
		}
		if err = syscall.Kill(saved.PID, syscall.SIGTERM); err != nil {
			return err
		}
		until := time.Now().Add(90 * time.Second)
		for time.Now().Before(until) {
			start, e := processStart(saved.PID)
			if os.IsNotExist(e) || e == nil && start != saved.Started {
				return os.Remove(statePath)
			}
			if e != nil {
				return e
			}
			time.Sleep(200 * time.Millisecond)
		}
		return fmt.Errorf("graceful stop timed out; process was not forcibly killed")
	case "start":
		if running {
			return nil
		}
		log, err := os.OpenFile(filepath.Join(*data, "bootstrap.log"), os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0600)
		if err != nil {
			return err
		}
		defer log.Close()
		cmd := exec.Command(exe, "run", "--data-dir", *data)
		cmd.Stdout, cmd.Stderr = log, log
		cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
		if err = cmd.Start(); err != nil {
			return err
		}
		started, err := processStart(cmd.Process.Pid)
		if err != nil {
			cmd.Process.Signal(syscall.SIGTERM)
			cmd.Wait()
			return err
		}
		saved = portableProcess{cmd.Process.Pid, started, exe, *data}
		raw, err := json.Marshal(saved)
		if err == nil {
			err = writeTool(statePath, raw, 0600)
		}
		if err != nil {
			cmd.Process.Signal(syscall.SIGTERM)
			cmd.Wait()
			return err
		}
		cmd.Process.Release()
		time.Sleep(time.Second)
		if err = checkPortable(saved); err != nil {
			return fmt.Errorf("service startup failed (PID %s): %w", strconv.Itoa(saved.PID), err)
		}
		return nil
	default:
		return fmt.Errorf("unsupported portable action")
	}
}
