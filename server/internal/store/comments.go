package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// Comment is one row of comments, as the clients see it.
type Comment struct {
	ID          string `json:"id"`
	ImageID     string `json:"imageId"`
	Scope       Scope  `json:"-"`
	ScopeID     string `json:"-"`
	Content     string `json:"content"`
	Nickname    string `json:"nickname"`
	AvatarURL   string `json:"avatarUrl,omitempty"`
	Status      int    `json:"status"`
	ReportCount int    `json:"reportCount,omitempty"`
	Client      string `json:"-"`
	CreatedAt   int64  `json:"createdAt"`
}

// NewComment is one POST /v1/comments, already screened and length-checked.
type NewComment struct {
	ImageID    string
	Scope      Scope
	ScopeID    string
	Content    string
	Nickname   string
	AvatarURL  string
	OpenIDHMAC string
	AnonID     string
	IPHMAC     string
	Client     string
	IdemKey    string

	// Status is what the comment starts as. The default is visible; content
	// screening raises it to pending, and a locked photo never gets this far.
	Status int
}

// AddComment inserts a comment and keeps the photo's counter in step.
//
// It is idempotent on (image_id, idem_key): a submit button pressed twice, or a
// phone that retried a request it never saw the answer to, produces one comment
// and returns that same comment the second time (§5).
func (s *Store) AddComment(ctx context.Context, in NewComment) (*Comment, bool, error) {
	status := in.Status
	switch in.Status {
	case StatusPending, StatusBlocked, StatusDeleted:
		// The caller named a status, so keep it.
	default:
		// 0 is the zero value *and* a valid status (待审), so a caller that did not
		// choose cannot be told apart from one that chose 待审 by the number alone.
		// Every path in this server passes an explicit status; anything else means
		// "publish it", which is what a comment is by default.
		status = StatusVisible
	}
	in.Status = status

	// As in Register: an unset scope would be a thread nothing can find.
	if in.Scope != ScopeEvent {
		in.Scope = ScopeImage
	}

	// Same photo, same text, inside five minutes is a double tap, not two people
	// who happen to agree (docs/tuping-design.md §8).
	dup, err := s.recentDuplicate(ctx, in)
	if err != nil {
		return nil, false, err
	}
	if dup != nil {
		return dup, false, nil
	}

	id := NewID()
	now := s.nowMS()
	_, execErr := s.db.ExecContext(ctx, `
		INSERT INTO comments (
			id, image_id, scope_type, scope_id, content, nickname, avatar_url,
			openid_hmac, anon_id, status, report_count, ip_hmac, client, idem_key, created_at
		) VALUES (?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)`,
		id, in.ImageID, int(in.Scope), nullableString(in.ScopeID), in.Content, in.Nickname,
		nullableString(in.AvatarURL), nullableString(in.OpenIDHMAC), nullableString(in.AnonID),
		in.Status, nullableString(in.IPHMAC), nullableString(in.Client), nullableString(in.IdemKey), now)
	if execErr != nil {
		if isUniqueViolation(execErr) && in.IdemKey != "" {
			existing, err := s.commentByIdem(ctx, in.ImageID, in.IdemKey)
			if err != nil {
				return nil, false, err
			}
			return existing, false, nil
		}
		return nil, false, fmt.Errorf("store: insert comment: %w", execErr)
	}

	if in.Status == StatusVisible {
		if err := s.refreshCommentCount(ctx, in.ImageID); err != nil {
			return nil, false, err
		}
	}

	c := &Comment{
		ID: id, ImageID: in.ImageID, Scope: in.Scope, ScopeID: in.ScopeID,
		Content: in.Content, Nickname: in.Nickname, AvatarURL: in.AvatarURL,
		Status: in.Status, Client: in.Client, CreatedAt: now,
	}
	return c, true, nil
}

func (s *Store) commentByIdem(ctx context.Context, imageID, idemKey string) (*Comment, error) {
	return s.scanComment(ctx, `SELECT `+commentColumns+` FROM comments WHERE image_id=? AND idem_key=?`,
		imageID, idemKey)
}

