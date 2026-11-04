package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	bolt "go.etcd.io/bbolt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type networkResult struct {
	kind   string
	config Configuration
	sent   Batch
	ack    []string
	err    error
}

func run(parent context.Context, dir string, client *Client) error {
	return runWithWatcher(parent, dir, client, watch)
}

func runWithWatcher(parent context.Context, dir string, client *Client, startWatcher sourceWatchStarter) error {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	q, err := openQueue(dir)
	if err != nil {
		return err
	}
	defer q.db.Close()
	changed := make(chan Event, 4096)
	failures := make(chan struct{}, 1)
	var failureMu sync.Mutex
	pendingFailures := map[string]error{}
	fail := func(e error) {
		// Keep each watch generation even if several watchers fail together. A
		// dropped task error would leave a dead watcher reported as healthy.
		key := ""
		var scoped *watchFailure
		if errors.As(e, &scoped) {
			key = fmt.Sprintf("%s:%d", scoped.jobID, scoped.generation)
		}
		failureMu.Lock()
		pendingFailures[key] = e
		failureMu.Unlock()
		select {
		case failures <- struct{}{}:
		default:
		}
	}
	emit := func(e Event) {
		if strings.ContainsAny(e.Path, "\\\x00\r\n") || len(e.Path) > 4096 ||
			strings.EqualFold(e.Path, ".syncsrv") || strings.HasPrefix(strings.ToLower(e.Path), ".syncsrv/") {
			// Let the scoped engine report unsupported source entries without poisoning notification batches.
			e.Path = ""
			e.Kind = "RESCAN"
		}
		select {
		case changed <- e:
		default:
			fail(fmt.Errorf("event buffer overflow; reconciliation required"))
		}
	}
	watches := newSourceWatches(ctx, dir, emit, fail)
	watches.start = startWatcher
	defer watches.stopAll()
	var applied int64
	assigned := map[string]bool{}
	var config Configuration
	var healthError string
	var deliveryError string
	dirty := map[string]Event{}
	persist := func() error {
		if len(dirty) == 0 {
			return nil
		}
		events := make([]Event, 0, len(dirty))
		for _, e := range dirty {
			events = append(events, e)
		}
		if err := q.add(events); err != nil {
			return fmt.Errorf("event persistence failed; coverage invalid: %w", err)
		}
		clear(dirty)
		return nil
	}
	apply := func(next Configuration) error {
		// Persist authorization/configuration before adding baseline events, so a
		// newly assigned task also gets the independent durable early notice.
		raw, e := json.Marshal(next)
		if e != nil {
			return e
		}
		if e = q.db.Update(func(tx *bolt.Tx) error { return tx.Bucket(metaBucket).Put([]byte("configuration"), raw) }); e != nil {
			return e
		}
		clear(assigned)
		applied = next.Version
		if next.Status != "APPROVED" {
			watches.stopAll()
			healthError = "Waiting for center authorization"
			return nil
		}
		for _, r := range next.Roots {
			assigned[r.JobID] = true
		}
		started := watches.apply(next.Roots)
		for _, r := range started {
			e := Event{JobID: r.JobID, Kind: "RESTART"}
			dirty[string(eventKey(e))] = e
		}
		if e = persist(); e != nil {
			return e
		}
		retired, e := q.retainJobs(next.Roots)
		if e != nil {
			return e
		}
		if retired > 0 {
			log.Printf("configuration superseded %d pending notifications; retained locally", retired)
		}
		healthError = watches.health()
		return nil
	}
	// Last applied roots stay monitored during a center outage, including after a source reboot.
	err = q.db.View(func(tx *bolt.Tx) error {
		raw := tx.Bucket(metaBucket).Get([]byte("configuration"))
		if raw == nil {
			return nil
		}
		return json.Unmarshal(raw, &config)
	})
	if err != nil {
		return err
	}
	if config.Status != "" {
		if err = apply(config); err != nil {
			healthError = err.Error()
		}
	}
	controls := time.NewTicker(5 * time.Second)
	defer controls.Stop()
	batchTimer := time.NewTimer(time.Hour)
	batchTimer.Stop()
	defer batchTimer.Stop()
	retryTimer := time.NewTimer(time.Hour)
	retryTimer.Stop()
	defer retryTimer.Stop()
	var batchC, retryC <-chan time.Time
	scheduleBatch := func() {
		if batchC == nil {
			batchTimer.Reset(100 * time.Millisecond)
			batchC = batchTimer.C
		}
	}
	results := make(chan networkResult, 1)
	var workers sync.WaitGroup
	defer func() { cancel(); workers.Wait() }()
	workers.Add(1)
	go func() { defer workers.Done(); client.notifyChanges(ctx, q, fail) }()
	workers.Add(1)
	go func() { defer workers.Done(); client.storageWorker(ctx, q, dir) }()
	busy := false
	pushBusy := false
	var pushCancel context.CancelFunc
	var pushRoots []Root
	var pushBatch Batch
	pushConfigurationCancelled := false
	controlDue := true
	heartbeatDue := false
	canSend := true
	backoff := time.Second
	pending, err := q.count()
	if err != nil {
		return err
	}
	processCPU, _ := processUsage()
	lastUsage := time.Now()
	launch := func(kind string, batch Batch, heartbeat any) {
		if kind == "events" {
			pushBusy = true
		} else {
			busy = true
		}
		configuredRoots := watches.roots()
		requestContext := ctx
		if kind == "events" {
			requestContext, pushCancel = context.WithCancel(ctx)
			pushRoots = append([]Root(nil), config.Roots...)
			pushBatch = batch
			pushConfigurationCancelled = false
		}
		workers.Add(1)
		go func() {
			defer workers.Done()
			result := networkResult{kind: kind, sent: batch}
			switch kind {
			case "config":
				result.err = client.request(ctx, "GET", "/agent/v1/config", nil, &result.config, true)
			case "heartbeat":
				var accepted struct {
					Version        string `json:"version"`
					Status         string `json:"status"`
					ConfigVersion  int64  `json:"configVersion"`
					AppliedVersion int64  `json:"appliedVersion"`
				}
				result.err = client.request(ctx, "POST", "/agent/v1/heartbeat", heartbeat, &accepted, true)
				if result.err == nil && accepted.Version == version && accepted.Status == "APPROVED" && accepted.AppliedVersion == accepted.ConfigVersion {
					result.err = writeHealthReceipt(dir)
				}
			default:
				result.ack, result.err = client.deliver(requestContext, configuredRoots, batch)
			}
			select {
			case results <- result:
			case <-ctx.Done():
			}
		}()
	}
	for {
		if !busy {
			if controlDue {
				controlDue = false
				launch("config", Batch{}, nil)
			} else if heartbeatDue {
				heartbeatDue = false
				var dbBytes int64
				if stat, e := os.Stat(q.filename); e == nil {
					dbBytes = stat.Size()
				}
				now := time.Now()
				cpu, rss := processUsage()
				percent := max((cpu-processCPU)/now.Sub(lastUsage).Seconds()*100, 0)
				processCPU = cpu
				lastUsage = now
				jobs := []string{}
				for _, r := range watches.roots() {
					jobs = append(jobs, r.JobID)
				}
				reportedError := strings.TrimSpace(healthError + " " + deliveryError)
				if len(reportedError) > 900 {
					reportedError = reportedError[:900]
				}
				message := map[string]any{"appliedVersion": applied, "pendingEvents": pending, "databaseBytes": dbBytes, "memoryBytes": rss,
					"uploadedBytes": client.uploadBytes.Load(), "uploadedFiles": client.uploadFiles.Load(), "cpuPercent": percent, "capability": capability(), "error": reportedError, "watchingJobs": jobs, "version": version}
				launch("heartbeat", Batch{}, message)
			}
		}
		if !pushBusy && canSend && pending > 0 && config.Status == "APPROVED" {
			allowed := map[string]bool{}
			anyEnabled := false
			for _, root := range watches.roots() {
				if root.Enabled {
					anyEnabled = true
				}
				if root.Enabled {
					allowed[root.JobID] = true
				}
			}
			batch, e := q.batchFor(256, allowed)
			if e != nil {
				return e
			}
			var ready []Event
			for _, event := range batch.Events {
				for _, root := range config.Roots {
					if root.JobID == event.JobID && root.Enabled {
						ready = append(ready, event)
						break
					}
				}
			}
			batch.Events = ready
			if len(ready) > 0 {
				launch("events", batch, nil)
			} else {
				canSend = false
				delay := time.Second
				if !anyEnabled {
					delay = 60 * time.Second
				}
				retryTimer.Reset(delay)
				retryC = retryTimer.C
			}
		}
		select {
		case <-ctx.Done():
			return persist()
		case e := <-changed:
			if !assigned[e.JobID] {
				if err = q.retireEvent(e); err != nil {
					return err
				}
				continue
			}
			key := string(eventKey(e))
			if previous, ok := dirty[key]; ok {
				e.Coalesced += previous.Coalesced + 1
			}
			dirty[key] = e
			scheduleBatch()
		case <-batchC:
			batchC = nil
			if err = persist(); err != nil {
				return err
			}
			pending, err = q.count()
			if err != nil {
				return err
			}
		case <-retryC:
			retryC = nil
			canSend = true
		case <-controls.C:
			// Recover a replaced/mounted source even while the center is offline.
			if config.Status == "APPROVED" && watches.health() != "" {
				if err = apply(config); err != nil {
					return err
				}
				pending, err = q.count()
				if err != nil {
					return err
				}
				canSend = true
			}
			controlDue = true
		case <-failures:
			failureMu.Lock()
			currentFailures := pendingFailures
			pendingFailures = map[string]error{}
			failureMu.Unlock()
			for _, e := range currentFailures {
				for _, r := range watches.invalidate(e) {
					event := Event{JobID: r.JobID, Kind: "OVERFLOW"}
					dirty[string(eventKey(event))] = event
				}
				log.Print(e)
				healthError = watches.health()
				var scoped *watchFailure
				if healthError == "" && !errors.As(e, &scoped) {
					healthError = e.Error()
				}
			}
			if len(healthError) > 900 {
				healthError = healthError[:900]
			}
			scheduleBatch()
			heartbeatDue = true
		case result := <-results:
			if result.kind == "events" {
				pushBusy = false
				if pushCancel != nil {
					pushCancel()
					pushCancel = nil
				}
			} else {
				busy = false
			}
			if result.kind == "events" && len(result.ack) > 0 {
				if err = q.ack(result.sent, result.ack); err != nil {
					return err
				}
				if pending, err = q.count(); err != nil {
					return err
				}
			}
			if result.kind == "events" && pushConfigurationCancelled {
				// A requested pause/reconfiguration is not a failed backup. Keep
				// unacknowledged events for the next enabled assignment.
				canSend = true
				controlDue = true
				continue
			}
			if errors.Is(result.err, errDebouncing) {
				canSend = false
				retryTimer.Reset(time.Second)
				retryC = retryTimer.C
				continue
			}
			if result.err != nil {
				log.Print(result.err)
				if result.kind == "events" {
					deliveryError = "Source delivery: " + result.err.Error()
					heartbeatDue = true
					canSend = false
					retryTimer.Reset(backoff)
					retryC = retryTimer.C
					backoff = min(backoff*2, 60*time.Second)
				}
				continue
			}
			switch result.kind {
			case "config":
				if pushBusy && deliveryConfigurationChanged(pushRoots, result.config, pushBatch) {
					pushConfigurationCancelled = true
					pushCancel()
				}
				config = result.config
				for _, root := range config.Roots {
					if root.SourceMode == "AGENT" && root.Enabled && root.FullScanRequired {
						event := Event{JobID: root.JobID, Kind: "RESCAN"}
						dirty[string(eventKey(event))] = event
						scheduleBatch()
					}
				}
				if e := apply(config); e != nil {
					healthError = e.Error()
					if len(healthError) > 900 {
						healthError = healthError[:900]
					}
					log.Print(healthError)
				}
				pending, err = q.count()
				if err != nil {
					return err
				}
				heartbeatDue = true
			case "events":
				if len(result.ack) == 0 {
					canSend = false
					retryTimer.Reset(5 * time.Second)
					retryC = retryTimer.C
					continue
				}
				controlDue = true
				backoff = time.Second
				if deliveryError != "" {
					deliveryError = ""
					heartbeatDue = true
				}
			}
		}
	}
}
func pathsOverlap(a, b string) bool {
	rel, e := filepath.Rel(a, b)
	if e == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return true
	}
	rel, e = filepath.Rel(b, a)
	return e == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}
