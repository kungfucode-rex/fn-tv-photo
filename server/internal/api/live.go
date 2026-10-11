package api

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"tuping/internal/store"
)

// liveMessage is the one shape the danmaku layer has to understand
// (docs/tuping-design.md §6.3). A danmaku overlay that had to tell "history
// page" from "live push" apart would need two code paths; this is why it does not.
//
// It carries a comment's *state* rather than an event: the same message announces a new
// comment, an edited one, and one that has just been held, blocked or deleted. The
// client's whole rule is then "replace the copy I have by id", which is what makes a
// comment disappear from a television when somebody deletes it on the web page (§17.16).
//
// Status is a pointer because absent and 0 have to stay different things: 0 is a real
// status (待审), and `omitempty` on an int would drop it, leaving a client to read a held
// comment as a visible one. The ping carries no status at all.
type liveMessage struct {
	Type      string `json:"t"`
	ID        string `json:"id,omitempty"`
	Nickname  string `json:"nickname,omitempty"`
	Content   string `json:"content,omitempty"`
	CreatedAt int64  `json:"createdAt,omitempty"`
	Status    *int   `json:"status,omitempty"`
}

// statusOf puts a status on the wire. One helper rather than taking the address of a loop
// variable at every call site.
func statusOf(status int) *int { return &status }

// subscriber is one TV watching one photo.
type subscriber struct {
	imageID string
	ch      chan liveMessage
}

// hub fans comments out to whoever is watching that photo. It is deliberately
// per-process and non-durable: a TV that is disconnected has the HTTP history
// call to fall back on, and the history call is the same data.
type hub struct {
	mu   sync.RWMutex
	subs map[*subscriber]struct{}
}

func newHub() *hub {
	return &hub{subs: map[*subscriber]struct{}{}}
}

func (h *hub) subscribe(imageID string) *subscriber {
	// A buffered channel, and a small one: a slow TV must lose danmaku, never
	// block the HTTP handler that is writing someone's comment.
	s := &subscriber{imageID: imageID, ch: make(chan liveMessage, 32)}
	h.mu.Lock()
	h.subs[s] = struct{}{}
	h.mu.Unlock()
	return s
}

func (h *hub) unsubscribe(s *subscriber) {
	h.mu.Lock()
	delete(h.subs, s)
	h.mu.Unlock()
}

// publish delivers a message to everyone watching this photo. It never blocks:
// a subscriber that has fallen behind is skipped rather than allowed to stall the
// writer, and the next history fetch fills the gap.
func (h *hub) publish(imageID string, msg liveMessage) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for s := range h.subs {
		if s.imageID != imageID {
			continue
		}
		select {
		case s.ch <- msg:
		default:
		}
	}
}

// publishComment tells everyone watching a photo what a comment now says (§17.16).
//
// Addressed to the photo's *root* id: a television subscribes with the image id the album
// cached, which is the root of a merge group, while a comment written before a merge still
// carries the id it was written under (§3.4). Publishing to the comment's own image id
// would leave exactly those comments unheard — and this is the case where "the layer never
// hears about it" looks like the bug that was reported.
func (s *Server) publishComment(ctx context.Context, comment *store.Comment) {
	if comment == nil {
		return
	}
	imageID := comment.ImageID
	if root, err := s.store.RootID(ctx, imageID); err == nil {
		imageID = root
	}
	status := comment.Status
	s.live.publish(imageID, liveMessage{
		Type:      "comment",
		ID:        comment.ID,
		Nickname:  comment.Nickname,
		Content:   comment.Content,
		CreatedAt: comment.CreatedAt,
		Status:    statusOf(status),
	})
}

// publishCommentByID re-reads a comment and publishes its new state, for the handlers that
// have just changed it through the store and would otherwise fetch it back themselves.
//
// A comment that is not there is a comment nobody can correct, so the error is not
// reported: the socket is a convenience over the HTTP history, not a source of truth.
func (s *Server) publishCommentByID(ctx context.Context, id string) {
	comment, err := s.store.Comment(ctx, id)
	if err != nil {
		return
	}
	s.publishComment(ctx, comment)
}

// handleLive is the WebSocket endpoint the TV subscribes to.
func (s *Server) handleLive(w http.ResponseWriter, r *http.Request) {
	imageID := r.PathValue("imageId")
	if imageID == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少 imageId")
		return
	}
	if _, ok := s.requireDevice(w, r); !ok {
		return
	}

	photo, err := s.store.PhotoByID(r.Context(), imageID)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "这张照片不在图评里")
		return
	}

	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// The TV is an Android app, not a browser page, so there is no Origin to
		// check. Nothing here is authorised by a cookie either — the device token
		// rides in a header — so a cross-site request has nothing to steal.
		InsecureSkipVerify: true,
		CompressionMode:    websocket.CompressionDisabled,
	})
	if err != nil {
		s.log.Warn("websocket accept failed", "err", err)
		return
	}
	defer conn.CloseNow()

	// Nothing on this socket is useful for longer than it takes the TV to go to
	// sleep; the read limit is the only guard the server needs against a client
	// that talks too much.
	conn.SetReadLimit(1 << 12)

	ctx := r.Context()
	sub := s.live.subscribe(photo.ID)
	defer s.live.unsubscribe(sub)

	// The TV's own ping/pong is separate from this ticker: this one exists so a
	// connection through a NAT that drops idle sockets is noticed within 30s.
	go func() {
		ticker := time.NewTicker(30 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				select {
				case sub.ch <- liveMessage{Type: "ping"}:
				default:
				}
			}
		}
	}()

	// Drain the client's pings. A read error is how this loop learns the socket
	// is gone, which is also what ends the writes below.
	go func() {
		for {
			if _, _, err := conn.Read(ctx); err != nil {
				return
			}
		}
	}()

	for {
		select {
		case <-ctx.Done():
			return
		case msg := <-sub.ch:
			writeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := wsjson.Write(writeCtx, conn, msg)
			cancel()
			if err != nil {
				return
			}
		}
	}
}