// recentDuplicate finds the same text on the same photo inside the dedup window.
//
// "No duplicate" is a normal answer, not an error: reporting ErrNotFound here
// would make every first-ever comment on a photo fail, which is precisely the bug
// this comment is here to stop anyone reintroducing.
func (s *Store) recentDuplicate(ctx context.Context, in NewComment) (*Comment, error) {
	since := s.nowMS() - 5*60*1000
	dup, err := s.scanComment(ctx, `SELECT `+commentColumns+` FROM comments
		WHERE image_id=? AND content=? AND status IN (?,?) AND created_at >= ?
		ORDER BY created_at DESC LIMIT 1`,
		in.ImageID, in.Content, StatusVisible, StatusPending, since)
	if errors.Is(err, ErrNotFound) {
		return nil, nil
	}
	return dup, err
}

// Comment reads one comment back, by its own id.
func (s *Store) Comment(ctx context.Context, id string) (*Comment, error) {
	return s.scanComment(ctx, `SELECT `+commentColumns+` FROM comments WHERE id=?`, id)
}

const commentColumns = `id, image_id, scope_type, COALESCE(scope_id,''), content, nickname,
	COALESCE(avatar_url,''), status, report_count, COALESCE(client,''), created_at`

func (s *Store) scanComment(ctx context.Context, query string, args ...any) (*Comment, error) {
	var c Comment
	err := s.db.QueryRowContext(ctx, query, args...).Scan(
		&c.ID, &c.ImageID, &c.Scope, &c.ScopeID, &c.Content, &c.Nickname,
		&c.AvatarURL, &c.Status, &c.ReportCount, &c.Client, &c.CreatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("store: scan comment: %w", err)
	}
	return &c, nil
}

// CommentQuery is a page of comments.
type CommentQuery struct {
	// ImageID is the root id; the whole merge group is included, so comments
	// written before a merge are still delivered after it.
	ImageID string
	Since   int64
	Limit   int

	// IncludeHidden adds blocked and deleted rows, for the moderation queue.
	IncludeHidden bool
}

