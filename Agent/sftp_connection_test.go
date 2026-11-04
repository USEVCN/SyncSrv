package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"golang.org/x/crypto/ssh"
	"net"
	"testing"
	"time"
)

func TestSFTPServerAcceptingTCPButNotSSHDoesNotHangAgent(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	release := make(chan struct{})
	defer close(release)
	go func() {
		conn, err := listener.Accept()
		if err == nil {
			defer conn.Close()
			<-release
		}
	}()
	started := time.Now()
	_, _, err = openSFTPConnection(context.Background(), listener.Addr().String(), &ssh.ClientConfig{User: "fixture", Timeout: 40 * time.Millisecond, HostKeyCallback: func(string, net.Addr, ssh.PublicKey) error { return nil }})
	if err == nil {
		t.Fatal("unresponsive SSH server connected")
	}
	if elapsed := time.Since(started); elapsed > time.Second {
		t.Fatalf("SSH handshake ignored timeout: %s", elapsed)
	}
	var timeout net.Error
	if !errors.As(err, &timeout) || !timeout.Timeout() {
		t.Fatalf("unexpected failure: %v", err)
	}
}

func TestSFTPSubsystemHandshakeHasDeadline(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	release := make(chan struct{})
	defer close(release)
	_, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	signer, err := ssh.NewSignerFromKey(private)
	if err != nil {
		t.Fatal(err)
	}
	config := &ssh.ServerConfig{NoClientAuth: true}
	config.AddHostKey(signer)
	accepted := make(chan struct{}, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		server, channels, requests, err := ssh.NewServerConn(conn, config)
		if err != nil {
			return
		}
		defer server.Close()
		go ssh.DiscardRequests(requests)
		for incoming := range channels {
			channel, requests, err := incoming.Accept()
			if err != nil {
				return
			}
			defer channel.Close()
			for request := range requests {
				if request.Type == "subsystem" {
					request.Reply(true, nil)
					accepted <- struct{}{}
					<-release // Accept SFTP but never return its protocol version.
					return
				}
				request.Reply(false, nil)
			}
		}
	}()
	started := time.Now()
	_, _, err = openSFTPConnection(context.Background(), listener.Addr().String(), &ssh.ClientConfig{User: "fixture", Timeout: 200 * time.Millisecond, HostKeyCallback: func(string, net.Addr, ssh.PublicKey) error { return nil }})
	if err == nil || time.Since(started) > time.Second {
		t.Fatalf("SFTP subsystem handshake ignored deadline: %v", err)
	}
	select {
	case <-accepted:
	default:
		t.Fatal("fixture never reached the SFTP subsystem handshake")
	}
}
