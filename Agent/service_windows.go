package main

import (
	"context"
	"golang.org/x/sys/windows/svc"
	"log"
	"os"
	"os/signal"
)

type windowsService struct {
	dir    string
	client *Client
}

func (w windowsService) Execute(_ []string, requests <-chan svc.ChangeRequest, status chan<- svc.Status) (bool, uint32) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan error, 1)
	status <- svc.Status{State: svc.StartPending}
	go func() { done <- run(ctx, w.dir, w.client) }()
	status <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for {
		select {
		case err := <-done:
			if err != nil {
				log.Print(err)
				return false, 1
			}
			return false, 0
		case r := <-requests:
			switch r.Cmd {
			case svc.Interrogate:
				status <- r.CurrentStatus
			case svc.Stop, svc.Shutdown:
				status <- svc.Status{State: svc.StopPending}
				cancel()
				if err := <-done; err != nil {
					return false, 1
				}
				return false, 0
			}
		}
	}
}
func runService(dir string, c *Client) error {
	service, e := svc.IsWindowsService()
	if e != nil {
		return e
	}
	if service {
		return svc.Run("SyncSrvAgent", windowsService{dir, c})
	}
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt)
	defer cancel()
	return run(ctx, dir, c)
}
