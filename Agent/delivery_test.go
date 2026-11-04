package main

import (
	"context"
	"errors"
	"fmt"
	"testing"
)

func TestReadFailureKeepsOnlyFailedEventsAndCannotRemoveNewerSave(t *testing.T) {
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	q.add([]Event{{JobID: "job", Path: "locked.iso"}, {JobID: "job", Path: "ok.txt"}, {JobID: "other", Path: "other.txt"}})
	batch, _ := q.batch(100)
	locked := &sourceReadError{path: "locked.iso", cause: errors.New("fixture sharing violation"), busy: true}
	acknowledged, err := deliverWith(context.Background(), []Root{{JobID: "job"}, {JobID: "other"}}, batch,
		func(_ context.Context, _ Root, b Batch) ([]string, error) {
			for _, e := range b.Events {
				if e.Path == "locked.iso" {
					return nil, locked
				}
			}
			var ids []string
			for _, e := range b.Events {
				ids = append(ids, e.ID)
			}
			return ids, nil
		})
	if !errors.Is(err, locked) || len(acknowledged) != 2 {
		t.Fatalf("ack=%v err=%v", acknowledged, err)
	}
	q.add([]Event{{JobID: "job", Path: "ok.txt"}}) // New save during upload cannot be removed by an old ACK.
	if err = q.ack(batch, acknowledged); err != nil {
		t.Fatal(err)
	}
	pending, _ := q.batch(100)
	if len(pending.Events) != 2 {
		t.Fatalf("lost pending events: %+v", pending)
	}
	for _, e := range pending.Events {
		if e.JobID != "job" {
			t.Fatal("completed independent job not acknowledged")
		}
	}
}
func TestIncompleteBaselineRemainsAtomicWhileAnotherJobProceeds(t *testing.T) {
	batch := Batch{Epoch: "e", Events: []Event{{JobID: "full", ID: "1", Path: ""}, {JobID: "full", ID: "2", Path: "ok"}, {JobID: "other", ID: "3", Path: "ok"}}}
	attempts := 0
	ack, err := deliverWith(context.Background(), []Root{{JobID: "full", FullScanRequired: true}, {JobID: "other"}}, batch,
		func(_ context.Context, r Root, b Batch) ([]string, error) {
			if r.JobID == "full" {
				attempts++
				return nil, &sourceReadError{cause: fmt.Errorf("unreadable baseline")}
			}
			return []string{"3"}, nil
		})
	if err == nil || attempts != 1 || len(ack) != 1 || ack[0] != "3" {
		t.Fatalf("unsafe partial baseline: %v %d %v", err, attempts, ack)
	}
}
func TestNetworkFailureIsNotRetriedAsPerFileReads(t *testing.T) {
	calls := 0
	_, err := deliverWith(context.Background(), []Root{{JobID: "job"}}, Batch{Events: []Event{{JobID: "job", ID: "1"}, {JobID: "job", ID: "2"}}},
		func(context.Context, Root, Batch) ([]string, error) {
			calls++
			return nil, errors.New("connection refused")
		})
	if err == nil || calls != 1 {
		t.Fatal("network failure was split into file retries")
	}
}
func TestDebounceIsPreserved(t *testing.T) {
	_, err := deliverWith(context.Background(), []Root{{JobID: "job"}}, Batch{Events: []Event{{JobID: "job", ID: "1"}}},
		func(context.Context, Root, Batch) ([]string, error) { return nil, errDebouncing })
	if !errors.Is(err, errDebouncing) {
		t.Fatal(err)
	}
}
