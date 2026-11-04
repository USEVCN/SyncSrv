package main

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func watchManagerFixture(t *testing.T) (*sourceWatches, map[string]int, map[string]int, map[string]func(error)) {
	t.Helper()
	starts, stops := map[string]int{}, map[string]int{}
	failures := map[string]func(error){}
	manager := newSourceWatches(context.Background(), t.TempDir(), func(Event) {}, func(error) {})
	manager.start = func(_ context.Context, roots []Root, _ func(Event), fail func(error)) (func(), error) {
		id := roots[0].JobID
		starts[id]++
		failures[id] = fail
		return func() { stops[id]++ }, nil
	}
	t.Cleanup(manager.stopAll)
	return manager, starts, stops, failures
}
func TestUnavailableRootDoesNotDisableOtherJobsAndRecovers(t *testing.T) {
	manager, starts, stops, _ := watchManagerFixture(t)
	healthy := Root{JobID: "healthy", Path: t.TempDir(), Enabled: true}
	missing := Root{JobID: "missing", Path: filepath.Join(t.TempDir(), "not-created"), Enabled: true}
	if got := manager.apply([]Root{missing, healthy}); len(got) != 1 || got[0].JobID != "healthy" {
		t.Fatalf("healthy root did not start: %v", got)
	}
	if manager.health() == "" || len(manager.roots()) != 1 {
		t.Fatal("missing root incorrectly reports healthy coverage")
	}
	if err := os.Mkdir(missing.Path, 0700); err != nil {
		t.Fatal(err)
	}
	if got := manager.apply([]Root{missing, healthy}); len(got) != 1 || got[0].JobID != "missing" {
		t.Fatalf("missing root failed recovery: %v", got)
	}
	if manager.health() != "" || starts["healthy"] != 1 || stops["healthy"] != 0 {
		t.Fatal("recovery restarted the healthy job")
	}
}
func TestTaskSettingsAndNewAssignmentsDoNotRescanUnchangedRoots(t *testing.T) {
	manager, starts, stops, _ := watchManagerFixture(t)
	first := Root{JobID: "first", Path: t.TempDir(), Enabled: true}
	manager.apply([]Root{first})
	first.BandwidthMiB = 12
	first.TransferThreads = 8
	first.Enabled = false
	second := Root{JobID: "second", Path: t.TempDir(), Enabled: true}
	started := manager.apply([]Root{first, second})
	if len(started) != 1 || started[0].JobID != "second" || starts["first"] != 1 || stops["first"] != 0 {
		t.Fatalf("unrelated configuration restarted source scan: %+v", started)
	}
	if manager.active["first"].root.Enabled || manager.active["first"].root.TransferThreads != 8 {
		t.Fatal("delivery settings were not refreshed")
	}
	manager.apply([]Root{second})
	if stops["first"] != 1 || starts["second"] != 1 {
		t.Fatal("removing one task disturbed another")
	}
}
func TestWatcherFailureIsolatedAndStaleCallbacksCannotStopReplacement(t *testing.T) {
	manager, _, stops, callbacks := watchManagerFixture(t)
	a, b := Root{JobID: "a", Path: t.TempDir()}, Root{JobID: "b", Path: t.TempDir()}
	manager.apply([]Root{a, b})
	var failure error
	manager.fail = func(err error) { failure = err }
	oldCallback := callbacks["a"]
	oldCallback(errors.New("coverage lost"))
	got := manager.invalidate(failure)
	if len(got) != 1 || got[0].JobID != "a" || stops["b"] != 0 || len(manager.roots()) != 1 {
		t.Fatal("watch error stopped unrelated task")
	}
	manager.apply([]Root{a, b})
	oldCallback(errors.New("late callback"))
	if got := manager.invalidate(failure); len(got) != 0 || len(manager.roots()) != 2 {
		t.Fatal("stale callback stopped replacement watch")
	}
}
func TestRemovedOrDisabledAssignmentsCancelOnlyRelevantTransfers(t *testing.T) {
	initial := []Root{{JobID: "a", Path: "source-a", Enabled: true, TransferThreads: 4}, {JobID: "b", Path: "source-b", Enabled: true}}
	batch := Batch{Events: []Event{{JobID: "a"}}}
	next := Configuration{Status: "APPROVED", Roots: append([]Root(nil), initial...)}
	next.Roots[1].Enabled = false
	if deliveryConfigurationChanged(initial, next, batch) {
		t.Fatal("unrelated paused job cancelled active transfer")
	}
	next.Roots[0].FullScanRequired = true
	if deliveryConfigurationChanged(initial, next, batch) {
		t.Fatal("baseline request cancelled an active baseline")
	}
	next.Roots[0].Enabled = false
	if !deliveryConfigurationChanged(initial, next, batch) {
		t.Fatal("paused job keeps transferring")
	}
	next.Roots = next.Roots[1:]
	if !deliveryConfigurationChanged(initial, next, batch) {
		t.Fatal("removed job keeps transferring")
	}
	next.Status = "REVOKED"
	if !deliveryConfigurationChanged(initial, next, batch) {
		t.Fatal("revoked device keeps transferring")
	}
}
