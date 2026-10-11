// Package guard is the first version's content safety: length, a five-minute
// duplicate window (which lives in the store), advertisement templates, and a
// local graded word list.
//
// It is deliberately honest about what it is. On a LAN this is a doorstop, not a
// moderation system; the public phase has to add 微信's msgSecCheck and a human
// queue, which is why the verdicts are graded rather than a boolean
// (docs/tuping-design.md §8).
package guard

import (
	"fmt"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Limits from the protocol.
const (
	// MaxContentRunes is how much of a comment a television can actually show.
	//
	// A danmaku line may be at most 45% of the screen wide — the ceiling that keeps the
	// longest line's crossing inside the photo's own patience (docs/tuping-design.md §9.5) —
	// and that is 864px at 1080p. The layer's largest font is 40px, so about 21 CJK
	// characters fit on a line; 20 leaves room for a wide glyph (an emoji is one rune pair
	// and more than one character's width) and for the smaller font's rounding.
	//
	// It was 80, which is three to four times what any television can draw: everything past
	// the ceiling came out as an ellipsis on screen, so the phone was inviting people to type
	// something that could not be read where it was going.
	MaxContentRunes  = 20
	MaxNicknameRunes = 12
	DefaultNickname  = "匿名"
)

// Verdict is what screening decided.
type Verdict int

const (
	// Allowed: publish it.
	Allowed Verdict = iota
	// Review: accept the submission but do not show it until a human looks.
	Review
	// Reject: refuse it and say why.
	Reject
)

// Result is a screening outcome.
type Result struct {
	Verdict Verdict
	// Reason is the client-facing error code: "too_long", "content_rejected".
	Reason string
	// Detail is the human-readable line shown above the input box. It is written
	// for the person who typed the comment, not for a log.
	Detail string
	// Matched names the word or pattern that tripped, for the server log only.
	Matched string
}

// Screener screens one comment.
type Screener struct {
	// reviewWords go to the moderation queue rather than being refused. The design
	// calls this the "graded" list: 直接拒 / 进待审.
	reviewWords []string
	// rejectWords are refused outright.
	rejectWords []string
}

// New builds a screen with the built-in advertisement templates plus whatever
// the operator added. The two lists are separate because the consequence is
// different, and merging them would make the gentle list unusable.
func New(reviewWords, rejectWords []string) *Screener {
	return &Screener{
		reviewWords: normalizeWords(reviewWords),
		rejectWords: normalizeWords(rejectWords),
	}
}

// Screen checks a comment.
func (s *Screener) Screen(content string) Result {
	content = strings.TrimSpace(content)
	if content == "" {
		return Result{Verdict: Reject, Reason: "bad_request", Detail: "说点什么吧"}
	}
	if utf8.RuneCountInString(content) > MaxContentRunes {
		return Result{
			Verdict: Reject,
			Reason:  "too_long",
			Detail:  fmt.Sprintf("最多 %d 个字，现在有 %d 个", MaxContentRunes, utf8.RuneCountInString(content)),
		}
	}
	if utf8.RuneCountInString(content) > 0 && onlyPunctuation(content) {
		return Result{Verdict: Reject, Reason: "content_rejected", Detail: "这句话没有实际内容", Matched: "punctuation"}
	}
	if m, ok := firstMatch(content, s.rejectWords); ok {
		return Result{Verdict: Reject, Reason: "content_rejected", Detail: "这句话里有不允许的内容", Matched: m}
	}
	if m, ok := firstMatch(content, s.reviewWords); ok {
		return Result{Verdict: Review, Reason: "", Detail: "已提交，审核后显示", Matched: m}
	}
	if m, ok := adPattern(content); ok {
		return Result{Verdict: Reject, Reason: "content_rejected", Detail: "不能发广告或联系方式", Matched: m}
	}
	return Result{Verdict: Allowed}
}

// NormalizeNickname trims, caps and defaults a nickname. An empty one becomes
// DefaultNickname rather than an empty bubble: the H5 page lets the field stay blank,
// and a comment with no name reads like a rendering bug.
//
// The default used to be 微信用户, which was wrong in a way that took a user to notice:
// the H5 page has no login and no WeChat anything, so a guest who left the field blank
// was told their comment came from a WeChat account they had never used. 匿名 is what the
// page actually knows about them (§17.11).
func NormalizeNickname(nickname string) string {
	nickname = strings.TrimSpace(nickname)
	nickname = strings.Map(func(r rune) rune {
		if r == '\n' || r == '\r' || r == '\t' {
			return ' '
		}
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, nickname)
	nickname = strings.Join(strings.Fields(nickname), " ")
	if nickname == "" {
		return DefaultNickname
	}
	if utf8.RuneCountInString(nickname) > MaxNicknameRunes {
		runes := []rune(nickname)
		nickname = string(runes[:MaxNicknameRunes])
	}
	return nickname
}

func normalizeWords(words []string) []string {
	out := make([]string, 0, len(words))
	for _, w := range words {
		w = strings.ToLower(strings.TrimSpace(w))
		if w != "" {
			out = append(out, w)
		}
	}
	return out
}

func firstMatch(content string, words []string) (string, bool) {
	if len(words) == 0 {
		return "", false
	}
	lower := strings.ToLower(content)
	for _, w := range words {
		if strings.Contains(lower, w) {
			return w, true
		}
	}
	return "", false
}

// adPatterns are the templates §8 names explicitly: a link, a phone number, a QQ
// number, and "add me on WeChat". They are patterns rather than words because a
// word list would have to guess at every spelling of a URL.
var adPatterns = []struct {
	name  string
	match func(string) bool
}{
	{"url", func(s string) bool {
		lower := strings.ToLower(s)
		for _, scheme := range []string{"http://", "https://", "www.", ".com", ".cn", ".net", ".xyz", "://"} {
			if strings.Contains(lower, scheme) {
				return true
			}
		}
		return false
	}},
	{"phone", func(s string) bool { return hasDigitRun(s, 11, 11, func(r rune) bool { return r == '1' }) }},
	{"phone-group", func(s string) bool { return hasDigitRun(s, 7, 7, nil) && strings.Contains(s, "-") }},
	{"qq", func(s string) bool {
		lower := strings.ToLower(s)
		for _, w := range []string{"qq", "扣扣", "企鹅号"} {
			if strings.Contains(lower, w) {
				return true
			}
		}
		return false
	}},
	{"wechat", func(s string) bool {
		for _, w := range []string{"加微信", "加我微信", "微信号", "weixin", "vx", "威信", "薇信", "加v"} {
			if strings.Contains(strings.ToLower(s), w) {
				return true
			}
		}
		return false
	}},
	{"promo", func(s string) bool {
		for _, w := range []string{"代购", "返利", "刷单", "私聊", "出售", "办理", "低价", "优惠券", "兼职", "招代理"} {
			if strings.Contains(s, w) {
				return true
			}
		}
		return false
	}},
}

func adPattern(content string) (string, bool) {
	for _, p := range adPatterns {
		if p.match(content) {
			return p.name, true
		}
	}
	return "", false
}

// hasDigitRun looks for a run of digits of the given length, optionally checking
// the run's first character.
func hasDigitRun(s string, min, max int, firstOK func(rune) bool) bool {
	run := 0
	var first rune
	reset := func() {
		run = 0
		first = 0
	}
	for _, r := range s {
		if unicode.IsDigit(r) {
			if run == 0 {
				first = r
			}
			run++
			continue
		}
		if run >= min && run <= max && (firstOK == nil || firstOK(first)) {
			return true
		}
		reset()
	}
	return run >= min && run <= max && (firstOK == nil || firstOK(first))
}

// onlyPunctuation reports whether a comment carries nothing but punctuation and
// whitespace.
//
// The definition has to be "nothing meaningful" rather than "no letters or digits",
// because the first version of this was the latter and it refused `🎉🎉🎉` as having no
// content. An emoji *is* the content — it is how a lot of people say "this is lovely" —
// so anything that is not punctuation or a space counts as something worth posting.
func onlyPunctuation(s string) bool {
	for _, r := range s {
		if isMeaningful(r) {
			return false
		}
	}
	return true
}

// isMeaningful reports whether a rune is content rather than decoration.
//
// Emoji and mathematical symbols are meaningful; punctuation and separators are not.
// `unicode.IsSymbol` covers the pictographs, which is exactly why it is here.
func isMeaningful(r rune) bool {
	switch {
	case unicode.IsLetter(r), unicode.IsDigit(r), unicode.IsNumber(r):
		return true
	case unicode.IsSymbol(r):
		// Emoji, currency signs and the like: a person typed them on purpose.
		return true
	case unicode.IsPunct(r), unicode.IsSpace(r):
		return false
	default:
		// Anything left over — a mark, a formatting character — is treated as
		// decoration, because a comment made only of those is not a comment.
		return false
	}
}
