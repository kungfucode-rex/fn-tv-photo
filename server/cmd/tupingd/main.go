// Command tupingd is the 图评 server: the comment service a photo album hangs a
// QR code and a danmaku layer onto.
//
// It is one static binary with no runtime and no cgo, so it can be cross-built on
// Windows and copied to a NAS (docs/tuping-design.md §11). Everything it needs to
// be configured is an environment variable, because that is what a NAS container
// panel can actually set.
package main

import (
	"context"
	"crypto/tls"
	"errors"
	"flag"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"tuping/internal/api"
	"tuping/internal/qr"
	"tuping/internal/secret"
	"tuping/internal/store"
	"tuping/internal/thumbs"
)

// version is injected at build time:
//
//	go build -ldflags "-X main.version=$(git describe --tags)"
//
// The album and the server carry separate version numbers on purpose and are not
// meant to be aligned (docs/tuping-design.md §11.2).
var version = "dev"

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "tupingd:", err)
		os.Exit(1)
	}
}

func run() error {
	var (
		addr        = flag.String("addr", envOr("TUPING_ADDR", ":8080"), "address to listen on")
		showVersion = flag.Bool("version", false, "print the version and exit")
		showConfig  = flag.Bool("check", false, "validate configuration and exit")
		showHealth  = flag.Bool("health", false, "ask this server's own address for /healthz and exit non-zero if it does not answer")
	)
	flag.Parse()

	if *showVersion {
		fmt.Println(version)
		return nil
	}

	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	cfg, err := configFromEnv()
	if err != nil {
		return err
	}
	cfg.Version = version

	// The certificate is the deployer's to obtain and renew; this process only has to serve
	// it, to notice a renewal without being restarted, and to say plainly when the two files
	// it was handed are not a pair that works (§17.23). Read here rather than at the listener
	// so that `-check` and `-health` cover it too — those are the two commands a deployer runs
	// on the NAS, and both need to know which scheme this deployment speaks.
	certFile, keyFile, err := tlsFilesFromEnv()
	if err != nil {
		return err
	}
	if *showHealth {
		return selfCheck(*addr, certFile != "")
	}
	var certs *certReloader
	if certFile != "" {
		if certs, err = newCertReloader(certFile, keyFile, logger); err != nil {
			return err
		}
	}
	cfg.TLS = certs != nil

	dbPath := envOr("TUPING_DB", "/data/tuping.db")
	thumbDir := envOr("TUPING_THUMBS", "/data/thumbs")
	secretPath := envOr("TUPING_SECRET_FILE", secret.DefaultFile(dbPath))

	if *showConfig {
		baseURL := cfg.BaseURL
		if baseURL == "" {
			baseURL = "(not set: every URL uses the address the caller used)"
		}
		fmt.Printf("baseUrl      = %s\n", baseURL)
		fmt.Printf("tls          = %s\n", tlsSummary(certs))
		fmt.Printf("db           = %s\n", dbPath)
		fmt.Printf("thumbs       = %s\n", thumbDir)
		fmt.Printf("secret       = %s\n", secret.Inspect(cfg.Secret, secretPath))
		fmt.Printf("adminToken   = %s\n", setOrNot(cfg.AdminToken))
		fmt.Printf("registerToken= %s\n", setOrNot(cfg.RegisterToken))
		if msg := pinnedSchemeMismatch(cfg, certs != nil); msg != "" {
			fmt.Printf("warning      = %s\n", msg)
		}
		return nil
	}

	// Inside the container this process starts as root and gives that up here, after taking
	// ownership of the directories it has to write into. A bind mount arrives owned by
	// whoever the host says — Docker creates a missing one as root — and that is never the
	// uid this server serves traffic as (docs/tuping-design.md §17.21).
	preparePrivileges(logger, writableDirs(dbPath, thumbDir))

	st, err := store.Open(store.Options{Path: dbPath})
	if err != nil {
		return err
	}
	defer st.Close()

	// The signing key is supplied, already on disk, or minted here. Resolved after
	// the store so that the first write lands in a data directory that exists, and
	// kept out of configFromEnv so that `-check` never creates anything
	// (docs/tuping-design.md §17.19).
	key, err := secret.Resolve(cfg.Secret, secretPath)
	if err != nil {
		return err
	}
	cfg.Secret = key.Value
	if key.Source == secret.SourceNew {
		logger.Warn("minted a new signing key; every admin session and every live thumbnail URL is now invalid",
			"path", key.Path)
	}

	thumbStore, err := thumbs.Open(thumbDir)
	if err != nil {
		return err
	}
	defer thumbStore.Flush()

	srv, err := api.New(api.Options{
		Config: cfg,
		Store:  st,
		Thumbs: thumbStore,
		Logger: logger,
		QR:     qr.New(512),
	})
	if err != nil {
		return err
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	srv.Run(ctx)

	httpServer := &http.Server{
		Addr:    *addr,
		Handler: srv.Handler(),
		// No read timeout: a registration uploads a thumbnail, and a phone on a
		// weak link may take a while over it. The header timeout is what actually
		// protects against a half-open socket.
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       90 * time.Second,
		BaseContext:       func(net.Listener) context.Context { return ctx },
	}

	listener, err := net.Listen("tcp", httpServer.Addr)
	if err != nil {
		return fmt.Errorf("listen on %s: %w", httpServer.Addr, err)
	}

	if certs != nil {
		// ServeTLS is called below with no file names on purpose: the pair is already here,
		// and GetCertificate is what re-reads it (§17.23). This is also the call that turns on
		// HTTP/2 for the guests' phones, which is free and is why the listener is configured
		// this way rather than by wrapping it in tls.NewListener by hand.
		httpServer.TLSConfig = &tls.Config{
			GetCertificate: certs.GetCertificate,
			MinVersion:     tls.VersionTLS12,
		}
	}

	listening := []any{
		"addr", listener.Addr().String(),
		"baseUrl", cfg.BaseURL,
		"version", version,
		"db", dbPath,
		"thumbs", thumbDir,
		"signingKey", string(key.Source),
		"tls", certs != nil,
	}
	if certs != nil {
		listening = append(listening, "cert", certFile, "certNotAfter", certs.notAfter().Format(time.RFC3339))
	}
	logger.Info("tupingd listening", listening...)
	if certs != nil {
		// The one certificate problem a deployer cannot see from inside the container: it is
		// on a timer they set up months ago, and the timer stopped. An expired certificate is
		// not a subtle failure — every browser refuses it — but nothing else in this process
		// would mention it until somebody's phone did.
		switch left := time.Until(certs.notAfter()); {
		case left < 0:
			logger.Error("the certificate has expired: browsers will refuse it until it is replaced",
				"notAfter", certs.notAfter().Format(time.RFC3339))
		case left < 14*24*time.Hour:
			logger.Warn("the certificate expires soon; check whatever is supposed to renew it",
				"notAfter", certs.notAfter().Format(time.RFC3339), "in", left.Round(time.Hour).String())
		}
	}
	if msg := pinnedSchemeMismatch(cfg, certs != nil); msg != "" {
		logger.Warn(msg, "baseUrl", cfg.BaseURL)
	}
	if cfg.BaseURL == "" {
		// Not a warning: this is the default shape since §17.20, and the address a phone
		// needs is one only the caller knows. Said once at startup so that a deployer
		// reading the log is not left wondering why nothing is configured.
		logger.Info("TUPING_BASE_URL is not set: every QR code and link uses the address its caller used")
	} else if isLoopbackBaseURL(cfg.BaseURL) {
		// This is the one configuration mistake that produces a symptom nobody can
		// act on: a QR code that no phone can open, on a TV that looks fine. Say
		// it loudly at start-up, where the operator is still looking.
		logger.Warn("TUPING_BASE_URL points at localhost: phones will not be able to open the QR code",
			"baseUrl", cfg.BaseURL)
	}

	errCh := make(chan error, 1)
	go func() {
		var err error
		if certs != nil {
			err = httpServer.ServeTLS(listener, "", "")
		} else {
			err = httpServer.Serve(listener)
		}
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			errCh <- err
		}
	}()

	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}

	logger.Info("shutting down")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return httpServer.Shutdown(shutdownCtx)
}

