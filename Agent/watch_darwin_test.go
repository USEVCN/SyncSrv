package main

import (
	"github.com/fsnotify/fsevents"
	"testing"
)

func TestDarwinCoalescedEventsForceReconciliation(t *testing.T) {
	roots := []Root{{JobID: "matching", Path: "/source"}, {JobID: "other", Path: "/source-other"}}
	var events []Event
	var failures []error
	emit := func(e Event) { events = append(events, e) }
	fail := func(e error) { failures = append(failures, e) }
	reportDarwinEvent(roots, fsevents.Event{Path: "/source/nested", Flags: fsevents.MustScanSubDirs}, emit, fail)
	if len(events) != 1 || events[0].JobID != "matching" || events[0].Kind != "RESCAN" || events[0].Path != "" || len(failures) != 0 {
		t.Fatalf("coalescing not reconciled: %+v %v", events, failures)
	}
	events = nil
	reportDarwinEvent(roots, fsevents.Event{Path: "/source/nested", Flags: fsevents.MustScanSubDirs | fsevents.UserDropped}, emit, fail)
	if len(events) != 1 || events[0].Kind != "OVERFLOW" || len(failures) != 1 {
		t.Fatalf("loss not invalidated: %+v %v", events, failures)
	}
}
