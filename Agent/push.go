package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type SourceNode struct {
	Path      string `json:"path"`
	Directory bool   `json:"directory"`
	Size      int64  `json:"size"`
	Modified  int64  `json:"modified"`
	Hash      string `json:"hash"`
	VersionID string `json:"versionId"`
}
type UploadStatus struct {
	Hash   string `json:"hash"`
	Offset int64  `json:"offset"`
	Ready  bool   `json:"ready"`
}

func validRelative(path string) error {
	if path == "" {
		return nil
	}
	if strings.ContainsAny(path, "\\\x00\r\n") || strings.HasPrefix(path, "/") {
		return fmt.Errorf("unsupported path: %q", path)
	}
	for _, p := range strings.Split(path, "/") {
		if p == "" || p == "." || p == ".." {
			return fmt.Errorf("unsafe path: %q", path)
		}
	}
	if strings.EqualFold(strings.Split(path, "/")[0], ".syncsrv") {
		return fmt.Errorf("reserved source path: %s", path)
	}
	return nil
}
func inspectSource(root, relative string) (string, os.FileInfo, error) {
	if err := validRelative(relative); err != nil {
		return "", nil, err
	}
	full := root
	info, err := os.Lstat(full)
	if err != nil {
		return "", nil, err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return "", nil, fmt.Errorf("source root unavailable: %s", root)
	}
	if relative == "" {
		return full, info, nil
	}
	for _, part := range strings.Split(relative, "/") {
		full = filepath.Join(full, part)
		info, err = os.Lstat(full)
		if err != nil {
			return "", nil, err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return "", nil, fmt.Errorf("symbolic source path: %s", full)
		}
	}
	return full, info, nil
}
func collectSourceInventory(ctx context.Context, root string, events []Event, full bool) ([]string, []SourceNode, error) {
	if _, _, err := inspectSource(root, ""); err != nil {
		return nil, nil, err
	}
	scopes := map[string]bool{}
	if full {
		scopes[""] = true
	}
	for _, e := range events {
		if e.Path == "" {
			scopes[""] = true
			continue
		}
		if err := validRelative(e.Path); err != nil {
			return nil, nil, err
		}
		prefix := ""
		for _, part := range strings.Split(e.Path, "/") {
			if prefix != "" {
				prefix += "/"
			}
			prefix += part
			_, info, err := inspectSource(root, prefix)
			if os.IsNotExist(err) {
				break
			}
			if err != nil {
				return nil, nil, err
			}
			if !info.IsDir() {
				break
			}
		}
		scopes[prefix] = true
	}
	var paths []string
	for p := range scopes {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	var selected []string
	for _, p := range paths {
		covered := false
		for _, previous := range selected {
			if previous == "" || p == previous || strings.HasPrefix(p, previous+"/") {
				covered = true
				break
			}
		}
		if !covered {
			selected = append(selected, p)
		}
	}
	nodes := map[string]SourceNode{}
	add := func(p string, info os.FileInfo) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if p == "" {
			return nil
		}
		if err := validRelative(p); err != nil {
			return err
		}
		if !info.IsDir() && !info.Mode().IsRegular() {
			return fmt.Errorf("unsupported source file: %s", p)
		}
		n := SourceNode{Path: p, Directory: info.IsDir(), Modified: info.ModTime().UnixMilli()}
		if !n.Directory {
			n.Size = info.Size()
		}

		nodes[p] = n
		return nil
	}
	for _, scope := range selected {
		full, _, err := inspectSource(root, scope)
		if err != nil && !os.IsNotExist(err) {
			return nil, nil, err
		}
		if err == nil {
			err = filepath.WalkDir(full, func(path string, entry fs.DirEntry, walkErr error) error {
				if walkErr != nil {
					return walkErr
				}
				rel, e := filepath.Rel(root, path)
				if e != nil {
					return e
				}
				rel = filepath.ToSlash(rel)
				if rel == "." {
					rel = ""
				}
				_, info, e := inspectSource(root, rel)
				if e != nil {
					return e
				}
				return add(rel, info)
			})
			if err != nil {
				return nil, nil, err
			}
		}
		for parent := filepath.ToSlash(filepath.Dir(scope)); parent != "." && parent != ""; parent = filepath.ToSlash(filepath.Dir(parent)) {
			_, info, e := inspectSource(root, parent)
			if e != nil {
				return nil, nil, e
			}
			if e = add(parent, info); e != nil {
				return nil, nil, e
			}
		}
	}
	result := make([]SourceNode, 0, len(nodes))
	for _, n := range nodes {
		result = append(result, n)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Path < result[j].Path })
	return selected, result, nil
}

// Compatibility helper for callers that explicitly need a fully hashed manifest.
func collectSource(ctx context.Context, root string, events []Event, full bool) ([]string, []SourceNode, error) {
	scopes, nodes, err := collectSourceInventory(ctx, root, events, full)
	if err != nil {
		return nil, nil, err
	}
	for i, n := range nodes {
		if !n.Directory {
			nodes[i], err = hashSourceFile(ctx, root, n)
			if err != nil {
				return nil, nil, err
			}
		}
	}
	return scopes, nodes, nil
}

