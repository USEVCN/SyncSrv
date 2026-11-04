package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"
)

type ReleaseAsset struct {
	Platform string `json:"platform"`
	Arch     string `json:"arch"`
	Filename string `json:"filename"`
	SHA256   string `json:"sha256"`
	Size     int64  `json:"size"`
}
type AgentRelease struct {
	MinCenterVersion string         `json:"minCenterVersion"`
	Version          string         `json:"version"`
	Product          string         `json:"product"`
	Channel          string         `json:"channel"`
	Schema           int            `json:"schema"`
	IssuedAt         string         `json:"issuedAt"`
	ExpiresAt        string         `json:"expiresAt"`
	Assets           []ReleaseAsset `json:"assets"`
}

var releaseVersion = regexp.MustCompile(`^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-dev(?:\.(0|[1-9][0-9]*))?)?$`)

func compareRelease(a, b string) (int, error) {
	left, right := releaseVersion.FindStringSubmatch(a), releaseVersion.FindStringSubmatch(b)
	if left == nil || right == nil {
		return 0, fmt.Errorf("unsupported release version: %q / %q", a, b)
	}
	for i := 1; i <= 3; i++ {
		x, err := strconv.ParseUint(left[i], 10, 32)
		if err != nil {
			return 0, err
		}
		y, err := strconv.ParseUint(right[i], 10, 32)
		if err != nil {
			return 0, err
		}
		if x < y {
			return -1, nil
		}
		if x > y {
			return 1, nil
		}
	}
	lp, rp := strings.Contains(a, "-"), strings.Contains(b, "-")
	if lp != rp {
		if lp {
			return -1, nil
		}
		return 1, nil
	}
	x, y := uint64(0), uint64(0)
	var err error
	if left[4] != "" {
		x, err = strconv.ParseUint(left[4], 10, 32)
		if err != nil {
			return 0, err
		}
	}
	if right[4] != "" {
		y, err = strconv.ParseUint(right[4], 10, 32)
		if err != nil {
			return 0, err
		}
	}
	if x < y {
		return -1, nil
	}
	if x > y {
		return 1, nil
	}
	return 0, nil
}
func prepareUpdate(args []string) error {
	flags := flag.NewFlagSet("prepare-update", flag.ContinueOnError)
	dir := flags.String("data-dir", "", "existing device data directory")
	out := flags.String("output-dir", "", "empty protected staging directory")
	current := flags.String("current-version", "", "installed version; never downgrade")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if !filepath.IsAbs(*dir) || !filepath.IsAbs(*out) {
		return fmt.Errorf("absolute data and staging directories required")
	}
	entries, err := os.ReadDir(*out)
	if err != nil || len(entries) != 0 {
		return fmt.Errorf("staging directory must exist and be empty")
	}
	info, err := os.Lstat(*out)
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("staging must be a real directory")
	}
	identity, err := loadIdentity(*dir)
	if err != nil {
		return err
	}
	client, err := newClient(identity)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
	defer cancel()
	var envelope signedRelease
	if err = client.request(ctx, "GET", "/agent/releases/latest", nil, &envelope, false); err != nil {
		return err
	}
	release, err := verifyRelease(envelope, releaseTrust, time.Now())
	if err != nil {
		return err
	}
	if release.MinCenterVersion != "" {
		var center struct {
			Version string `json:"version"`
		}
		if err = client.request(ctx, "GET", "/api/health", nil, &center, false); err != nil {
			return err
		}
		compatible, e := compareRelease(center.Version, release.MinCenterVersion)
		if e != nil || compatible < 0 {
			return fmt.Errorf("upgrade center to %s before this Agent", release.MinCenterVersion)
		}
	}
	compared, err := compareRelease(release.Version, *current)
	if err != nil {
		return err
	}
	if compared <= 0 {
		fmt.Println("UNCHANGED")
		return nil
	}
	name := "syncsrv-agent-" + runtime.GOOS + "-" + runtime.GOARCH
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	var asset *ReleaseAsset
	for i := range release.Assets {
		a := &release.Assets[i]
		if a.Platform == runtime.GOOS && a.Arch == runtime.GOARCH {
			if asset != nil {
				return fmt.Errorf("duplicate release architecture")
			}
			asset = a
		}
	}
	if asset == nil || asset.Filename != name {
		return fmt.Errorf("no matching release for %s/%s", runtime.GOOS, runtime.GOARCH)
	}
	destination := filepath.Join(*out, "syncsrv-agent")
	if runtime.GOOS == "windows" {
		destination += ".exe"
	}
	if err = downloadUpdate(ctx, client, release.Version, *asset, destination); err != nil {
		return err
	}
	reported, err := exec.CommandContext(ctx, destination, "version").Output()
	if err != nil || strings.TrimSpace(string(reported)) != release.Version+" "+runtime.GOOS+" "+runtime.GOARCH {
		return fmt.Errorf("downloaded binary version/platform check failed")
	}
	// Only output the version: wrappers use it as data, never as executable shell text.
	fmt.Println(release.Version)
	return nil
}
func downloadUpdate(ctx context.Context, client *Client, version string, asset ReleaseAsset, destination string) error {
	if !releaseVersion.MatchString(version) || asset.Size <= 0 || asset.Size > 64<<20 {
		return fmt.Errorf("invalid release metadata")
	}
	digest, err := hex.DecodeString(asset.SHA256)
	if err != nil || len(digest) != sha256.Size {
		return fmt.Errorf("invalid release checksum")
	}
	if filepath.Base(asset.Filename) != asset.Filename || strings.ContainsAny(asset.Filename, `/\`) {
		return fmt.Errorf("invalid asset name")
	}
	req, err := http.NewRequestWithContext(ctx, "GET", client.identity.Server+"/agent/releases/"+version+"/"+asset.Filename, nil)
	if err != nil {
		return err
	}
	transport := *client.http
	transport.Timeout = 15 * time.Minute
	response, err := transport.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("release download HTTP %d", response.StatusCode)
	}
	file, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0700)
	if err != nil {
		return err
	}
	good := false
	defer func() {
		file.Close()
		if !good {
			os.Remove(destination)
		}
	}()
	hash := sha256.New()
	count, err := io.Copy(io.MultiWriter(file, hash), io.LimitReader(response.Body, asset.Size+1))
	if err != nil {
		return err
	}
	if count != asset.Size || !strings.EqualFold(hex.EncodeToString(hash.Sum(nil)), asset.SHA256) {
		return fmt.Errorf("release size/SHA-256 mismatch")
	}
	if err = file.Sync(); err != nil {
		return err
	}
	if err = file.Close(); err != nil {
		return err
	}
	good = true
	return nil
}
