package guard

import (
	"strconv"
	"strings"
	"testing"
	"unicode/utf8"
)

func newScreener() *Screener { return New(nil, nil) }

// TestCleanCommentsPass is the floor: screening must not get in the way of the thing
// the service exists for.
func TestCleanCommentsPass(t *testing.T) {
	s := newScreener()
	for _, content := range []string{
		"这张好看",
		"a lovely photo",
		"我爷爷年轻的时候也是这样",
		"🎉🎉🎉",
		"3 分熟",
		"2026 年了还留着这张",
	} {
		if got := s.Screen(content); got.Verdict != Allowed {
			t.Errorf("%q was not allowed: %+v", content, got)
		}
	}
}

// TestEmptyIsRefused: a blank comment is a bad request, not a publication.
func TestEmptyIsRefused(t *testing.T) {
	s := newScreener()
	for _, content := range []string{"", "   ", "\n\t "} {
		got := s.Screen(content)
		if got.Verdict != Reject || got.Reason != "bad_request" {
			t.Errorf("%q -> %+v, want a bad_request refusal", content, got)
		}
	}
}

// TestLengthIsCountedInRunes is the one that would be wrong if it counted bytes: twenty
// Chinese characters is sixty bytes, and a byte-based limit would refuse a comment the
// page's own maxlength allows.
func TestLengthIsCountedInRunes(t *testing.T) {
	s := newScreener()

	atLimit := strings.Repeat("字", MaxContentRunes)
	if got := s.Screen(atLimit); got.Verdict != Allowed {
		t.Errorf("%d runes was refused: %+v", MaxContentRunes, got)
	}

	overLimit := strings.Repeat("字", MaxContentRunes+1)
	got := s.Screen(overLimit)
	if got.Verdict != Reject || got.Reason != "too_long" {
		t.Errorf("%d runes -> %+v, want too_long", MaxContentRunes+1, got)
	}
	// The message is for the person who typed it, so it has to say what the limit is — read
	// from the constant rather than written out, because a number repeated in a test is a
	// number that keeps passing after the limit moves (this one said "80" for a day).
	if !strings.Contains(got.Detail, strconv.Itoa(MaxContentRunes)) {
		t.Errorf("the refusal does not mention the limit: %q", got.Detail)
	}

	// Latin text is counted the same way.
	if got := s.Screen(strings.Repeat("a", MaxContentRunes)); got.Verdict != Allowed {
		t.Errorf("%d latin characters was refused", MaxContentRunes)
	}
}

// TestAdvertisementPatternsAreRefused is §8's explicit list. Each of these is a
// template, not a word, because a word list would have to guess at every spelling.
func TestAdvertisementPatternsAreRefused(t *testing.T) {
	s := newScreener()
	cases := map[string]string{
		// Short enough to reach the template check: the length limit runs first, and a
		// fixture longer than it proves nothing about the templates. The full-length URL
		// fixture this replaced was 27 runes, which is how the 80 -> 20 change announced
		// itself — as a `too_long` verdict in a test about advertisements.
		"url":          "看这个 http://a.cn",
		"naked-url":    "看这里 www.spam.com",
		"scheme":       "ftp://spam",
		"phone":        "联系我 13800138000",
		"wechat":       "加微信聊",
		"wechat-id":    "微信号 abc123",
		"wechat-latin": "加我 vx 12345",
		"qq":           "QQ 123456789",
		"promo":        "代购各种名牌",
		"coupon":       "这里有优惠券",
	}
	for name, content := range cases {
		if runes := utf8.RuneCountInString(content); runes > MaxContentRunes {
			t.Errorf("%s: fixture is %d runes, over the %d-rune limit", name, runes, MaxContentRunes)
			continue
		}
		got := s.Screen(content)
		if got.Verdict != Reject || got.Reason != "content_rejected" {
			t.Errorf("%s: %q -> %+v, want content_rejected", name, content, got)
		}
	}
}