func hashSourceFile(ctx context.Context, root string, n SourceNode) (SourceNode, error) {
	_, info, err := inspectSource(root, n.Path)
	if err != nil {
		return n, sourceFailure(n.Path, err)
	}
	if !info.Mode().IsRegular() || info.Size() != n.Size || info.ModTime().UnixMilli() != n.Modified {
		return n, fmt.Errorf("source changed before checksum: %s", n.Path)
	}
	base, err := os.OpenRoot(root)
	if err != nil {
		return n, err
	}
	f, err := base.Open(filepath.FromSlash(n.Path))
	base.Close()
	if err != nil {
		return n, sourceFailure(n.Path, err)
	}
	defer f.Close()
	p := sourceProgress(ctx)
	p.file(n.Path, "HASHING_SOURCE", 0)
	hash := sha256.New()
	size, err := io.CopyBuffer(hash, &pacedReader{ctx: ctx, reader: f, onRead: func(n int) { p.read(n, true) }}, make([]byte, 256*1024))
	if err != nil {
		return n, sourceFailure(n.Path, err)
	}
	after, err := f.Stat()
	if err != nil {
		return n, err
	}
	if size != n.Size || after.Size() != n.Size || after.ModTime().UnixMilli() != n.Modified {
		return n, fmt.Errorf("source changed during checksum: %s", n.Path)
	}
	n.Hash = hex.EncodeToString(hash.Sum(nil))
	return n, nil
}

func (c *Client) pushSource(ctx context.Context, root Root, events []Event, observers ...func(string, string) error) error {
	progress := func(phase, path string) error {
		for _, observer := range observers {
			if err := observer(phase, path); err != nil {
				return err
			}
		}
		return nil
	}
	started := time.Now()
	// Enumerate metadata first: no full-content reads delay the first upload.
	scopes, nodes, err := collectSourceInventory(ctx, root.Path, events, root.FullScanRequired)
	if err != nil {
		return sourceFailure("", err)
	}
	var session struct {
		ID          string `json:"id"`
		BatchDirect bool   `json:"batchDirect"`
	}
	if err = c.request(ctx, "POST", "/agent/v1/push/begin", map[string]any{"jobId": root.JobID, "token": randomID(), "scopes": scopes, "entries": len(nodes), "transferMode": "DIRECT"}, &session, true); err != nil {
		return err
	}
	ctx, stopProgress := c.watchTransfer(ctx, session.ID)
	defer stopProgress()
	sourceProgress(ctx).mu.Lock()
	sourceProgress(ctx).extended = session.BatchDirect
	sourceProgress(ctx).mu.Unlock()
	completed := false
	defer func() {
		if !completed {
			cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			_ = c.request(cleanup, "POST", "/agent/v1/push/abort", map[string]any{"sessionId": session.ID}, nil, true)
		}
	}()
	for offset := 0; offset < len(nodes); offset += 128 {
		if err = c.request(ctx, "POST", "/agent/v1/push/inventory", map[string]any{"sessionId": session.ID, "offset": offset, "entries": nodes[offset:min(offset+128, len(nodes))]}, nil, true); err != nil {
			return err
		}
	}
	if err = progress("CONNECTING_B", ""); err != nil {
		return err
	}
	ctx = c.withFleetBudget(withTransferLimit(ctx, root.BandwidthMiB))
	var manifestMu sync.Mutex
	received := 0
	publish := func(ctx context.Context, entries []SourceNode) error {
		manifestMu.Lock()
		defer manifestMu.Unlock()
		err := c.request(ctx, "POST", "/agent/v1/push/page", map[string]any{"sessionId": session.ID, "offset": received, "entries": entries}, nil, true)
		if err == nil {
			received += len(entries)
		}
		return err
	}
	// Directory metadata has no content dependency.
	var files, directories []SourceNode
	for _, n := range nodes {
		if n.Directory {
			directories = append(directories, n)
		} else {
			files = append(files, n)
		}
	}
	for offset := 0; offset < len(directories); offset += 128 {
		if err = publish(ctx, directories[offset:min(offset+128, len(directories))]); err != nil {
			return err
		}
	}
	transferStarted := time.Now()
	sourceProgress(ctx).timing(transferStarted.Sub(started), 0)
	if session.BatchDirect {
		err = c.pushBatches(ctx, root, session.ID, files, publish, progress)
	} else {
		err = parallelFiles(ctx, root.TransferThreads, files, func(workerCtx context.Context, queue <-chan SourceNode) error {
			// Each worker owns its protocol connection: FTP and SFTP streams never share cursors.
			target, e := c.directTarget(workerCtx, session.ID)
			if e != nil {
				return e
			}
			defer target.close()
			for n := range queue {
				if e = workerCtx.Err(); e != nil {
					return e
				}
				fileCtx := withSourceFile(workerCtx, n.Path)
				n, e = hashSourceFile(fileCtx, root.Path, n)
				if e != nil {
					return e
				}
				if e = publish(fileCtx, []SourceNode{n}); e != nil {
					return e
				}
				if e = progress("UPLOADING_B", n.Path); e != nil {
					return e
				}
				if e = c.directUpload(fileCtx, target, session.ID, root, n, progress); e != nil {
					return e
				}
				sourceProgress(fileCtx).done()
			}
			return nil
		})
	}
	if err != nil {
		return err
	}
	sourceProgress(ctx).timing(transferStarted.Sub(started), time.Since(transferStarted))
	finish := map[string]any{"sessionId": session.ID}
	if session.BatchDirect {
		finish["stats"] = sourceProgress(ctx).stats()
		finish["telemetry"] = sourceProgress(ctx).snapshot()
	}
	err = c.request(ctx, "POST", "/agent/v1/push/finish", finish, nil, true)
	completed = err == nil
	return err
}
