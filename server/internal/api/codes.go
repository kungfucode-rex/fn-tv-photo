package api

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
)

/*
The one admin operation that is not about a comment (docs/tuping-design.md §17.27).

A short code records the address the registering television used to reach this server, and
that address is what the QR picture encodes. It is deliberately frozen at registration:
a guest may already be holding a printed or screenshotted link, and re-pointing it behind
their back is worse than a dead one — `store.ensureCode` says so, and the answer to "we have
moved" used to be "delete the rows and re-register every photo", which means either
handwritten SQL or losing every comment with them.

This is the supported version of that operation, and it is deliberately *not* reachable from
the album end:

  - **It is a deployment-level act.** The television cannot know that the public address
    changed — it is still reaching the server on the same one it always did, or on a new one
    it was just reconfigured for — and a client that could rewrite the address on every code
    would be a client that could hand every guest somebody else's hostname.
  - **It takes the admin token**, the same door as the moderation endpoints, so it is
    available to the person who deployed this and to nobody else.
  - **It changes nothing but the address.** No code is re-minted, no photo, thumbnail or
    comment is touched, so a rebind cannot lose a thread and cannot orphan a device mapping.

What it cannot do on its own is make a television that is *already running* redraw its code:
the app caches one QR picture per code, so the new address reaches the screen when that
photo is registered again (the reply's PNG is fetched fresh — see `ReviewRepository.fetchQr`).
That is why the response carries the sentence the operator needs rather than only a count.
*/

// maxRebindBody bounds the request body. The only field is an address, whose own ceiling is
// `maxBaseURLLen`; the rest is room for the JSON around it.
const maxRebindBody = 4 << 10

// rebindRequest is the body, all of it.
type rebindRequest struct {
	// BaseURL is where every code should point now. Empty means "clear what they recorded",
	// which hands each code back to the deployment's own configuration — see the note on
	// `store.RebindCodes` for why both forms are wanted.
	BaseURL string `json:"baseUrl"`
}

// handleAdminRebindCodes is POST /v1/admin/codes/rebind.
func (s *Server) handleAdminRebindCodes(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}

	raw, ok := s.rebindAddress(w, r)
	if !ok {
		return
	}

	// "" means clear; anything else has to be an address a phone could open, validated by
	// the same rule a device's own report goes through. A rebind to "localhost" or to a
	// half-typed address would put a dead link into every QR code on the instance, which is
	// the failure this whole endpoint exists to get *out* of.
	addr := ""
	if raw != "" {
		addr = normalizeBaseURL(raw)
		if addr == "" {
			writeError(w, http.StatusBadRequest, "bad_request",
				"baseUrl 必须是 http/https 地址，比如 http://106.52.237.179:53347；"+
					"留空表示清掉短码上记的地址，改用 TUPING_BASE_URL 或调用者的地址。")
			return
		}
	}

	updated, total, err := s.store.RebindCodes(r.Context(), addr)
	if err != nil {
		s.serverError(w, err)
		return
	}
	s.log.Info("codes rebound", "updated", updated, "total", total, "baseUrl", addr)

	body := map[string]any{
		"updated": updated,
		"total":   total,
		"baseUrl": addr,
		// The half of this operation that lives on the television. Said in the response
		// because the caller is standing at a shell, and a count of 217 changed rows looks
		// like success while the code on the wall still opens the old address.
		"next": "在电视上重启一次 App：每张照片重新注册时会抓一张新的二维码，也就换成了新地址。" +
			"已经在内存里的照片不会自己重画。",
	}
	// The same warning the deployment gets at startup and on /healthz, for the same mistake:
	// a private address is fine, a private address behind a television that reaches this
	// server from somewhere else is a code no guest's phone can open (§11.5, §17.20). The
	// rebind is the one moment an operator deliberately chooses that address for codes that
	// already exist, so it is the moment the sentence is worth repeating.
	if addr != "" && hostIsPrivate(addr) {
		body["warning"] = "这个地址只有同一网络里的手机能打开：公网上的客人扫到的是一个打不开的链接。"
		s.log.Warn("codes rebound to a private address", "baseUrl", addr)
	}
	writeJSON(w, http.StatusOK, body)
}

// rebindAddress reads the address from wherever the caller put it, and answers the error
// itself when the body is unreadable.
//
// Three spellings, because this is a command somebody types by hand and the failure mode of
// being strict is a 400 that looks like a wrong token: `?baseUrl=…`, a JSON body, or a form
// body. The body is sniffed rather than trusted to `Content-Type`, since `curl -d '{"…"}'`
// sends `application/x-www-form-urlencoded` for JSON without being asked to.
func (s *Server) rebindAddress(w http.ResponseWriter, r *http.Request) (string, bool) {
	if raw := strings.TrimSpace(r.URL.Query().Get("baseUrl")); raw != "" {
		return raw, true
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, maxRebindBody))
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "请求体读不出来")
		return "", false
	}
	body = bytes.TrimSpace(body)
	if len(body) == 0 {
		// No body at all is the "clear every code" spelling: the pin or the caller's own
		// address takes over, which is what a deployment behind a proxy that rewrites Host
		// wants anyway.
		return "", true
	}

	if body[0] == '{' {
		var payload rebindRequest
		if err := json.Unmarshal(body, &payload); err != nil {
			writeError(w, http.StatusBadRequest, "bad_request", "请求体不是合法 JSON")
			return "", false
		}
		return strings.TrimSpace(payload.BaseURL), true
	}

	values, err := url.ParseQuery(string(body))
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "请求体不是合法表单")
		return "", false
	}
	return strings.TrimSpace(values.Get("baseUrl")), true
}
