package main

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	bolt "go.etcd.io/bbolt"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

type Event struct {
	ID         string `json:"id"`
	JobID      string `json:"jobId"`
	Path       string `json:"path"`
	Kind       string `json:"kind"`
	ObservedAt string `json:"observedAt"`
	Coalesced  int64  `json:"coalesced,omitempty"`
}
type Batch struct {
	Epoch  string  `json:"epoch"`
	Events []Event `json:"events"`
}
type Queue struct {
	db           *bolt.DB
	epoch        string
	filename     string
	batchMu      sync.Mutex
	lastBatchKey []byte
}

var metaBucket = []byte("metadata")
var outboxBucket = []byte("outbox")
var countKey = []byte("pending-count")

func randomID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b[:])
}
func openQueue(dir string) (*Queue, error) {
	filename := filepath.Join(dir, "events.db")
	db, err := bolt.Open(filename, 0600, &bolt.Options{Timeout: time.Second})
	if err != nil {
		return nil, err
	}
	q := &Queue{db: db, filename: filename}
	err = db.Update(func(tx *bolt.Tx) error {
		m, e := tx.CreateBucketIfNotExists(metaBucket)
		if e != nil {
			return e
		}
		if _, e = tx.CreateBucketIfNotExists(noticesBucket); e != nil {
			return e
		}
		if _, e = tx.CreateBucketIfNotExists(outboxBucket); e != nil {
			return e
		}
		epoch := m.Get([]byte("epoch"))
		if epoch == nil {
			if e = m.Put([]byte("epoch"), []byte(randomID())); e != nil {
				return e
			}
		}
		if m.Get(countKey) == nil {
			if e = m.Put(countKey, []byte(strconv.Itoa(tx.Bucket(outboxBucket).Stats().KeyN))); e != nil {
				return e
			}
		}
		q.epoch = string(m.Get([]byte("epoch")))
		return nil
	})
	if err != nil {
		db.Close()
		return nil, err
	}
	return q, nil
}
func eventKey(e Event) []byte { return []byte(e.JobID + "\x00" + e.Path) }

// A newer local state replaces an obsolete unsent state with a NEW event ID.
// An in-flight old acknowledgement can never remove the new state.
func (q *Queue) add(events []Event) error {
	return q.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket(outboxBucket)
		count, err := strconv.Atoi(string(tx.Bucket(metaBucket).Get(countKey)))
		if err != nil {
			return err
		}
		var config Configuration
		rawConfig := tx.Bucket(metaBucket).Get([]byte("configuration"))
		if rawConfig != nil {
			if err = json.Unmarshal(rawConfig, &config); err != nil {
				return err
			}
		}
		direct := map[string]bool{}
		for _, root := range config.Roots {
			direct[root.JobID] = root.SourceMode == "AGENT"
		}
		for _, e := range events {
			if previous := b.Get(eventKey(e)); previous != nil {
				var old Event
				if err := json.Unmarshal(previous, &old); err != nil {
					return err
				}
				e.Coalesced += old.Coalesced + 1
			}
			seq, err := b.NextSequence()
			if err != nil {
				return err
			}
			e.ID = strconv.FormatUint(seq, 10)
			if e.ObservedAt == "" {
				e.ObservedAt = time.Now().UTC().Format(time.RFC3339Nano)
			}
			raw, err := json.Marshal(e)
			if err != nil {
				return err
			}
			if b.Get(eventKey(e)) == nil {
				count++
			}
			if rawConfig == nil || direct[e.JobID] {
				if err = tx.Bucket(noticesBucket).Put(eventKey(e), raw); err != nil {
					return err
				}
			}
			if err = b.Put(eventKey(e), raw); err != nil {
				return err
			}
		}
		return tx.Bucket(metaBucket).Put(countKey, []byte(strconv.Itoa(count)))
	})
}
func (q *Queue) batch(limit int) (Batch, error) { return q.batchFor(limit, nil) }
func (q *Queue) batchFor(limit int, allowed map[string]bool) (Batch, error) {
	q.batchMu.Lock()
	defer q.batchMu.Unlock()
	out := Batch{Epoch: q.epoch, Events: []Event{}}
	var last []byte
	err := q.db.View(func(tx *bolt.Tx) error {
		c := tx.Bucket(outboxBucket).Cursor()
		used := 0
		// Continue beyond a failed batch, so permanently blocked paths cannot
		// starve later paths or another device task. Pending records stay durable.
		k, v := c.First()
		if len(q.lastBatchKey) > 0 {
			k, v = c.Seek(q.lastBatchKey)
			if bytes.Equal(k, q.lastBatchKey) {
				k, v = c.Next()
			}
			if k == nil {
				k, v = c.First()
			}
		}
		start := append([]byte(nil), k...)
		for first := true; k != nil && len(out.Events) < limit; {
			if !first && bytes.Equal(k, start) {
				break
			}
			first = false
			currentKey := k
			value := v
			k, v = c.Next()
			if k == nil {
				k, v = c.First()
			}

			var candidate Event
			if err := json.Unmarshal(value, &candidate); err != nil {
				return err
			}
			if allowed != nil && !allowed[candidate.JobID] {
				last = append(last[:0], currentKey...)
				continue
			}
			if used+len(value) > 512*1024 && len(out.Events) > 0 {
				break
			}
			last = append(last[:0], currentKey...)
			used += len(value)
			out.Events = append(out.Events, candidate)
		}
		return nil
	})
	if err == nil && len(last) > 0 {
		q.lastBatchKey = append(q.lastBatchKey[:0], last...)
	}
	return out, err
}
func (q *Queue) ack(sent Batch, ids []string) error {
	known := map[string]Event{}
	for _, e := range sent.Events {
		known[e.ID] = e
	}
	for _, id := range ids {
		if _, ok := known[id]; !ok {
			return fmt.Errorf("server acknowledged unknown event %s", id)
		}
	}
	return q.db.Update(func(tx *bolt.Tx) error {
		b := tx.Bucket(outboxBucket)
		count, err := strconv.Atoi(string(tx.Bucket(metaBucket).Get(countKey)))
		if err != nil {
			return err
		}
		for _, id := range ids {
			e := known[id]
			raw := b.Get(eventKey(e))
			if raw == nil {
				continue
			}
			var current Event
			if err := json.Unmarshal(raw, &current); err != nil {
				return err
			}
			if current.ID == id {
				count--
				if err := b.Delete(eventKey(e)); err != nil {
					return err
				}
			}
		}
		return tx.Bucket(metaBucket).Put(countKey, []byte(strconv.Itoa(count)))
	})
}
func (q *Queue) count() (int, error) {
	n := 0
	err := q.db.View(func(tx *bolt.Tx) error {
		var e error
		n, e = strconv.Atoi(string(tx.Bucket(metaBucket).Get(countKey)))
		return e
	})
	return n, err
}

