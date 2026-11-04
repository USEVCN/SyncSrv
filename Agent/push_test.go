package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestSourceScopeIncludesNewAncestorsAndDeletion(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "new", "nested"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "new", "nested", "file"), []byte("hello"), 0600); err != nil {
		t.Fatal(err)
	}
	scopes, nodes, err := collectSource(context.Background(), root, []Event{{Path: "new/nested/file"}}, false)
	if err != nil {
		t.Fatal(err)
	}
	if len(scopes) != 1 || scopes[0] != "new/nested/file" || len(nodes) != 3 {
		t.Fatalf("%v %+v", scopes, nodes)
	}
	if nodes[2].Hash != "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824" {
		t.Fatalf("hash: %+v", nodes[2])
	}
	if err = os.RemoveAll(filepath.Join(root, "new")); err != nil {
		t.Fatal(err)
	}
	scopes, nodes, err = collectSource(context.Background(), root, []Event{{Path: "new/nested/file"}}, false)
	if err != nil || len(nodes) != 0 || scopes[0] != "new" {
		t.Fatalf("%v %v %v", scopes, nodes, err)
	}
}
func TestSourceCollectorRejectsMissingRootAndSymlink(t *testing.T) {
	root := t.TempDir()
	if _, _, err := collectSource(context.Background(), filepath.Join(root, "missing"), nil, true); err == nil {
		t.Fatal("missing root accepted")
	}
	if err := os.Symlink(t.TempDir(), filepath.Join(root, "link")); err != nil {
		t.Skip(err)
	}
	if _, _, err := collectSource(context.Background(), root, nil, true); err == nil {
		t.Fatal("symlink accepted")
	}
}
func TestExplicitPrivateHTTPDevelopment(t *testing.T) {
	if _, err := validateCenter("http://192.168.50.12:18011", true); err != nil {
		t.Fatal(err)
	}
	for _, address := range []string{"http://8.8.8.8", "http://example.com", "http://192.168.50.12/path"} {
		if _, err := validateCenter(address, true); err == nil {
			t.Fatalf("accepted %s", address)
		}
	}
	if _, err := validateCenter("http://192.168.50.12:18011", false); err == nil {
		t.Fatal("HTTP requires opt-in")
	}
}

func TestPausedJobCannotStarveAnotherJob(t *testing.T) {
	q, err := openQueue(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer q.db.Close()
	for i := 0; i < 300; i++ {
		if err = q.add([]Event{{JobID: "a-paused", Path: fmt.Sprint(i)}}); err != nil {
			t.Fatal(err)
		}
	}
	if err = q.add([]Event{{JobID: "z-active", Path: "file"}}); err != nil {
		t.Fatal(err)
	}
	batch, err := q.batchFor(256, map[string]bool{"z-active": true})
	if err != nil {
		t.Fatal(err)
	}
	if len(batch.Events) != 1 || batch.Events[0].JobID != "z-active" {
		t.Fatalf("%+v", batch)
	}
}
