package main

import (
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"strings"
	"time"
)

// selfCheck is what `-health` does: one request to the address this process would be serving
// on, over the scheme it would be serving with, and a non-zero exit when nothing answers.
//
// It exists because the container health check has to be right in both shapes without knowing
// which one it is in. The image has no curl, busybox wget's TLS support is a build-time
// question, and a real certificate is never issued for 127.0.0.1 — so the probe is this binary
// asking itself, with the same environment the server reads (docs/tuping-design.md §17.23).
func selfCheck(addr string, useTLS bool) error {
	target := probeURL(addr, useTLS)
	client := &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			// The certificate belongs to the domain the guests use, not to loopback, and the
			// question here is "is anything serving at all" — whether the chain is trusted is
			// the guests' phones' question, and they answer it every time one scans.
			TLSClientConfig:   &tls.Config{InsecureSkipVerify: useTLS},
			DisableKeepAlives: true,
		},
	}
	resp, err := client.Get(target)
	if err != nil {
		return fmt.Errorf("no answer from %s: %w", target, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%s answered %d, want 200", target, resp.StatusCode)
	}
	return nil
}

// probeURL turns a listen address into something a client can dial: ":8080" and "0.0.0.0:8080"
// are not addresses, and the process running this is on loopback by definition.
func probeURL(addr string, useTLS bool) string {
	scheme := "http"
	if useTLS {
		scheme = "https"
	}
	addr = strings.TrimSpace(addr)
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		// Not host:port at all. Hand the whole string to the client and let it produce the
		// error, which names what was actually configured.
		return scheme + "://" + addr + "/healthz"
	}
	switch host {
	case "", "0.0.0.0", "::":
		host = "127.0.0.1"
	}
	return scheme + "://" + net.JoinHostPort(host, port) + "/healthz"
}
