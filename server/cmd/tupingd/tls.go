package main

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"log/slog"
	"os"
	"strings"
	"sync"
	"time"
)

// The certificate pair, when the deployer wants this process to terminate TLS itself
// (docs/tuping-design.md §17.23). Obtaining and renewing that certificate is deliberately not
// this program's job: a NAS panel can set two environment variables and mount two files, and
// that is the whole contract.
const (
	tlsCertEnv = "TUPING_TLS_CERT"
	tlsKeyEnv  = "TUPING_TLS_KEY"
)

// tlsFilesFromEnv returns the certificate pair to serve with, or two empty strings for the
// plain-http default.
//
// Half a pair is refused here rather than at the first handshake. The failure this prevents is
// the quiet one: with only TUPING_TLS_KEY set, a server that came up in cleartext looks
// exactly like a deployment whose certificate works, on every screen in the house — and the
// admin token crosses the LAN in the clear while the person who set it believes otherwise.
func tlsFilesFromEnv() (cert, key string, err error) {
	cert = strings.TrimSpace(os.Getenv(tlsCertEnv))
	key = strings.TrimSpace(os.Getenv(tlsKeyEnv))
	switch {
	case cert == "" && key == "":
		return "", "", nil
	case cert == "":
		return "", "", fmt.Errorf("%s is set but %s is not: set both, or neither (neither serves plain http)", tlsKeyEnv, tlsCertEnv)
	case key == "":
		return "", "", fmt.Errorf("%s is set but %s is not: set both, or neither (neither serves plain http)", tlsCertEnv, tlsKeyEnv)
	}
	return cert, key, nil
}

// certReloader serves the pair from disk, and picks up a replacement without a restart.
//
// Why not ServeTLS(listener, certFile, keyFile): that reads both files once, at startup. A
// certificate from an ACME client is renewed by a timer, unattended, every couple of months —
// the file on disk changes while this process keeps serving the old one, and the symptom then
// arrives on a date that has nothing to do with anything anyone did: a browser warning on the
// guests' phones, with a container that looks perfectly healthy from the NAS panel. Re-reading
// the pair is the whole feature, and it costs two small file reads a minute.
//
// The failure direction is deliberate. A replacement that does not parse leaves the previous
// certificate in place and logs it, rather than refusing every handshake: a botched renewal
// must not take the service down, and the old certificate is still valid until its own expiry.
type certReloader struct {
	certPath string
	keyPath  string
	log      *slog.Logger

	// checkInterval is how long a handshake trusts the cache before looking at the files
	// again. Zero in tests, where the reload itself is what is under test.
	checkInterval time.Duration

	mu        sync.Mutex
	cert      *tls.Certificate
	certPEM   []byte
	keyPEM    []byte
	nextCheck time.Time
}

// newCertReloader reads the pair once, so that a path that is wrong, a file that is
// unreadable, or two files that do not belong together all fail at startup — where the
// deployer is watching the log — instead of at somebody's first scan.
func newCertReloader(certPath, keyPath string, log *slog.Logger) (*certReloader, error) {
	if log == nil {
		log = slog.Default()
	}
	r := &certReloader{
		certPath:      certPath,
		keyPath:       keyPath,
		log:           log,
		checkInterval: time.Minute,
	}
	cert, certPEM, keyPEM, err := r.load()
	if err != nil {
		return nil, err
	}
	r.cert, r.certPEM, r.keyPEM = cert, certPEM, keyPEM
	return r, nil
}

// GetCertificate is the tls.Config hook. It never returns an error: see the type comment.
func (r *certReloader) GetCertificate(*tls.ClientHelloInfo) (*tls.Certificate, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if time.Now().Before(r.nextCheck) {
		return r.cert, nil
	}
	r.nextCheck = time.Now().Add(r.checkInterval)

	cert, certPEM, keyPEM, err := r.load()
	if err != nil {
		r.log.Error("the certificate on disk could not be loaded; still serving the one already in memory",
			"err", err)
		return r.cert, nil
	}
	if bytes.Equal(certPEM, r.certPEM) && bytes.Equal(keyPEM, r.keyPEM) {
		return r.cert, nil
	}
	r.cert, r.certPEM, r.keyPEM = cert, certPEM, keyPEM
	r.log.Info("reloaded the certificate",
		"subject", subjectOf(cert), "notAfter", notAfterOf(cert).Format(time.RFC3339))
	return r.cert, nil
}

// load reads and parses the pair. The environment variable names are in the error because that
// is what the person reading the log has in front of them, not the path they already typed, and
// so is the way out: this failure stops the container (deliberately — see the type comment), and
// the operator meets it as a restart loop in a NAS panel with no shell open.
func (r *certReloader) load() (*tls.Certificate, []byte, []byte, error) {
	certPEM, err := os.ReadFile(r.certPath)
	if err != nil {
		return nil, nil, nil, fmt.Errorf(
			"%s: %w (mount the certificate at that path, readable by uid 10001, or unset %s and %s to serve plain http)",
			tlsCertEnv, err, tlsCertEnv, tlsKeyEnv)
	}
	keyPEM, err := os.ReadFile(r.keyPath)
	if err != nil {
		return nil, nil, nil, fmt.Errorf(
			"%s: %w (mount the private key at that path, readable by uid 10001, or unset %s and %s to serve plain http)",
			tlsKeyEnv, err, tlsCertEnv, tlsKeyEnv)
	}
	pair, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("%s (%s) and %s (%s) do not form a certificate/key pair: %w",
			tlsCertEnv, r.certPath, tlsKeyEnv, r.keyPath, err)
	}
	return &pair, certPEM, keyPEM, nil
}

// describe is the one line `-check` prints and the startup log carries: which files, for whom,
// until when. The date is the field that turns "the certificate is fine, I set it up months
// ago" into something a person can compare with today.
func (r *certReloader) describe() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return fmt.Sprintf("on (cert=%s key=%s subject=%q notAfter=%s)",
		r.certPath, r.keyPath, subjectOf(r.cert), notAfterOf(r.cert).Format(time.RFC3339))
}

// notAfter is when the certificate in memory stops being accepted, or the zero time when that
// cannot be read out of it.
func (r *certReloader) notAfter() time.Time {
	r.mu.Lock()
	defer r.mu.Unlock()
	return notAfterOf(r.cert)
}

func leafOf(cert *tls.Certificate) *x509.Certificate {
	if cert == nil {
		return nil
	}
	if cert.Leaf != nil {
		return cert.Leaf
	}
	if len(cert.Certificate) > 0 {
		if parsed, err := x509.ParseCertificate(cert.Certificate[0]); err == nil {
			return parsed
		}
	}
	return nil
}

func subjectOf(cert *tls.Certificate) string {
	if leaf := leafOf(cert); leaf != nil {
		return leaf.Subject.CommonName
	}
	return ""
}

func notAfterOf(cert *tls.Certificate) time.Time {
	if leaf := leafOf(cert); leaf != nil {
		return leaf.NotAfter
	}
	return time.Time{}
}
