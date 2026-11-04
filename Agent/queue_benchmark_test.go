package main

import (
	"fmt"
	"testing"
	"time"
)

// Measures local durable transactions on hot paths; not filesystem event coverage or WAN throughput.
func BenchmarkDurableCoalescing(b *testing.B) {
	for _, count := range []int{1, 256} {
		b.Run(fmt.Sprintf("batch_%d", count), func(b *testing.B) {
			q, err := openQueue(b.TempDir())
			if err != nil {
				b.Fatal(err)
			}
			defer q.db.Close()
			events := make([]Event, count)
			for i := range events {
				events[i] = Event{JobID: "job", Path: fmt.Sprintf("folder/file-%d", i), Kind: "CHANGE"}
			}
			b.ReportAllocs()
			b.ResetTimer()
			start := time.Now()
			for i := 0; i < b.N; i++ {
				if err = q.add(events); err != nil {
					b.Fatal(err)
				}
			}
			b.ReportMetric(float64(b.N*count)/time.Since(start).Seconds(), "events/s")
		})
	}
}
