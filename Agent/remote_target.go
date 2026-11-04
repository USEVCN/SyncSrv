package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	smb "github.com/cloudsoda/go-smb2"
	"github.com/jlaffaye/ftp"
	"github.com/pkg/sftp"
	dav "github.com/studio-b12/gowebdav"
	"golang.org/x/crypto/ssh"
)

type TargetEndpoint struct {
	ID, Name, Protocol, Host, Root, Username, Password, Domain, HostKey, CreatedAt string
	Port                                                                           int
}
type targetInfo struct {
	size                int64
	directory, symbolic bool
	modified            int64
}
type remoteTarget struct {
	stat      func(string) (targetInfo, error)
	read      func(string) (io.ReadCloser, error)
	write     func(string, io.Reader, int64, int64) error
	mkdir     func(string) error
	close     func() error
	resumable bool
}

func fileInfo(info os.FileInfo, err error) (targetInfo, error) {
	if err != nil {
		return targetInfo{}, err
	}
	return targetInfo{size: info.Size(), directory: info.IsDir(), symbolic: info.Mode()&os.ModeSymlink != 0, modified: info.ModTime().UnixMilli()}, nil
}
func (r *remoteTarget) parents(file string) error {
	current := ""
	for _, part := range strings.Split(path.Dir(file), "/") {
		if part == "." {
			continue
		}
		current = path.Join(current, part)
		info, err := r.stat(current)
		if err != nil {
			mkdirErr := r.mkdir(current)
			// Another file worker may have created this shared parent meanwhile.
			// Re-stat it even after a mkdir failure; still reject files and symlinks.
			info, err = r.stat(current)
			if err != nil && mkdirErr != nil {
				return mkdirErr
			}
		}
		if err != nil {
			return err
		}
		if !info.directory || info.symbolic {
			return fmt.Errorf("unsafe target directory")
		}
	}
	return nil
}
func openTarget(ctx context.Context, e TargetEndpoint) (*remoteTarget, error) {
	root := strings.TrimRight(strings.ReplaceAll(e.Root, "\\", "/"), "/")
	joined := func(p string) string { return root + "/" + p }
	address := func(defaultPort int) string {
		port := e.Port
		if port == 0 {
			port = defaultPort
		}
		return net.JoinHostPort(e.Host, fmt.Sprint(port))
	}
	r := &remoteTarget{}
	switch e.Protocol {
	case "SMB":
		conn, err := (&net.Dialer{Timeout: 20 * time.Second}).DialContext(ctx, "tcp", address(445))
		if err != nil {
			return nil, err
		}
		stop := context.AfterFunc(ctx, func() { conn.Close() })
		session, err := (&smb.Dialer{Negotiator: smb.Negotiator{RequireMessageSigning: true}, Initiator: &smb.NTLMInitiator{User: e.Username, Password: e.Password, Domain: e.Domain}}).DialConn(ctx, conn, address(445))
		if err != nil {
			stop()
			conn.Close()
			return nil, err
		}
		parts := strings.SplitN(strings.Trim(root, "/"), "/", 2)
		share, err := session.WithContext(ctx).Mount(parts[0])
		if err != nil {
			stop()
			conn.SetDeadline(time.Now().Add(2 * time.Second))
			session.Logoff()
			conn.Close()
			return nil, err
		}
		share = share.WithContext(ctx)
		prefix := ""
		if len(parts) > 1 {
			prefix = parts[1]
		}
		name := func(p string) string { return strings.ReplaceAll(path.Join(prefix, p), "/", "\\") }
		r.stat = func(p string) (targetInfo, error) { return fileInfo(share.Lstat(name(p))) }
		r.read = func(p string) (io.ReadCloser, error) { return share.Open(name(p)) }
		r.mkdir = func(p string) error { return share.Mkdir(name(p), 0750) }
		r.write = func(p string, in io.Reader, offset, size int64) error {
			flags := os.O_CREATE | os.O_WRONLY
			if offset == 0 {
				flags |= os.O_TRUNC
			}
			f, err := share.OpenFile(name(p), flags, 0600)
			if err != nil {
				return err
			}
			return writeRemote(f, in, offset)
		}
		r.close = func() error {
			stop()
			// LOGOFF uses the library's background context; a broken server must not
			// prevent the durable queue from retrying its failed transfer.
			conn.SetDeadline(time.Now().Add(2 * time.Second))
			share.Umount()
			session.Logoff()
			return conn.Close()
		}
		r.resumable = true
	case "SFTP":
		if e.HostKey == "" {
			return nil, fmt.Errorf("SFTP host key fingerprint required")
		}
		config := &ssh.ClientConfig{User: e.Username, Auth: []ssh.AuthMethod{ssh.Password(e.Password)}, Timeout: 20 * time.Second, HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			if strings.TrimRight(ssh.FingerprintSHA256(key), "=") != strings.TrimRight(e.HostKey, "=") {
				return fmt.Errorf("SFTP host key mismatch")
			}
			return nil
		}}
		sc, closeSFTP, err := openSFTPConnection(ctx, address(22), config)
		if err != nil {
			return nil, err
		}
		r.stat = func(p string) (targetInfo, error) { return fileInfo(sc.Lstat(joined(p))) }
		r.read = func(p string) (io.ReadCloser, error) { return sc.Open(joined(p)) }
		r.mkdir = func(p string) error { return sc.Mkdir(joined(p)) }
		r.write = func(p string, in io.Reader, offset, size int64) error {
			flags := os.O_CREATE | os.O_WRONLY
			if offset == 0 {
				flags |= os.O_TRUNC
			}
			f, err := sc.OpenFile(joined(p), flags)
			if err != nil {
				return err
			}
			return writeRemote(f, in, offset)
		}
		r.close = closeSFTP
		r.resumable = true
	case "FTP":
		options := []ftp.DialOption{ftp.DialWithContext(ctx), ftp.DialWithTimeout(30 * time.Second), ftp.DialWithShutTimeout(30 * time.Second)}
		client, err := ftp.Dial(address(21), options...)
		if err != nil {
			return nil, err
		}
		if err = client.Login(e.Username, e.Password); err != nil {
			client.Quit()
			return nil, err
		}
		stop := context.AfterFunc(ctx, func() { client.Quit() })
		r.stat = func(p string) (targetInfo, error) {
			entry, err := client.GetEntry(joined(p))
			if err != nil {
				return targetInfo{}, err
			}
			return targetInfo{size: int64(entry.Size), directory: entry.Type == ftp.EntryTypeFolder, symbolic: entry.Type == ftp.EntryTypeLink, modified: entry.Time.UnixMilli()}, nil
		}
		r.read = func(p string) (io.ReadCloser, error) { return client.Retr(joined(p)) }
		r.mkdir = func(p string) error { return client.MakeDir(joined(p)) }
		r.write = func(p string, in io.Reader, offset, size int64) error {
			return client.StorFrom(joined(p), in, uint64(offset))
		}
		// REST support varies. A failed upload is safely restarted for FTP.
		r.close = func() error { stop(); return client.Quit() }
	case "WEBDAV":
		base, err := url.Parse(e.Host)
		if err != nil || base.Hostname() == "" || (base.Scheme != "http" && base.Scheme != "https") || base.User != nil || base.RawQuery != "" || base.Fragment != "" {
			return nil, fmt.Errorf("invalid WebDAV target origin")
		}
		if e.Port > 0 {
			base.Host = net.JoinHostPort(base.Hostname(), fmt.Sprint(e.Port))
		}
		base.Path = strings.TrimRight(base.Path, "/") + "/" + strings.TrimLeft(root, "/")
		base.RawPath = ""
		// The auto-auth mode buffers non-seekable uploads for retries. Use the same
		// preemptive Basic authentication as the center, keeping large uploads streaming.
		client := dav.NewAuthClient(base.String(), dav.NewPreemptiveAuth(&webDAVAuth{e.Username, e.Password}))
		transport := http.DefaultTransport.(*http.Transport).Clone()
		transport.ResponseHeaderTimeout = 30 * time.Second
		client.SetTransport(contextTransport{ctx, transport})
		client.SetHeader(dav.XInhibitRedirect, "1")
		r.stat = func(p string) (targetInfo, error) { return fileInfo(client.Stat(p)) }
		r.read = client.ReadStream
		r.mkdir = func(p string) error { return client.Mkdir(p, 0750) }
		r.write = func(p string, in io.Reader, offset, size int64) error {
			if offset != 0 {
				return fmt.Errorf("WebDAV requires whole-object upload")
			}
			return client.WriteStreamWithLength(p, in, size, 0600)
		}
		r.close = func() error { transport.CloseIdleConnections(); return nil }
	case "LOCAL":
		// Useful when Agent and center run on the same host; remote tasks must select a network endpoint.
		base, err := os.OpenRoot(e.Root)
		if err != nil {
			return nil, err
		}
		r.stat = func(p string) (targetInfo, error) {
			if err := safeLocalTarget(base, p); err != nil {
				return targetInfo{}, err
			}
			if p == "" {
				p = "."
			}
			return fileInfo(base.Lstat(p))
		}
		r.read = func(p string) (io.ReadCloser, error) {
			if err := safeLocalTarget(base, p); err != nil {
				return nil, err
			}
			return base.Open(p)
		}
		r.mkdir = func(p string) error {
			if err := safeLocalTarget(base, p); err != nil {
				return err
			}
			return base.Mkdir(p, 0750)
		}
		r.write = func(p string, in io.Reader, offset, size int64) error {
			flags := os.O_CREATE | os.O_WRONLY
			if offset == 0 {
				flags |= os.O_TRUNC
			}
			if err := safeLocalTarget(base, p); err != nil {
				return err
			}
			f, err := base.OpenFile(p, flags, 0600)
			if err != nil {
				return err
			}
			return writeRemote(f, in, offset)
		}
		r.close = base.Close
		r.resumable = true
	default:
		return nil, fmt.Errorf("unsupported direct target protocol: %s", e.Protocol)
	}
	info, err := r.stat("")
	if err != nil || !info.directory || info.symbolic {
		r.close()
		return nil, fmt.Errorf("direct target root unavailable: %v", err)
	}
	return r, nil
}

