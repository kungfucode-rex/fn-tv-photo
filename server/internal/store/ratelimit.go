package store

import (
	"context"
	"fmt"
	"time"
)

// RateLimit is one budget: at most Limit hits per Window.
type RateLimit struct {
	Key    string
	Limit  int
	Window time.Duration
}

// The first-version budgets from docs/tuping-design.md §7.
var (
	LimitPerAnon    = RateLimit{Limit: 5, Window: time.Minute}
	LimitPerAnonDay = RateLimit{Limit: 50, Window: 24 * time.Hour}
	LimitPerIP      = RateLimit{Limit: 30, Window: time.Minute}
	LimitPerPhoto   = RateLimit{Limit: 200, Window: time.Hour}
	LimitPerDevice  = RateLimit{Limit: 300, Window: time.Hour}

	// LimitPerService is the comment endpoint's own budget, shared by every caller.
	//
	// Every budget above is per caller or per photo, and each of them can be escaped by
	// arriving as somebody new: another guest cookie, another address, another picture.
	// This one cannot, which is the whole of why it exists — "the comment endpoint accepts
	// at most 120 a minute" is a statement about the deployment rather than about one
	// visitor, and a public deployment is where that matters. It is deliberately the
	// loosest of the limits, because it is also the only one that can refuse a caller who
	// has done nothing wrong: 2 a second across everybody is more than a household or a
	// party will produce, and the per-caller budgets are what catch the single flooder.
	LimitPerService = RateLimit{Limit: 120, Window: time.Minute}
)

// Allow consumes one unit of a budget and reports whether it was there.
//
// The counter lives in SQLite rather than in memory so a restart does not hand
// everyone a fresh allowance, and so the limit is the same whichever handler
// happens to ask. A fixed window is deliberate: it is one row, one statement,
// and a sliding window buys nothing at these sizes.
func (s *Store) Allow(ctx context.Context, rl RateLimit) (bool, error) {
	if rl.Limit <= 0 {
		return true, nil
	}
	now := s.nowMS()
	window := rl.Window.Milliseconds()
	if window <= 0 {
		window = time.Minute.Milliseconds()
	}
	windowStart := now - (now % window)

	// The window column decides which generation a row belongs to, so the
	// increment and the reset are one statement and there is no read-then-write.
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO rate_limits (key, window_start, count) VALUES (?,?,1)
		ON CONFLICT(key) DO UPDATE SET
			count = CASE WHEN rate_limits.window_start = excluded.window_start
			             THEN rate_limits.count + 1 ELSE 1 END,
			window_start = excluded.window_start`,
		rl.Key, windowStart)
	if err != nil {
		return false, fmt.Errorf("store: rate limit: %w", err)
	}

	var count int
	if err := s.db.QueryRowContext(ctx,
		`SELECT count FROM rate_limits WHERE key=?`, rl.Key).Scan(&count); err != nil {
		return false, fmt.Errorf("store: rate limit read: %w", err)
	}
	return count <= rl.Limit, nil
}

// AllowAll consumes every budget and reports the first that ran out.
func (s *Store) AllowAll(ctx context.Context, limits ...RateLimit) (bool, string, error) {
	for _, rl := range limits {
		if rl.Key == "" {
			continue
		}
		ok, err := s.Allow(ctx, rl)
		if err != nil {
			return false, "", err
		}
		if !ok {
			return false, rl.Key, nil
		}
	}
	return true, "", nil
}

// SweepRateLimits drops windows nobody will look at again.
func (s *Store) SweepRateLimits(ctx context.Context, olderThan time.Duration) (int64, error) {
	cutoff := s.nowMS() - olderThan.Milliseconds()
	res, err := s.db.ExecContext(ctx, `DELETE FROM rate_limits WHERE window_start < ?`, cutoff)
	if err != nil {
		return 0, fmt.Errorf("store: sweep rate limits: %w", err)
	}
	return res.RowsAffected()
}
