package main

import (
	"bufio"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

const version = "0.16.0"

func main() {
	if err := command(os.Args[1:]); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}
func command(args []string) error {
	if len(args) > 0 {
		switch args[0] {
		case "upgrade-transaction":
			return upgradeTransaction(args[1:])
		case "health-check":
			return upgradeHealth(args[1:])
		case "prepare-update":
			return prepareUpdate(args[1:])
		case "install-tools":
			return installTools(args[1:])
		case "portable-control":
			return portableControl(args[1:])
		}
	}
	if len(args) == 0 {
		return fmt.Errorf("usage: syncsrv-agent enroll|run|status|version --data-dir PATH")
	}
	if args[0] == "version" {
		fmt.Println(version, runtime.GOOS, runtime.GOARCH)
		return nil
	}
	if args[0] != "enroll" && args[0] != "run" && args[0] != "status" {
		return fmt.Errorf("unknown command %s", args[0])
	}
	flags := flag.NewFlagSet(args[0], flag.ContinueOnError)
	dir := flags.String("data-dir", "", "local persistent data directory")
	server := flags.String("server", "", "HTTPS center origin")
	name := flags.String("name", "", "device name")
	allowHTTP := flags.Bool("allow-http", false, "allow explicit private IP HTTP for LAN testing")
	tokenFile := flags.String("token-file", "", "protected file containing a one-time pairing token")
	if err := flags.Parse(args[1:]); err != nil {
		return err
	}
	if *dir == "" || !filepath.IsAbs(*dir) {
		return fmt.Errorf("--data-dir must be an absolute local path")
	}
	if err := prepareDataDirectory(*dir); err != nil {
		return err
	}
	if args[0] == "enroll" {
		return enroll(*dir, *server, *name, *tokenFile, *allowHTTP)
	}
	identity, err := loadIdentity(*dir)
	if err != nil {
		return err
	}
	client, err := newClient(identity)
	if err != nil {
		return err
	}
	if args[0] == "status" {
		var config Configuration
		if err = client.request(context.Background(), "GET", "/agent/v1/config", nil, &config, true); err != nil {
			return err
		}
		return json.NewEncoder(os.Stdout).Encode(map[string]any{"deviceId": identity.ID, "server": identity.Server, "configuration": config})
	}
	if args[0] != "run" {
		return fmt.Errorf("unknown command %s", args[0])
	}
	output, err := serviceLog(*dir)
	if err != nil {
		return err
	}
	defer func() { log.SetOutput(os.Stderr); output.Close() }()
	log.SetOutput(output)
	return runService(*dir, client)
}
func enroll(dir, server, name, tokenFile string, allowHTTP bool) error {
	server, err := validateCenter(server, allowHTTP)
	if err != nil {
		return err
	}
	if name == "" {
		name, _ = os.Hostname()
	}
	if name == "" || len(name) > 120 {
		return fmt.Errorf("device name required, max 120 characters")
	}
	identity, err := loadIdentity(dir)
	if err == nil && identity.ID != "" {
		if identity.Server != server {
			return fmt.Errorf("device already belongs to another center")
		}
		fmt.Println("Device already enrolled:", identity.ID)
		return nil
	}
	if err != nil && !os.IsNotExist(err) {
		return err
	}
	if os.IsNotExist(err) {
		_, key, e := ed25519.GenerateKey(rand.Reader)
		if e != nil {
			return e
		}
		identity = Identity{Server: server, PrivateKey: key, AllowHTTP: allowHTTP}
		if e = saveIdentity(dir, identity); e != nil {
			return e
		}
	}
	if identity.Server != server {
		return fmt.Errorf("saved enrollment identity belongs to another center")
	}
	var raw []byte
	if tokenFile != "" {
		raw, err = os.ReadFile(tokenFile)
	} else {
		fmt.Fprint(os.Stderr, "One-time pairing token: ")
		text, e := bufio.NewReader(os.Stdin).ReadString('\n')
		raw = []byte(text)
		err = e
	}
	if err != nil {
		return err
	}
	token := strings.TrimSpace(string(raw))
	if token == "" {
		return fmt.Errorf("empty pairing token")
	}
	pub, err := publicKey(identity)
	if err != nil {
		return err
	}
	proof := base64.StdEncoding.EncodeToString(ed25519.Sign(identity.PrivateKey, []byte(token+"\n"+pub+"\n"+name)))
	client, err := newClient(identity)
	if err != nil {
		return err
	}
	var response struct {
		ID     string `json:"id"`
		Status string `json:"status"`
	}
	payload := map[string]string{"token": token, "name": name, "platform": runtime.GOOS, "arch": runtime.GOARCH, "version": version, "publicKey": pub, "signature": proof}
	if err = client.request(context.Background(), "POST", "/agent/v1/enroll", payload, &response, false); err != nil {
		return err
	}
	if response.ID == "" {
		return fmt.Errorf("server did not return a device ID")
	}
	identity.ID = response.ID
	if err = saveIdentity(dir, identity); err != nil {
		return err
	}
	fmt.Println("Enrolled:", response.ID, "status:", response.Status, "— authorize the device in the center")
	return nil
}
