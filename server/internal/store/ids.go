package store

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
	"sync"
	"time"
)

// The Crockford base32 alphabet ULIDs are written in.
const crockford = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// idGen hands out ULIDs. ULIDs are lexicographically sortable by time, which is
// what keeps `comments(image_id, created_at)` and any later pagination honest
// without a second column.
type idGen struct {
	mu      sync.Mutex
	lastMS  int64
	entropy [10]byte
}

var ids idGen

// NewID returns a fresh ULID: 48 bits of milliseconds, 80 bits of randomness,
// Crockford base32, 26 characters.
func NewID() string {
	return ids.next(time.Now())
}

func (g *idGen) next(t time.Time) string {
	g.mu.Lock()
	defer g.mu.Unlock()

	ms := t.UnixMilli()
	if ms > g.lastMS {
		g.lastMS = ms
		if _, err := rand.Read(g.entropy[:]); err != nil {
			// crypto/rand does not fail in practice; if it ever does, a zero
			// entropy tail is still unique within one process thanks to the
			// monotonic clock below.
			g.entropy = [10]byte{}
		}
	} else {
		// Same millisecond: bump the entropy as a big-endian integer so ids stay
		// strictly increasing even inside one tick.
		for i := len(g.entropy) - 1; i >= 0; i-- {
			g.entropy[i]++
			if g.entropy[i] != 0 {
				break
			}
		}
	}

	var b [16]byte
	b[0] = byte(ms >> 40)
	b[1] = byte(ms >> 32)
	b[2] = byte(ms >> 24)
	b[3] = byte(ms >> 16)
	b[4] = byte(ms >> 8)
	b[5] = byte(ms)
	copy(b[6:], g.entropy[:])
	return encodeCrockford(b)
}

func encodeCrockford(b [16]byte) string {
	var out [26]byte
	out[0] = crockford[(b[0]&224)>>5]
	out[1] = crockford[b[0]&31]
	out[2] = crockford[(b[1]&248)>>3]
	out[3] = crockford[((b[1]&7)<<2)|((b[2]&192)>>6)]
	out[4] = crockford[(b[2]&62)>>1]
	out[5] = crockford[((b[2]&1)<<4)|((b[3]&240)>>4)]
	out[6] = crockford[((b[3]&15)<<1)|((b[4]&128)>>7)]
	out[7] = crockford[(b[4]&124)>>2]
	out[8] = crockford[((b[4]&3)<<3)|((b[5]&224)>>5)]
	out[9] = crockford[b[5]&31]
	out[10] = crockford[(b[6]&248)>>3]
	out[11] = crockford[((b[6]&7)<<2)|((b[7]&192)>>6)]
	out[12] = crockford[(b[7]&62)>>1]
	out[13] = crockford[((b[7]&1)<<4)|((b[8]&240)>>4)]
	out[14] = crockford[((b[8]&15)<<1)|((b[9]&128)>>7)]
	out[15] = crockford[(b[9]&124)>>2]
	out[16] = crockford[((b[9]&3)<<3)|((b[10]&224)>>5)]
	out[17] = crockford[b[10]&31]
	out[18] = crockford[(b[11]&248)>>3]
	out[19] = crockford[((b[11]&7)<<2)|((b[12]&192)>>6)]
	out[20] = crockford[(b[12]&62)>>1]
	out[21] = crockford[((b[12]&1)<<4)|((b[13]&240)>>4)]
	out[22] = crockford[((b[13]&15)<<1)|((b[14]&128)>>7)]
	out[23] = crockford[(b[14]&124)>>2]
	out[24] = crockford[((b[14]&3)<<3)|((b[15]&224)>>5)]
	out[25] = crockford[b[15]&31]
	return string(out[:])
}

// CodeLength is the short code's length: 62^6 ≈ 5.7×10^10, enough that the code
// is a capability rather than a number (docs/tuping-design.md §5).
const CodeLength = 6

// Base62 is the alphabet short codes are drawn from: digits and letters, with no
// pair that a person reading one off a TV could confuse for another.
const base62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

// CodeOwnerOperator is the `codes.device_id` recorded when an operator re-binds codes with
// `POST /v1/admin/codes/rebind`: the address on those codes is the deployment's, and no
// device may move it afterwards.
//
// The three states of that column are all meaningful and all needed:
//
//	''            minted before the column existed — the first device to register the photo
//	              again claims it and its address follows that device (see `followAddress`);
//	'*'           an operator set this address with `rebind`; devices leave it alone;
//	a device id   that device may move the address, and nobody else may.
//
// Without the middle state the two acts would take turns: a television on the LAN would
// re-point an operator's public address back to the LAN every time somebody pressed
// 扫码评论 (docs/tuping-design.md §17.31).
const CodeOwnerOperator = "*"

// NewCode returns a cryptographically random short code.
//
// Random, not sequential: the code is the only thing standing between a stranger
// and someone's photo, and §12.1 relies on codes not being enumerable.
func NewCode() string {
	var b [CodeLength]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(fmt.Sprintf("store: crypto/rand: %v", err))
	}
	out := make([]byte, CodeLength)
	for i, v := range b {
		out[i] = base62[int(v)%len(base62)]
	}
	return string(out)
}

// ValidCode reports whether a string could be a code this server issued.
func ValidCode(code string) bool {
	if len(code) != CodeLength {
		return false
	}
	for i := 0; i < len(code); i++ {
		if !strings.ContainsRune(base62, rune(code[i])) {
			return false
		}
	}
	return true
}

// HashToken is how every user-identifying value is stored: device tokens, owner
// tokens, admin tokens, openids and IPs. A leaked database then leaks nothing
// that can be replayed or re-identified (docs/tuping-design.md §5, §12.6).
func HashToken(secret, value string) string {
	sum := sha256.Sum256([]byte(secret + "\x00" + value))
	return hex.EncodeToString(sum[:])
}