// Comments lists comments oldest first, which is the order they are played in.
func (s *Store) Comments(ctx context.Context, q CommentQuery) ([]Comment, error) {
	group, err := s.GroupIDs(ctx, q.ImageID)
	if err != nil {
		return nil, err
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(group)), ",")
	args := make([]any, 0, len(group)+4)
	for _, g := range group {
		args = append(args, g)
	}

	query := `SELECT ` + commentColumns + ` FROM comments WHERE image_id IN (` + placeholders + `)`
	if q.Since > 0 {
		query += ` AND created_at > ?`
		args = append(args, q.Since)
	}
	if !q.IncludeHidden {
		query += ` AND status = ?`
		args = append(args, StatusVisible)
	}
	query += ` ORDER BY created_at ASC, id ASC`

	limit := q.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	query += ` LIMIT ?`
	args = append(args, limit)

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("store: list comments: %w", err)
	}
	defer rows.Close()

	var out []Comment
	for rows.Next() {
		var c Comment
		if err := rows.Scan(
			&c.ID, &c.ImageID, &c.Scope, &c.ScopeID, &c.Content, &c.Nickname,
			&c.AvatarURL, &c.Status, &c.ReportCount, &c.Client, &c.CreatedAt); err != nil {
			return nil, fmt.Errorf("store: scan comment row: %w", err)
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// RecentComments returns the newest n comments for a photo, newest last.
func (s *Store) RecentComments(ctx context.Context, imageID string, n int) ([]Comment, error) {
	group, err := s.GroupIDs(ctx, imageID)
	if err != nil {
		return nil, err
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(group)), ",")
	args := make([]any, 0, len(group)+2)
	for _, g := range group {
		args = append(args, g)
	}
	args = append(args, StatusVisible, n)

	rows, err := s.db.QueryContext(ctx, `SELECT `+commentColumns+` FROM comments
		WHERE image_id IN (`+placeholders+`) AND status = ?
		ORDER BY created_at DESC, id DESC LIMIT ?`, args...)
	if err != nil {
		return nil, fmt.Errorf("store: recent comments: %w", err)
	}
	defer rows.Close()

	var rev []Comment
	for rows.Next() {
		var c Comment
		if err := rows.Scan(
			&c.ID, &c.ImageID, &c.Scope, &c.ScopeID, &c.Content, &c.Nickname,
			&c.AvatarURL, &c.Status, &c.ReportCount, &c.Client, &c.CreatedAt); err != nil {
			return nil, err
		}
		rev = append(rev, c)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// Back to oldest-first so the caller has one order to think about.
	for i, j := 0, len(rev)-1; i < j; i, j = i+1, j-1 {
		rev[i], rev[j] = rev[j], rev[i]
	}
	return rev, nil
}

// ReportComment records a report and moves a comment to the review queue once it
// has collected ReportThreshold of them (§10.1).
const ReportThreshold = 3

func (s *Store) ReportComment(ctx context.Context, id string) (int, int, error) {
	res, err := s.db.ExecContext(ctx,
		`UPDATE comments SET report_count = report_count + 1,
			status = CASE WHEN report_count + 1 >= ? AND status = ? THEN ? ELSE status END
		 WHERE id = ?`, ReportThreshold, StatusVisible, StatusPending, id)
	if err != nil {
		return 0, 0, fmt.Errorf("store: report: %w", err)
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return 0, 0, ErrNotFound
	}
	c, err := s.Comment(ctx, id)
	if err != nil {
		return 0, 0, err
	}
	if c.Status != StatusVisible {
		if err := s.refreshCommentCount(ctx, c.ImageID); err != nil {
			return 0, 0, err
		}
	}
	return c.ReportCount, c.Status, nil
}

// SetCommentStatus is the moderator's decision.
func (s *Store) SetCommentStatus(ctx context.Context, id string, status int) error {
	res, err := s.db.ExecContext(ctx, `UPDATE comments SET status=? WHERE id=?`, status, id)
	if err != nil {
		return fmt.Errorf("store: set status: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	c, err := s.Comment(ctx, id)
	if err != nil {
		return err
	}
	return s.refreshCommentCount(ctx, c.ImageID)
}

// SetCommentContent is the moderator's edit: the same comment, different words.
//
// Nothing else moves with it. The idempotency key stays as it was minted, because it
// belongs to the original submission — rewriting it would let the edited text be
// inserted a second time — and report_count is left alone, because editing a comment
// does not un-report what the people who saw it already said.
func (s *Store) SetCommentContent(ctx context.Context, id, content, nickname string) error {
	res, err := s.db.ExecContext(ctx,
		`UPDATE comments SET content=?, nickname=? WHERE id=?`, content, nickname, id)
	if err != nil {
		return fmt.Errorf("store: set comment content: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ModerationQueue lists comments awaiting a decision, oldest first.
func (s *Store) ModerationQueue(ctx context.Context, limit int) ([]Comment, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx, `SELECT `+commentColumns+` FROM comments
		WHERE status IN (?,?) ORDER BY created_at ASC LIMIT ?`, StatusPending, StatusBlocked, limit)
	if err != nil {
		return nil, fmt.Errorf("store: moderation queue: %w", err)
	}
	defer rows.Close()

	var out []Comment
	for rows.Next() {
		var c Comment
		if err := rows.Scan(
			&c.ID, &c.ImageID, &c.Scope, &c.ScopeID, &c.Content, &c.Nickname,
			&c.AvatarURL, &c.Status, &c.ReportCount, &c.Client, &c.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// CountComments is used by the retention report and by tests.
func (s *Store) CountComments(ctx context.Context, imageID string) (int, error) {
	p, err := s.PhotoByID(ctx, imageID)
	if err != nil {
		return 0, err
	}
	return p.CommentCount, nil
}

func isUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "UNIQUE constraint failed") || strings.Contains(msg, "constraint failed")
}