// ClientConfig.Timeout only applies to ssh.Dial. This path dials explicitly so
// cancellation can close the socket; bound SSH and SFTP handshakes ourselves.
func openSFTPConnection(ctx context.Context, address string, config *ssh.ClientConfig) (*sftp.Client, func() error, error) {
	timeout := config.Timeout
	if timeout <= 0 {
		timeout = 20 * time.Second
	}
	conn, err := (&net.Dialer{Timeout: timeout}).DialContext(ctx, "tcp", address)
	if err != nil {
		return nil, nil, err
	}
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	cleanup := func() { stop(); _ = conn.Close() }
	if err = conn.SetDeadline(time.Now().Add(timeout)); err != nil {
		cleanup()
		return nil, nil, err
	}
	raw, chans, reqs, err := ssh.NewClientConn(conn, address, config)
	if err != nil {
		cleanup()
		return nil, nil, fmt.Errorf("SFTP SSH handshake: %w", err)
	}
	client := ssh.NewClient(raw, chans, reqs)
	sc, err := sftp.NewClient(client)
	if err != nil {
		cleanup()
		_ = client.Close()
		return nil, nil, fmt.Errorf("SFTP subsystem handshake: %w", err)
	}
	if err = conn.SetDeadline(time.Time{}); err != nil {
		cleanup()
		_ = sc.Close()
		_ = client.Close()
		return nil, nil, err
	}
	closeConnection := func() error {
		stop()
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		_ = sc.Close()
		return client.Close()
	}
	return sc, closeConnection, nil
}

