package main

import (
	"context"
	"net"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// One long poll owns command claims. Workers own their protocol connections and receipts.
// A cancelled scheduler waits for every writer before the Agent closes its durable queue.
func dispatchStorage(ctx context.Context, capacity int,
	poll func(context.Context, []string) (StorageWork, error),
	execute func(context.Context, StorageWork) error, failed func(error)) {
	ctx, cancel := context.WithCancel(ctx)
	var workers sync.WaitGroup
	defer func() { cancel(); workers.Wait() }()
	capacity = max(1, min(capacity, 16))
	type polled struct {
		work StorageWork
		err  error
	}
	type finished struct {
		id  string
		err error
	}
	polls := make(chan polled, 1)
	results := make(chan finished, capacity)
	active := map[string]StorageWork{}
	limits := map[string]*transferLimit{}
	var pending *StorageWork
	polling := false
	parallelPolling := false
	var retry <-chan time.Time
	var timer *time.Timer
	defer func() {
		if timer != nil {
			timer.Stop()
		}
	}()
	delay := func(d time.Duration) {
		if timer != nil {
			timer.Stop()
		}
		timer = time.NewTimer(d)
		retry = timer.C
	}
	for ctx.Err() == nil {
		if pending != nil && storageCanStart(*pending, active) {
			work := *pending
			pending = nil
			active[work.ID] = work
			workCtx := ctx
			if work.BandwidthMiB > 0 && work.LimitScope != "" {
				limiter := limits[work.LimitScope]
				if limiter == nil {
					limiter = &transferLimit{rate: work.BandwidthMiB * 1024 * 1024}
					limits[work.LimitScope] = limiter
				}
				workCtx = context.WithValue(ctx, transferLimitKey{}, limiter)
			}
			workers.Add(1)
			go func() {
				defer workers.Done()
				err := execute(workCtx, work)
				select {
				case results <- finished{work.ID, err}:
				case <-ctx.Done():
				}
			}()
		}
		if pending == nil && !polling && retry == nil && len(active) < capacity && (parallelPolling || len(active) == 0) {
			ids := make([]string, 0, len(active))
			for id := range active {
				ids = append(ids, id)
			}
			sort.Strings(ids)
			polling = true
			workers.Add(1)
			go func() {
				defer workers.Done()
				work, err := poll(ctx, ids)
				select {
				case polls <- polled{work, err}:
				case <-ctx.Done():
				}
			}()
		}
		select {
		case <-ctx.Done():
			return
		case <-retry:
			retry = nil
		case result := <-results:
			delete(active, result.id)
			if len(limits) > 32 {
				live := map[string]bool{}
				for _, w := range active {
					live[w.LimitScope] = true
				}
				for scope := range limits {
					if !live[scope] {
						delete(limits, scope)
					}
				}
			}
			if result.err != nil && ctx.Err() == nil {
				failed(result.err)
			}
		case result := <-polls:
			polling = false
			parallelPolling = parallelPolling || result.work.ParallelPolling
			if result.err != nil {
				if ctx.Err() == nil {
					failed(result.err)
				}
				delay(5 * time.Second)
			} else if result.work.ID == "" {
				delay(100 * time.Millisecond)
			} else if _, running := active[result.work.ID]; running {
				// A replay or a downgraded center must never execute a command twice.
				delay(time.Second)
			} else {
				pending = &result.work
			}
		}
	}
}

func storageCanStart(work StorageWork, active map[string]StorageWork) bool {
	resources := storageResources(work)
	for _, other := range active {
		// Until the protocol carries shared limiter identities, a limited command
		// runs exclusively so increasing workers cannot multiply its effective cap.
		if (work.BandwidthMiB > 0 && work.LimitScope == "") || (other.BandwidthMiB > 0 && other.LimitScope == "") {
			return false
		}
		for _, left := range resources {
			for _, right := range storageResources(other) {
				if left == right {
					return false
				}
			}
		}
	}
	return true
}

func storageResources(work StorageWork) []string {
	key := func(e TargetEndpoint, name string) string {
		root := strings.ReplaceAll(e.Root, "\\", "/")
		location := path.Clean(root + "/" + name)
		if e.Protocol == "LOCAL" {
			if absolute, err := filepath.Abs(filepath.Join(e.Root, filepath.FromSlash(name))); err == nil {
				location = filepath.Clean(absolute)
			}
			return "local|" + strings.ToLower(location)
		}
		port := e.Port
		if port == 0 {
			port = map[string]int{"SMB": 445, "SFTP": 22, "FTP": 21}[e.Protocol]
		}
		// Case folding is deliberately conservative for Windows and SMB paths.
		return strings.ToLower(e.Protocol + "|" + net.JoinHostPort(e.Host, strconv.Itoa(port)) + "|" + location)
	}
	resources := []string{key(work.Source.Endpoint, work.From)}
	if work.Target != nil {
		resources = append(resources, key(work.Target.Endpoint, work.To))
	}
	return resources
}