// A callback already in flight when a job is removed must not block other jobs.
func (q *Queue) retireEvent(event Event) error {
	return q.db.Update(func(tx *bolt.Tx) error {
		archive, err := tx.CreateBucketIfNotExists([]byte("superseded-configuration"))
		if err != nil {
			return err
		}
		seq, err := tx.Bucket(outboxBucket).NextSequence()
		if err != nil {
			return err
		}
		event.ID = strconv.FormatUint(seq, 10)
		event.ObservedAt = time.Now().UTC().Format(time.RFC3339Nano)
		raw, err := json.Marshal(event)
		if err != nil {
			return err
		}
		return archive.Put([]byte(event.ID), raw)
	})
}

// Removed assignments are retained as superseded records, not falsely acknowledged.
func (q *Queue) retainJobs(roots []Root) (int, error) {
	allowed := map[string]bool{}
	for _, r := range roots {
		allowed[r.JobID] = true
	}
	retired := 0
	err := q.db.Update(func(tx *bolt.Tx) error {
		archive, e := tx.CreateBucketIfNotExists([]byte("superseded-configuration"))
		if e != nil {
			return e
		}
		outbox := tx.Bucket(outboxBucket)
		cursor := outbox.Cursor()
		count, e := strconv.Atoi(string(tx.Bucket(metaBucket).Get(countKey)))
		if e != nil {
			return e
		}
		var removeKeys [][]byte
		for k, v := cursor.First(); k != nil; k, v = cursor.Next() {
			var event Event
			if e = json.Unmarshal(v, &event); e != nil {
				return e
			}
			if !allowed[event.JobID] {
				if e = archive.Put([]byte(event.ID), v); e != nil {
					return e
				}
				removeKeys = append(removeKeys, append([]byte(nil), k...))
			}
		}
		for _, key := range removeKeys {
			if e = tx.Bucket(noticesBucket).Delete(key); e != nil {
				return e
			}
			if e = outbox.Delete(key); e != nil {
				return e
			}
			retired++
			count--
		}
		return tx.Bucket(metaBucket).Put(countKey, []byte(strconv.Itoa(count)))
	})
	return retired, err
}