type seekWriteCloser interface {
	io.Writer
	io.Seeker
	io.Closer
}

func writeRemote(f seekWriteCloser, in io.Reader, offset int64) error {
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		f.Close()
		return err
	}
	_, err := copyRemote(f, in)
	if err == nil {
		if durable, ok := f.(interface{ Sync() error }); ok {
			err = durable.Sync()
		}
	}
	closeErr := f.Close()
	if err != nil {
		return err
	}
	return closeErr
}

type contextTransport struct {
	ctx       context.Context
	transport http.RoundTripper
}

func (t contextTransport) RoundTrip(r *http.Request) (*http.Response, error) {
	return t.transport.RoundTrip(r.WithContext(t.ctx))
}

// Credentials are attached only to the configured origin; redirects are disabled.
type webDAVAuth struct{ user, password string }

func (a *webDAVAuth) Authorize(_ *http.Client, r *http.Request, _ string) error {
	if a.user != "" {
		r.SetBasicAuth(a.user, a.password)
	}
	return nil
}
func (a *webDAVAuth) Verify(_ *http.Client, r *http.Response, _ string) (bool, error) {
	if r.StatusCode == http.StatusUnauthorized {
		return false, fmt.Errorf("WebDAV authentication failed")
	}
	return false, nil
}
func (a *webDAVAuth) Clone() dav.Authenticator { return a }
func (a *webDAVAuth) Close() error             { return nil }

func safeLocalTarget(root *os.Root, p string) error {
	if p == "" {
		return nil
	}
	if !safeStoragePath(p) {
		return fmt.Errorf("unsafe local storage path")
	}
	current := ""
	for _, part := range strings.Split(p, "/") {
		current = filepath.Join(current, part)
		info, err := root.Lstat(current)
		if os.IsNotExist(err) {
			return nil
		}
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 || (!info.IsDir() && !info.Mode().IsRegular()) {
			return fmt.Errorf("unsupported local storage entry")
		}
	}
	return nil
}
