package api

import (
	"context"
	"time"
)

// sweepLoop is the retention job §12.4 asks for: a thumbnail nobody has read for
// thirty days goes, and the row stops pointing at it.
//
// It runs hourly rather than continuously because it exists to bound disk use,
// not to keep anything in step — and it deletes only what the access index proves
// is cold, never everything older than a date.
func (s *Server) sweepLoop(ctx context.Context) {
	ticker := time.NewTicker(time.Hour)
	defer ticker.Stop()

	// Once at start-up: a NAS that was switched off for a month comes back with a
	// month of cold thumbnails, and waiting an hour to reclaim them is silly.
	s.sweepRun(ctx)

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			s.sweepRun(ctx)
		}
	}
}

func (s *Server) sweepRun(ctx context.Context) {
	if s.thumbs == nil {
		return
	}

	// The access index is the authority on what is cold, so the sweep is driven
	// from it rather than from thumb_expire_at: a thumbnail that is read every day
	// must never expire no matter when it was uploaded.
	expired := s.thumbs.Expired(time.Now())
	for _, key := range expired {
		if err := s.store.ClearThumbKey(ctx, key); err != nil {
			s.log.Warn("clear thumb key failed", "key", key, "err", err)
		}
	}
	if n, err := s.thumbs.Sweep(time.Now()); err != nil {
		s.log.Warn("thumbnail sweep failed", "err", err)
	} else if n > 0 {
		s.log.Info("thumbnails expired", "count", n)
	}

	if _, err := s.store.SweepRateLimits(ctx, 48*time.Hour); err != nil {
		s.log.Warn("rate limit sweep failed", "err", err)
	}
}