// TestOrdinaryNumbersAreNotPhoneNumbers: an eleven-digit run starting with 1 is a
// phone number; a shorter one, or one that does not start with 1, is a date, a
// quantity, or somebody's photo number. Refusing those would be the filter failing
// its own users.
func TestOrdinaryNumbersAreNotPhoneNumbers(t *testing.T) {
	s := newScreener()
	for _, content := range []string{
		"这张是 2026 年拍的",
		"第 1234 张",
		"我们 5 个人",
		"编号 0123456789",   // ten digits, not eleven
		"1380013800 只有十位", // ten digits
		"23800138000",     // eleven digits but does not start with 1
	} {
		if got := s.Screen(content); got.Verdict == Reject {
			t.Errorf("%q was refused as an advertisement: %+v", content, got)
		}
	}
}

// TestPunctuationOnlyIsRefused: a comment that is only symbols has no content, and
// letting it through would let somebody fill the screen with noise one press at a time.
func TestPunctuationOnlyIsRefused(t *testing.T) {
	s := newScreener()
	got := s.Screen("。。。！！！")
	if got.Verdict != Reject {
		t.Errorf("punctuation-only was accepted: %+v", got)
	}
}

// TestOperatorsCanAddWordsAndTheTwoListsActDifferently is the whole point of having two
// lists: one refuses, the other holds for a human. Merging them would make the gentle
// list unusable.
func TestOperatorsCanAddWordsAndTheTwoListsActDifferently(t *testing.T) {
	s := New([]string{"剧透"}, []string{"违规词"})

	reviewed := s.Screen("这里有剧透")
	if reviewed.Verdict != Review {
		t.Errorf("a review word -> %+v, want Review", reviewed)
	}
	// A held comment still gets a line the page can show, because the submitter has to
	// be told it was accepted rather than refused.
	if reviewed.Detail == "" {
		t.Error("a held comment carries no message for the person who wrote it")
	}

	rejected := s.Screen("这是违规词")
	if rejected.Verdict != Reject || rejected.Reason != "content_rejected" {
		t.Errorf("a reject word -> %+v, want a content_rejected refusal", rejected)
	}
	if rejected.Matched != "违规词" {
		t.Errorf("the refusal does not name what tripped: %q", rejected.Matched)
	}
}

// TestWordsAreMatchedCaseInsensitivelyAndTrimmed: an operator writing "Spam" in a
// comma-separated environment variable means the same thing as "spam".
func TestWordsAreMatchedCaseInsensitivelyAndTrimmed(t *testing.T) {
	s := New(nil, []string{"  SPAM  ", "Nonsense"})
	for _, content := range []string{"this is spam", "SPAM here", "nonsense too"} {
		if got := s.Screen(content); got.Verdict != Reject {
			t.Errorf("%q was not caught by the operator's word list: %+v", content, got)
		}
	}
	// A blank entry is not a word that matches everything.
	if got := New(nil, []string{"", "   "}).Screen("anything at all"); got.Verdict != Allowed {
		t.Errorf("blank word-list entries refused a clean comment: %+v", got)
	}
}

// TestNormalizeNickname covers the four things a nickname can be.
func TestNormalizeNickname(t *testing.T) {
	cases := map[string]string{
		"":                      DefaultNickname,
		"   ":                   DefaultNickname,
		"\n\t":                  DefaultNickname,
		"小王":                    "小王",
		"  小王  ":                "小王",
		"小 王":                   "小 王", // inner spacing is the writer's choice
		"a\nb":                  "a b", // a newline in a nickname would break the one-line list
		strings.Repeat("字", 20): strings.Repeat("字", MaxNicknameRunes),
	}
	for input, want := range cases {
		if got := NormalizeNickname(input); got != want {
			t.Errorf("NormalizeNickname(%q) = %q, want %q", input, got, want)
		}
	}
}

// TestNormalizeNicknameStripsControlCharacters: a control character in a nickname is
// something that came off the wire, not something a person typed.
func TestNormalizeNicknameStripsControlCharacters(t *testing.T) {
	got := NormalizeNickname("小\x00王\x07")
	if strings.ContainsAny(got, "\x00\x07") {
		t.Errorf("control characters survived: %q", got)
	}
}
