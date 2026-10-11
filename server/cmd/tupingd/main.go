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

	dbPath := envOr("TUPING_DB", "/data/tuping.db")
	thumbDir := envOr("TUPING_THUMBS", "/data/thumbs")
	secretPath := envOr("TUPING_SECRET_FILE", secret.DefaultFile(dbPath))

	if *showConfig {
		baseURL := cfg.BaseURL
		if baseURL == "" {
			baseURL = "(not set: every URL uses the address the caller used)"
		}
		fmt.Printf("baseUrl      = %s\n", baseURL)
		fmt.Printf("db           = %s\n", dbPath)
		fmt.Printf("thumbs       = %s\n", thumbDir)
		fmt.Printf("secret       = %s\n", secret.Inspect(cfg.Secret, secretPath))
		fmt.Printf("adminToken   = %s\n", setOrNot(cfg.AdminToken))
		fmt.Printf("registerToken= %s\n", setOrNot(cfg.RegisterToken))
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

	logger.Info("tupingd listening",
		"addr", listener.Addr().String(),
		"baseUrl", cfg.BaseURL,
		"version", version,
		"db", dbPath,
		"thumbs", thumbDir,
		"signingKey", string(key.Source),
	)
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
		if err := httpServer.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
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
