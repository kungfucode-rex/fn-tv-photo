package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestProbeURL pins the translation from a listen address to something a client can dial. The
// image's default is ":8080", which is not an address at all, and the process running this is
// on loopback by definition.
func TestProbeURL(t *testing.T) {
	cases := []struct {
		name   string
		addr   string
		useTLS bool
		want   string
	}{
		{"the container default", ":8080", false, "http://127.0.0.1:8080/healthz"},
		{"every interface", "0.0.0.0:8080", false, "http://127.0.0.1:8080/healthz"},
		{"every interface over TLS", "0.0.0.0:8443", true, "https://127.0.0.1:8443/healthz"},
		{"every interface, ipv6", "[::]:8080", false, "http://127.0.0.1:8080/healthz"},
		{"a chosen address is dialled as it is", "192.168.31.14:8080", false, "http://192.168.31.14:8080/healthz"},
		{"loopback with a certificate", "127.0.0.1:8443", true, "https://127.0.0.1:8443/healthz"},
		{"ipv6 loopback", "[::1]:8443", true, "https://[::1]:8443/healthz"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := probeURL(tc.addr, tc.useTLS); got != tc.want {
				t.Errorf("probeURL(%q, %v) = %q, want %q", tc.addr, tc.useTLS, got, tc.want)
			}
		})
	}
}

// TestSelfCheckAgainstARealServer is the probe doing its job both ways round: plain http and
// TLS, where the certificate is self-signed and therefore deliberately not verified. A probe
// that insisted on a trusted chain would report every https deployment unhealthy.
func TestSelfCheckAgainstARealServer(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/healthz" {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusOK)
	})

	plain := httptest.NewServer(handler)
	defer plain.Close()
	if err := selfCheck(strings.TrimPrefix(plain.URL, "http://"), false); err != nil {
		t.Errorf("plain http: %v", err)
	}
	// The same server, asked over TLS: it is not one, and saying so is the point of the probe.
	if err := selfCheck(strings.TrimPrefix(plain.URL, "http://"), true); err == nil {
		t.Error("a plain-http listener was reported healthy by the https probe")
	}

	secure := httptest.NewTLSServer(handler)
	defer secure.Close()
	if err := selfCheck(strings.TrimPrefix(secure.URL, "https://"), true); err != nil {
		t.Errorf("https: %v", err)
	}
}

// TestDeploymentHealthChecksUseTheProbe is the guard on a line that only fails in one delivery
// shape: a health check that can only speak http reports a container with a certificate as
// unhealthy forever, while the service itself is fine — and nothing else in this repository
// would notice. Every task that ships a container gets checked, because the Dockerfile and the
// two compose files are three places the same line lives (the precedent is
// TestDockerfileAgreesWithDataUID).
func TestDeploymentHealthChecksUseTheProbe(t *testing.T) {
	files := []string{
		filepath.Join("..", "..", "Dockerfile"),
		filepath.Join("..", "..", "docker-compose.yml"),
		filepath.Join("..", "..", "docker-compose.image.yml"),
	}
	for _, path := range files {
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read %s: %v", path, err)
		}
		text := string(raw)
		if !strings.Contains(text, "tupingd") || !strings.Contains(text, "-health") {
			t.Errorf("%s has no health check that asks the binary itself, so an https deployment reads as unhealthy forever", filepath.Base(path))
			continue
		}
		// The other half of the same rule: asking wget to speak TLS to 127.0.0.1 cannot work,
		// because no real certificate is issued for a loopback address.
		if strings.Contains(text, "wget") && strings.Contains(text, "https://127.0.0.1") {
			t.Errorf("%s probes https with wget; a certificate for a real domain is never valid for 127.0.0.1", filepath.Base(path))
		}
	}
}

// TestSelfCheckRefusesAnAnswerThatIsNotAHealthDocument: the probe is on loopback, but a port
// that something else has taken still must not read as healthy.
func TestSelfCheckRefusesAnAnswerThatIsNotAHealthDocument(t *testing.T) {
	notOurs := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer notOurs.Close()

	if err := selfCheck(strings.TrimPrefix(notOurs.URL, "http://"), false); err == nil {
		t.Error("a 404 was reported healthy")
	}
}
