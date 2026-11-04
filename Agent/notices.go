package main

import (
	"context"
	"encoding/json"
	"errors"
	bolt "go.etcd.io/bbolt"
	"log"
	"time"
)

var noticesBucket = []byte("notices")
var errDebouncing = errors.New("waiting for the center's change merge interval")

// A separate durable outbox keeps visibility independent of hashing, B upload and retry backoff.
func (q *Queue) noticeBatch(limit int) (Batch, error) {
	batch := Batch{Epoch: q.epoch, Events: []Event{}}
	err := q.db.View(func(tx *bolt.Tx) error {
		var config Configuration
		configData := tx.Bucket(metaBucket).Get([]byte("configuration"))
		if configData != nil {
			if err := json.Unmarshal(configData, &config); err != nil {
				return err
			}
			if config.Status != "APPROVED" {
				return nil
			}
		}
		direct := map[string]bool{}
		for _, root := range config.Roots {
			direct[root.JobID] = root.SourceMode == "AGENT"
		}
		cursor := tx.Bucket(noticesBucket).Cursor()
		used := 0
		for k, value := cursor.First(); k != nil && len(batch.Events) < limit; k, value = cursor.Next() {
			if used+len(value) > 512*1024 && len(batch.Events) > 0 {
				break
			}
			var event Event
			if err := json.Unmarshal(value, &event); err != nil {
				return err
			}
			if configData != nil && !direct[event.JobID] {
				continue
			}
			batch.Events = append(batch.Events, event)
			used += len(value)
		}
		return nil
	})
	return batch, err
}

func (q *Queue) acknowledgeNotices(batch Batch, acknowledged []string) error {
	ids := map[string]bool{}
	for _, id := range acknowledged {
		ids[id] = true
	}
	return q.db.Update(func(tx *bolt.Tx) error {
		bucket := tx.Bucket(noticesBucket)
		for _, event := range batch.Events {
			if !ids[event.ID] {
				continue
			}
			value := bucket.Get(eventKey(event))
			if value == nil {
				continue
			}
			var current Event
			if err := json.Unmarshal(value, &current); err != nil {
				return err
			}
			if current.ID == event.ID {
				if err := bucket.Delete(eventKey(event)); err != nil {
					return err
				}
			}
		}
		return nil
	})
}

func (c *Client) notifyChanges(ctx context.Context, q *Queue, fail func(error)) {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			batch, err := q.noticeBatch(256)
			if err != nil {
				fail(err)
				return
			}
			if len(batch.Events) == 0 {
				continue
			}
			var reply struct {
				IDs []string `json:"acknowledged"`
			}
			if err = c.request(ctx, "POST", "/agent/v1/changes/notice", batch, &reply, true); err != nil {
				log.Print("Early change notification: ", err)
				continue
			}
			if err = q.acknowledgeNotices(batch, reply.IDs); err != nil {
				fail(err)
				return
			}
		}
	}
}