func configFromEnv() (api.Config, error) {
	cfg := api.Config{
		BaseURL:                strings.TrimSpace(os.Getenv("TUPING_BASE_URL")),
		Secret:                 os.Getenv("TUPING_SECRET"),
		AdminToken:             os.Getenv("TUPING_ADMIN_TOKEN"),
		RegisterToken:          os.Getenv("TUPING_REGISTER_TOKEN"),
		ReviewWords:            os.Getenv("TUPING_REVIEW_WORDS"),
		RejectWords:            os.Getenv("TUPING_REJECT_WORDS"),
		ThumbTTL:               durationOr(os.Getenv("TUPING_THUMB_TTL"), 30*24*time.Hour),
		DeviceRegistrationOpen: boolOr(os.Getenv("TUPING_OPEN_REGISTRATION"), true),
	}
	// Optional since §17.20. A QR code needs an address a phone can open, and the only party
	// that knows which of this server's addresses that is, is whoever just connected — the
	// registering device reports its own, and a request that arrives without one is answered
	// with the address it arrived on. Setting this pins an address for the callers that
	// cannot be believed, which is why it is still accepted.
	if cfg.BaseURL != "" &&
		!strings.HasPrefix(cfg.BaseURL, "http://") &&
		!strings.HasPrefix(cfg.BaseURL, "https://") {
		return cfg, fmt.Errorf("TUPING_BASE_URL must start with http:// or https:// (got %q)", cfg.BaseURL)
	}
	return cfg, nil
}

