package main

import (
	"context"
	"sync"
	"time"
)

const smallFileLimit = 1024 * 1024

// Keep enough batches to occupy the pool, without buffering file contents.
func fileBatches(files []SourceNode, workers int) [][]SourceNode {
	count := min(32, max(1, (len(files)+workers*4-1)/(workers*4)))
	var batches [][]SourceNode
	for len(files) > 0 {
		n, size := 1, files[0].Size
		if size <= smallFileLimit {
			for n < len(files) && n < count && files[n].Size <= smallFileLimit && size+files[n].Size <= 8*1024*1024 {
				size += files[n].Size
				n++
			}
		}
		batches = append(batches, files[:n])
		files = files[n:]
	}
	return batches
}

type concurrencyTuner struct {
	limit, maximum, previous int
	rate, latency            float64
	probing                  bool
	cooldown                 time.Time
	reason                   string
}

// Compare useful completion rates, not idle bandwidth. A probe needs measurable benefit.
func (t *concurrencyTuner) sample(now time.Time, rate, latency float64, saturated, backlog bool) {
	if !backlog {
		t.reason = "队列收尾，保持并发"
		return
	}
	if t.probing {
		if rate < t.rate*1.05 || latency > t.latency*1.5 {
			t.limit = t.previous
			t.cooldown = now.Add(30 * time.Second)
			t.reason = "增加并发未改善完成速度，已回退"
		} else {
			t.reason = "完成速度提高，保留并发"
		}
		t.probing = false
		t.rate, t.latency = rate, latency
		return
	}
	if t.rate > 0 && rate < t.rate*0.75 && latency > t.latency*1.5 && t.limit > 1 {
		t.limit = max(1, t.limit/2)
		t.cooldown = now.Add(30 * time.Second)
		t.reason = "完成延迟上升，降低并发"
	} else if saturated {
		t.reason = "接近任务总限速，保持并发"
	} else if rate > 0 && now.After(t.cooldown) && t.limit < t.maximum {
		t.previous = t.limit
		t.limit = min(t.maximum, t.limit+2)
		t.probing = true
		t.reason = "有待处理文件，试增并发"
	}
	t.rate, t.latency = rate, latency
}

type batchWorker func(context.Context, []SourceNode) error
type batchFactory func(context.Context) (batchWorker, func(), error)

func parallelBatches(ctx context.Context, root Root, files []SourceNode, factory batchFactory) error {
	if len(files) == 0 {
		return ctx.Err()
	}
	maximum := fileConcurrency(root.TransferThreads)
	if root.AdaptiveTransfers {
		maximum = root.MaxTransferThreads
		if maximum <= 0 {
			maximum = 16
		}
		maximum = min(maximum, 16)
	}
	batches := fileBatches(files, maximum)
	maximum = min(maximum, len(batches))
	tuner := concurrencyTuner{limit: maximum, maximum: maximum, reason: "手动固定并发"}
	if root.AdaptiveTransfers {
		tuner.limit = min(4, maximum)
		tuner.reason = "低并发起步，观察完成速度"
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	queue := make(chan []SourceNode)
	type result struct {
		files   int
		bytes   int64
		elapsed time.Duration
		err     error
	}
	results := make(chan result, maximum)
	var wg sync.WaitGroup
	workers := 0
	startWorkers := func() {
		for workers < tuner.limit {
			workers++
			wg.Add(1)
			go func() {
				defer wg.Done()
				var run batchWorker
				for batch := range queue {
					if ctx.Err() != nil {
						return
					}
					started := time.Now()
					if run == nil {
						var closeWorker func()
						var err error
						run, closeWorker, err = factory(ctx)
						if err != nil {
							results <- result{err: err}
							return
						}
						defer closeWorker()
					}
					err := run(ctx, batch)
					var bytes int64
					for _, n := range batch {
						bytes += n.Size
					}
					results <- result{len(batch), bytes, time.Since(started), err}
					if err != nil {
						return
					}
				}
			}()
		}
	}
	defer func() { cancel(); close(queue); wg.Wait() }()
	startWorkers()
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	last := time.Now()
	var completed int
	var bytes int64
	var duration time.Duration
	var small int
	for _, n := range files {
		if n.Size <= smallFileLimit {
			small++
		}
	}
	preferFiles := small*10 >= len(files)*7
	charged := int64(0)
	if limiter, ok := ctx.Value(transferLimitKey{}).(*transferLimit); ok {
		charged = limiter.usage()
	}
	next, active := 0, 0
	for next < len(batches) || active > 0 {
		sourceProgress(ctx).concurrency(tuner.limit, maximum, root.AdaptiveTransfers, tuner.reason)
		var out chan []SourceNode
		var batch []SourceNode
		if next < len(batches) && active < tuner.limit {
			out = queue
			batch = batches[next]
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case out <- batch:
			next++
			active++
		case r := <-results:
			if r.err != nil {
				return r.err
			}
			active--
			completed += r.files
			bytes += r.bytes
			duration += r.elapsed
		case now := <-ticker.C:
			seconds := now.Sub(last).Seconds()
			saturated := false
			if limiter, ok := ctx.Value(transferLimitKey{}).(*transferLimit); ok {
				used := limiter.usage()
				saturated = limiter.rate > 0 && float64(used-charged)/seconds >= limiter.rate*0.85
				charged = used
			}
			if root.AdaptiveTransfers && completed >= 4 {
				rate := float64(bytes) / seconds
				if preferFiles {
					rate = float64(completed) / seconds
				}
				tuner.sample(now, rate, duration.Seconds()/float64(completed), saturated, next < len(batches))
				startWorkers()
			}
			last = now
			completed = 0
			bytes = 0
			duration = 0
		}
	}
	return nil
}
