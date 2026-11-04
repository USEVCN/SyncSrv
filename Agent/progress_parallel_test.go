package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestProgressHTTPDoesNotHoldOtherFileWorkers(t *testing.T) {
	entered, release := make(chan struct{}), make(chan struct{})
	var requests atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if requests.Add(1) == 1 {
			close(entered)
		}
		<-release
		w.WriteHeader(http.StatusServiceUnavailable)
		w.Write([]byte(`{"success":false,"message":"fixture failure"}`))
	}))
	defer server.Close()
	_, key, _ := ed25519.GenerateKey(rand.Reader)
	client, err := newClient(Identity{Server: server.URL, PrivateKey: key})
	if err != nil {
		t.Fatal(err)
	}
	progress := client.changeProgress(context.Background(), Batch{})
	first, second := make(chan error, 1), make(chan error, 1)
	go func() { first <- progress("UPLOADING_B", "first") }()
	awaitStorage(t, entered)
	go func() { second <- progress("UPLOADING_B", "second") }()
	select {
	case err := <-second:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(500 * time.Millisecond):
		close(release)
		t.Fatal("a slow progress HTTP request blocked another file worker")
	}
	close(release)
	if awaitStorage(t, first) == nil || progress("VERIFYING_B", "third") == nil {
		t.Fatal("progress failure was not preserved")
	}
	if requests.Load() != 1 {
		t.Fatal("observational updates were not coalesced")
	}
}
