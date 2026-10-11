package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"io"
	"log/slog"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// discardLogger keeps the reload lines out of the test output. The behaviour they report is
// asserted through the served certificate, not through the log.
func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// TestTLSFilesFromEnv is the pair rule: both variables, or neither.
//
// Half a pair is the failure this rule exists for — a server that came up in cleartext while
// the deployer believes TLS is on looks exactly like a working certificate on every screen in
// the house.
func TestTLSFilesFromEnv(t *testing.T) {
	cases := []struct {
		name    string
		cert    string
		key     string
		wantOn  bool
		wantErr string
	}{
		{name: "neither is the plain http default"},
		{name: "both", cert: "/certs/fullchain.pem", key: "/certs/privkey.pem", wantOn: true},
		{name: "whitespace is not a value", cert: "  ", key: "\n", wantOn: false},
		{name: "cert without key", cert: "/certs/fullchain.pem", wantErr: tlsKeyEnv},
		{name: "key without cert", key: "/certs/privkey.pem", wantErr: tlsCertEnv},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv(tlsCertEnv, tc.cert)
			t.Setenv(tlsKeyEnv, tc.key)

			cert, key, err := tlsFilesFromEnv()
			switch {
			case tc.wantErr != "":
				if err == nil {
					t.Fatalf("got cert=%q key=%q and no error, want one naming %s", cert, key, tc.wantErr)
				}
				if !strings.Contains(err.Error(), tc.wantErr) {
					t.Errorf("error %q does not name %s, which is the variable the deployer has to add", err, tc.wantErr)
				}
			case err != nil:
				t.Fatalf("unexpected error: %v", err)
			case tc.wantOn && (cert != tc.cert || key != tc.key):
				t.Errorf("got cert=%q key=%q, want %q and %q", cert, key, tc.cert, tc.key)
			case !tc.wantOn && (cert != "" || key != ""):
				t.Errorf("got cert=%q key=%q, want both empty", cert, key)
			}
		})
	}
}

// TestCertReloaderServesAndPicksUpAReplacement is the whole point of serving the pair through
// a hook instead of handing two file names to ServeTLS: an ACME renewal replaces the files
// while the process keeps running, and a restart nobody knows to perform is not a renewal
// strategy.
func TestCertReloaderServesAndPicksUpAReplacement(t *testing.T) {
	dir := t.TempDir()
	certPath := filepath.Join(dir, "fullchain.pem")
	keyPath := filepath.Join(dir, "privkey.pem")
	writeSelfSigned(t, certPath, keyPath, "first.example")

	r, err := newCertReloader(certPath, keyPath, discardLogger())
	if err != nil {
		t.Fatalf("newCertReloader: %v", err)
	}
	// A minute is right in production and useless in a test.
	r.checkInterval = 0

	if got := handshakeSubject(t, r); got != "first.example" {
		t.Fatalf("the listener served %q, want the certificate on disk", got)
	}
	if line := r.describe(); !strings.Contains(line, certPath) || !strings.Contains(line, "first.example") {
		t.Errorf("describe() = %q, want it to name the file and the subject", line)
	}

	// A renewal that produced something unreadable must not take a working service down: the
	// old certificate is still valid until its own expiry, so it stays.
	if err := os.WriteFile(certPath, []byte("this is not a certificate\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := handshakeSubject(t, r); got != "first.example" {
		t.Errorf("a botched renewal took the working certificate down: serving %q", got)
	}

	// The real thing: the same two paths, a new pair.
	writeSelfSigned(t, certPath, keyPath, "second.example")
	if got := handshakeSubject(t, r); got != "second.example" {
		t.Errorf("still serving %q after the files were replaced, want second.example", got)
	}
}

// TestNewCertReloaderFailsAtStartup: a bad path or two files that are not a pair has to stop
// the process where the deployer is reading the log, not at somebody's first scan in another
// room.
func TestNewCertReloaderFailsAtStartup(t *testing.T) {
	dir := t.TempDir()

	if _, err := newCertReloader(filepath.Join(dir, "missing.pem"), filepath.Join(dir, "missing.key"), discardLogger()); err == nil {
		t.Error("a missing certificate file did not stop the server")
	} else if !strings.Contains(err.Error(), tlsCertEnv) {
		t.Errorf("error %q does not name %s", err, tlsCertEnv)
	}

	certPath := filepath.Join(dir, "one.pem")
	otherKey := filepath.Join(dir, "other.key")
	writeSelfSigned(t, certPath, filepath.Join(dir, "one.key"), "one.example")
	writeSelfSigned(t, filepath.Join(dir, "two.pem"), otherKey, "two.example")

	_, err := newCertReloader(certPath, otherKey, discardLogger())
	if err == nil {
		t.Fatal("a certificate and a key that do not belong together were accepted")
	}
	if !strings.Contains(err.Error(), "pair") {
		t.Errorf("error %q does not say the two files are not a pair", err)
	}
}

// handshakeSubject dials a real TLS listener whose certificate comes from the reloader, which
// is how the server uses it. Calling GetCertificate directly would prove the reload logic and
// not the wiring, and the wiring is the half that fails silently: a certificate nothing asks
// for is a server still serving its old one.
func handshakeSubject(t *testing.T, r *certReloader) string {
	t.Helper()

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	defer ln.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		_ = tls.Server(conn, &tls.Config{
			GetCertificate: r.GetCertificate,
			MinVersion:     tls.VersionTLS12,
		}).Handshake()
	}()

	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 5 * time.Second}, "tcp", ln.Addr().String(),
		&tls.Config{InsecureSkipVerify: true}) // the certificate under test is self-signed
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	<-done

	peers := conn.ConnectionState().PeerCertificates
	if len(peers) == 0 {
		t.Fatal("the handshake carried no certificate")
	}
	return peers[0].Subject.CommonName
}

// writeSelfSigned puts a fresh self-signed server certificate at the two given paths.
func writeSelfSigned(t *testing.T, certPath, keyPath, name string) {
	t.Helper()

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: name},
		DNSNames:     []string{name},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(90 * 24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		t.Fatalf("marshal key: %v", err)
	}

	writePEM(t, certPath, "CERTIFICATE", der)
	writePEM(t, keyPath, "EC PRIVATE KEY", keyDER)
}

func writePEM(t *testing.T, path, blockType string, der []byte) {
	t.Helper()
	file, err := os.Create(path)
	if err != nil {
		t.Fatalf("create %s: %v", path, err)
	}
	defer file.Close()
	if err := pem.Encode(file, &pem.Block{Type: blockType, Bytes: der}); err != nil {
		t.Fatalf("encode %s: %v", path, err)
	}
}
