package store

import "time"

// The tests that care about time — the five-minute duplicate window and the rate
// limit budgets — get a clock they can move instead of sleeping. It is one
// process-wide offset because Go tests in a package run sequentially unless they
// ask not to, and none of these do.
var testClock = &clock{}

type clock struct {
	offset time.Duration
}

func (c *clock) now() time.Time {
	return time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC).Add(c.offset)
}

// nowFunc is the injectable clock Open takes.
func nowFunc() func() time.Time {
	return testClock.now
}

// advance moves the test clock forward, so a minute-long window can be crossed
// without waiting a minute.
func advance(seconds int) {
	testClock.offset += time.Duration(seconds) * time.Second
}