func envOr(name, def string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return def
}

func durationOr(value string, def time.Duration) time.Duration {
	if value == "" {
		return def
	}
	d, err := time.ParseDuration(value)
	if err != nil {
		return def
	}
	return d
}

func boolOr(value string, def bool) bool {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "1", "true", "yes", "on":
		return true
	case "0", "false", "no", "off":
		return false
	default:
		return def
	}
}

func setOrNot(v string) string {
	if v == "" {
		return "(not set)"
	}
	return "(set)"
}

// tlsSummary is what `-check` prints for the transport: the two files, who they were issued
// to, and when they stop working.
func tlsSummary(certs *certReloader) string {
	if certs == nil {
		return "off (plain http)"
	}
	return certs.describe()
}

// pinnedSchemeMismatch names the one combination that produces dead links: this process speaks
// https, so nothing answers on http any more, and every QR code is nevertheless built from an
// http:// pin. Unlike the private-address warning (§11.5) there is no deployment in which this
// is deliberate — the reverse case, a TLS-terminating proxy in front of a *plain* tupingd, is
// a perfectly good shape and is deliberately not warned about.
func pinnedSchemeMismatch(cfg api.Config, tls bool) string {
	if !tls || !strings.HasPrefix(cfg.BaseURL, "http://") {
		return ""
	}
	return fmt.Sprintf("TUPING_BASE_URL is %s but this server speaks https, so every QR code and every link "+
		"points at an address nothing answers on; make it https://, or drop it and let the address come from "+
		"whoever is connecting", cfg.BaseURL)
}

// isLoopbackBaseURL spots the configuration mistake §11.5 calls out by name.
func isLoopbackBaseURL(base string) bool {
	trimmed := strings.TrimPrefix(strings.TrimPrefix(base, "http://"), "https://")
	host := trimmed
	if i := strings.IndexAny(host, ":/"); i >= 0 {
		host = host[:i]
	}
	switch strings.ToLower(host) {
	case "localhost", "127.0.0.1", "::1", "0.0.0.0", "":
		return true
	}
	return false
}
