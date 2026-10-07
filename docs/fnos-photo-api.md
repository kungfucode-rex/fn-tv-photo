# fnOS photo API — reverse-engineered protocol notes

These notes describe the private fnOS (飞牛) photo-gallery protocol that **FN Photo**
speaks, and record how each part was verified. Nothing here is officially
documented; it was reconstructed from working clients and then checked against a
mock server that actually enforces the rules.

> Source material: [`ljmljz/fnphoto-tv`](https://github.com/ljmljz/fnphoto-tv)
> (MIT) and [`HelloWangXiangDong/fnAlbum`](https://github.com/HelloWangXiangDong/fnAlbum)
> (MIT, `docs/PROTOCOL.md`). The two disagree in places; where they do, the
> disagreement and the resolution are called out below.
>
> Neither reference client covers shared albums, so §5c comes from a HAR capture of
> the fnOS web frontend against a live NAS, plus that frontend's own `shareApi`
> bundle. That is a different class of evidence from the rest of this file: it is the
> server's real answer rather than a working client's reconstruction.

## 1. Two legs, both required

| Leg | Purpose | Auth |
| --- | --- | --- |
| WebSocket encrypted login | obtain an `AccessToken` | RSA public key + AES-256-CBC |
| HTTP business API (`/p` prefix) | timeline, albums, folders, media | `AccessToken` + `authx` signature |

There is no documented refresh flow. Every client re-runs the full login when the
token is rejected, so credentials must be persisted.

## 2. WebSocket login

Connect to `ws://<host>:5666/websocket?type=main` (fnOS serves HTTP on 5666 and
HTTPS on 5667 by default).

1. **Ask for the public key**

   ```json
   {"req":"util.crypto.getRSAPub","reqid":"<28 hex chars>"}
   ```

   Reply: `{"pub":"-----BEGIN PUBLIC KEY-----…","si":"72057984125699406","result":"succ"}`

   `pub` is **X.509** (`SubjectPublicKeyInfo`), so it loads directly through
   `X509EncodedKeySpec`. A PKCS#1 body would need its DER header restored first.

2. **Send the encrypted login**

   Plaintext payload:

   ```json
   {
     "req": "user.login",
     "reqid": "<28 hex chars>",
     "si": "72057984125699406",
     "user": "…",
     "password": "…",
     "stay": true,
     "deviceType": "AndroidTV",
     "deviceName": "FN Photo",
     "did": "<24 chars>"
   }
   ```

   Encryption:
   - generate a **32-character ASCII** AES key and a random 16-byte IV;
   - `AES-256-CBC/PKCS5Padding` over the payload;
   - `RSA/ECB/PKCS1Padding` over the AES key.

   Envelope (note the request id is **inside** the encrypted blob, not outside):

   ```json
   {"req":"encrypted","iv":"<b64>","rsa":"<b64>","aes":"<b64>"}
   ```

3. **Read the reply**

   `{"result":"succ","uid":1000,"admin":true,"token":"…","secret":"…","backId":"…"}`

   `token` is the `AccessToken`. `backId` replaces the middle 16 hex characters of
   later request ids. Keep the socket open and send `{"req":"ping"}` periodically;
   the reply is `{"res":"pong"}`.

### Request ids outlive a sign-out — reset them

Request ids are 28 hex characters, `tttttttt` + 16-char session id + `eeee`. The
middle field starts as zeros and is replaced by the `backId` from a successful
login.

That id belongs to **one** login, but the generator is a process-wide singleton that
survives signing out. Without an explicit reset, the *second* handshake advertises
the *first* session's id in `util.crypto.getRSAPub`, and a server that treats the id
as session context answers `errno 401`. Clearing it before each handshake fixes a
"sign out, then sign in again" failure (`FnReqId.reset()`, covered by
`FnReqIdTest`).

Whether fnOS actually enforces this is **unverified** — no public client resets it,
and the public ones may simply never sign out. `tools/mock-nas` enforces it as a
modelled rule so the client's bookkeeping is tested rather than assumed.

### The `si` trap — errno 8192

`si` is an 18-digit integer delivered as a **string**. Parsing it with
`getLong`/`optLong` both rounds the low digits (JSON numbers go through a double)
and changes the JSON field type. The server then rejects the login with
`errno 8192` *while still decrypting the payload correctly and echoing `reqid`*,
which makes it look like a crypto bug. Always `optString("si")` and echo it back
untouched.

`tools/mock-nas/server.js` enforces this, and `tools/mock-nas/verify.js` proves the
rejection happens.

## 2b. The 访问码 (access code) gate

Since v1.2.0302 (July 2026), fnOS can put an **access code** in front of the whole
login entry — a "yard gate" before the account password, added after the February
2026 attacks. Official description:
<https://help.fnnas.com/articles/v1/safety/access-code>.

It matters here because the documented coverage explicitly **includes the Android TV
client**, and because of *how* it blocks: the gateway intercepts every path on the
web port, serving a standalone challenge page instead of the SPA, and marks the
response with:

```
X-Trim-Safe-Code-Challenge: 1
```

The WebSocket login endpoint then **refuses the upgrade with `401`** before any
protocol exchange happens. A client that ignores the gate cannot log in at all, and
all it sees is a bare 401.

The exchange is not secret — it is in the challenge page's own script:

```js
fetch("/access_code_verify", {
  method: "GET",
  credentials: "same-origin",
  headers: {
    "x-access-code": encodeBase64(code),   // base64 of the UTF-8 bytes
    "x-access-source": "web",
  },
})
```

A 2xx installs an authorising **cookie**; that cookie is what lets the later
WebSocket upgrade and every photo request through. So a client needs:

1. a real cookie jar (OkHttp's default is `CookieJar.NO_COOKIES`, which silently
   defeats the whole mechanism);
2. one `GET /access_code_verify` carrying the two headers before logging in;
3. the same cookie jar shared by the JSON API client, the media client and the
   login WebSocket.

Verified against a live server: a wrong code returns `401` (no `Set-Cookie`), which
is how the client distinguishes "bad code" from "no gate configured".

Notably the access code deliberately does **not** cover WebDAV/SMB/FTP or share
links, so those remain an alternative transport when the native API is gated.

## 3. HTTP API signing

Every business request carries:

```
AccessToken: <token>
authx: nonce=<6 digits>&timestamp=<epoch millis>&sign=<md5>
```

```
paramHash = md5(GET: params sorted by key as k=v joined with &  |  POST: raw body)
sign      = md5(SALT + "_" + path + "_" + nonce + "_" + timestamp + "_" + paramHash + "_" + SECRET)
```

- `path` **must include the `/p` prefix**.
- `timestamp` is **milliseconds**. `fnAlbum`'s prose says seconds, but its own code
  — and `fnphoto-tv` — use milliseconds. Milliseconds is correct.
- `SALT`/`SECRET` are global constants baked into the fnOS web frontend, not
  per-install values. They are **not** the `secret` returned by login, which is used
  only for signing later WebSocket messages.

### Which representation of the parameters gets hashed?

The two reference clients disagree, and both apparently work:

- `RAW_VALUES` hashes decoded values: `start_time=2026:10:30 00:00:00`
- `ENCODED_QUERY` hashes the exact query on the wire: `start_time=2026%3A10%3A30+00%3A00%3A00`

The two only diverge when a value needs escaping, which `gallery/getList` hits
immediately because of its `YYYY:MM:DD HH:MM:SS` format. Parameterless requests
hash the empty string under both rules, so the difference is invisible there — a
detail worth knowing before concluding "both work".

**FN Photo resolves this by negotiating.** It starts on `RAW_VALUES`; if the server
answers with business code `5000`, it flips to the other rule, replays the request
and persists whichever worked. The settings screen can also pin the mode manually.

## 4. Authentication differs per endpoint

| Endpoint | `AccessToken` | `authx` |
| --- | --- | --- |
| albums / photo lists | yes | yes |
| thumbnails `stream/p/t/{id}/{size}/{uuid}` | yes | **no** |
| video / Live Photo `stream/v/{id}` | yes | **no** |

Because media needs only the token, images and video can go straight to Coil and
ExoPlayer with a single header-adding interceptor.

`size` ∈ `xxs | xs | s | m | o`. Measured: `xxs` 107×60, `xs` 320×180, `s` 427×240,
`m` 1920×1080; `l` is not served by current firmware. Prefer the relative URLs the
server returns in `additional.thumbnail.*` rather than assembling them yourself.

Video streams support HTTP `Range`, so seeking works. `isLive=1` marks an iPhone
Live Photo; its clip is at `additional.thumbnail.videoUrl` and only deserves a
badge when that URL is actually present.

## 5. Endpoints used

```
GET /p/api/v1/gallery/timeline
GET /p/api/v1/gallery/getList          start_time,end_time,limit,offset,mode=index
GET /p/api/v1/album/list               sort_direction,sort_by,offset,limit
GET /p/api/v1/album/photos             album_id,sort_by,sort_direction,offset,limit
GET /p/api/v1/album_grant/list_to_me   offset,limit,sort_by=share_mod_time,sort_direction
GET /p/api/v1/photo/folder/list        desc,orderBy
GET /p/api/v1/folder_view/getFolderList folderPath,desc,orderBy
GET /p/api/v1/folder_view/getFileList   folderPath,desc,orderBy,limit,offset
GET /p/api/v1/user_photo/stat
GET /p/api/v1/ai-person/list              getAll,limit,orderBy
GET /p/api/v1/ai-person/photoLibrary/timeLine       id
GET /p/api/v1/ai-person/photoLibrary/list           see below
GET /p/api/v1/stream/p/t/{id}/{size}/{uuid}
GET /p/api/v1/stream/face/{faceId}        person avatar; token only, no signature
GET /p/api/v1/stream/v/{id}
```

### 5b. The person gallery is a two-step, single-day API

`ai-person/photoLibrary/list` **only accepts one day at a time**, and it needs all
eight of these parameters:

```
album_id   = <personId>          (yes, the person id in the album field)
endTime    = "YYYY:MM:DD 23:59:59"
end_time   = "YYYY:MM:DD 23:59:59"
limit      = n
offset     = n
personId   = <personId>
startTime  = "YYYY:MM:DD 00:00:00"
start_time = "YYYY:MM:DD 00:00:00"
```

Omitting `album_id` or the camelCase duplicates, or asking for a range wider than a
single day (for example `1970:01:01 … 2099:12:31`, which is what a naive
"give me everything" query looks like), is answered with **HTTP 400**. Note the
`YYYY:MM:DD` form with colons — not dashes.

So a flat, paged grid has to be reassembled from the person's day list:

```
GET /p/api/v1/ai-person/photoLibrary/timeLine?id=<personId>
  -> [{year, month, day, itemCount}, ...]     newest first
```

Which days cover a requested `limit`/`offset` window is computed from those
`itemCount`s *before* fetching, so a page costs a handful of parallel requests rather
than one sequential request per day. The mock enforces the same strictness — it
returns 400 for a wide range or a missing `album_id` — so a regression fails loudly,
and `verify.js` asserts both the accepted and the rejected forms.

`fnphoto-tv`'s README also mentions `/p/api/v1/photo/folder/view` and
`/api/v1/photos/albums`; those do not exist — its own source uses the two
`folder/list` and `album/list` routes above.

### 5c. Albums other people shared with you

`album/list` returns only the account's **own** albums. Albums another account has
shared with you — fnOS's 相册 → 共享, "他人分享相册", frontend route `/p/share/to_me` —
come from a route of their own:

```
GET /p/api/v1/album_grant/list_to_me?offset=0&limit=1000&sort_by=share_mod_time&sort_direction=desc
```

Each entry is the **same object shape as `album/list`, plus `ownerName`**. That field
is the only thing distinguishing a granted album; there is no "shared" filter on
`album/list` to use instead. Captured from a live device (one entry shown, values
trimmed):

```json
{"code":0,"msg":"","data":{"count":null,"hasNext":null,"list":[
  {"albumId":16,"albumName":"Seismic 经典","source":"user",
   "photoCount":132,"videoCount":0,
   "posterUrl":"/p/api/v1/stream/p/t/69422/s/647aec69-…",
   "posterImgUrl":"/p/api/v1/stream/p/t/69422/s/647aec69-…",
   "startDateTime":"2025:04:21 11:07:52","endDateTime":"2026:09:19 05:11:07",
   "shared":1,"ownerId":1,"ownerName":"kungfucode",
   "grants":null,"hasOtherUserPhoto":null,"grantPermission":"",
   "readableUsers":null,"category":0,"birthday":"","displayType":1,"modTime":0}
]}}
```

Three things worth recording:

- **`count` and `hasNext` are `null`** — there is no paging cursor to follow. The fnOS
  frontend asks for `limit=1000` in a single request rather than paging.
- **The route family is real, not a one-off.** The frontend's own `shareApi` bundle
  declares `album_grant/create`, `update`, `cancel`, `list_mine` and `list_to_me`
  (RTK Query tags `mine` / `toMe` / `shareLinkList`). Note `share_link/*` — 外部分享链接
  — is a *different* feature and is not how a device-internal share appears.
- **Opening one works through the ordinary album photo list.** Confirmed on a live
  device, not just the mock: `album/photos?album_id=16` returned a granted album's
  photos (120 of a 132-photo album, loaded over two pages) with no permission
  handling of its own. The frontend drives its album view through
  `album/normal/timeline` + `album/normal/getList` (camelCase
  `albumId`/`startTime`/`endTime`, one day per request) instead, but that is a UI
  choice rather than a requirement — it is not needed to read a granted album.

### Two response envelopes

Most endpoints answer `{code, msg, data}` with `code == 0` meaning success.
`/p/api/v1/app/version` instead answers `{errno, result, data}`. The client treats a
missing `code` as `errno`.

### `end_time` must reach the end of the day

`getList` filters by range, and the time format is `YYYY:MM:DD HH:MM:SS` (colons in
the date). An `end_time` of `2026:10:30 00:00:00` **silently returns nothing for
that day**; it must be `23:59:59`. To list everything, query
`1970:01:01 00:00:00` → `2099:12:31 23:59:59`.

## 6. FN Connect — removed

FN ID sign-in used to be supported: an **FN ID** (`myhome`) is a cloud handle rather than
a hostname, so it was traded for a reachable address before any of the above could happen.

It has been **removed**, because the address it resolves to cannot carry the API. The
lookup itself worked and is recorded here for anyone revisiting it:

```
POST https://fnos.net/api/v1/fn/con
Content-Type: application/json
body:    {"fnId":"myhome"}
fn-sign: sha256("trim_connect`" + fnId + "`" + timestamp + "`" + "anna")
authx:   nonce=<6 digits>&timestamp=<ms>&sign=<md5>
```

```
sign = md5(SALT + "_" + "/api/v1/fn/con" + "_" + nonce + "_" + timestamp + "_" +
           md5(body) + "_" + CONNECT_SECRET)
```

Two things differ from the local API and are easy to get wrong: the authx secret is
`CONNECT_SECRET` (not the photo API's), and a separate `fn-sign` header is required whose
trailing token is `anna`. Both failures look like the NAS being offline rather than a
signing mistake.

### Why it was dropped

The reply lists every address the NAS is known by, and the client tried them local-first
(LAN IPv4 → IPv6 → DDNS → relay → public) with a TCP probe each. Measured against a live
NAS:

| Address group | Outcome |
| --- | --- |
| `ipv4` / `ipv6` / `ddns` / `publicIpv4` / `publicIpv6` | plain HTTP(S) hosts; usable when reachable |
| `fn`, e.g. `kungfucode.fnos.net:443` | **not usable** — see below |

The relay host answers **every** path with a `302` to `https://fnos.net/<fnId>`, and that
target serves the FN Connect landing page (an 851-byte SPA shell) for
`/websocket?type=main` and `/p/api/v1/app/version` alike. Nothing is proxied to the NAS;
only the synthetic `/trimcon` probe returns `200` (empty, plus CORS headers). The page
itself turns out to be a **reachability checker**, not a tunnel client: it verifies each
candidate — STUN against the NAS for the public addresses, an iframe to the NAS's own
`/static/bridge.html` for the LAN, `/trimcon` for the relay — which means the intended
remote path is a **direct** connection to a public or DDNS address, not a relayed one.

So FN Connect could only ever have worked through a DDNS or public address, and the FN ID
was doing nothing those do not: the user types the address and gets there directly. An
earlier version of this section claimed the client simply "skips" the relay, which was
true but understated it — with the tunnel unimplemented and unobservable (it lives in the
vendor's closed-source apps), there was nothing left for FN ID sign-in to do.

Reaching a NAS from outside the LAN therefore needs a **DDNS name, a forwarded port, or
IPv6** — the client will connect to whichever of those answers. An address saved as a bare
handle by an older version is dropped from the saved logins on first launch, since it can
no longer go anywhere.

The reply carries every address the NAS is known by plus its web ports:

```json
{"code":0,"data":{
  "port":{"httpsPort":50317,"httpPort":50316},
  "ipv4":["192.168.31.14","192.168.31.192"], "ipv6":[],
  "ddns":null, "fn":["kungfucode.fnos.net:443"],
  "publicIpv4":["203.0.113.7"], "publicIpv6":["2408:8270:..."]
}}
```

Entries may carry their own port; portless ones take `port.httpPort`, and the scheme
follows the port — 443 is the vendor's TLS entry point, so that base URL is `https://` and
the login socket `wss://`. Getting this wrong is invisible in the request and confusing in
the symptom: with `http://` hardcoded, a sign-in from outside the LAN spoke plaintext at a
TLS listener and died with `unexpected end of stream`.

## 7. TLS — the self-signed certificate

Measured against a live NAS:

| | |
| --- | --- |
| subject / issuer | `O=fnOS, CN=fnOS` — self-signed |
| subjectAltName | `DNS:fnOS` only |
| validity | about three months, then renewed |
| protocol | TLSv1.3 |
| chain | leaf only |

Two **independent** reasons a stock client cannot verify it: there is no chain to a
trusted root, and the only name in it (`fnOS`) matches neither a hostname nor an IP a user
types. The system store, and any user-installed CA, reject it — so "HTTPS only" and
"default trust rules" cannot coexist, and an earlier version spoke cleartext instead.

The client therefore trusts the certificate **on first use**:

- `FnCertificateTrust` accepts any chain — there is nothing to check — and enforces
  continuity in the `HostnameVerifier`, which, unlike `checkServerTrusted`, is told which
  host is being connected to, so fingerprints can be kept per NAS.
- The first fingerprint seen for a host is stored (`cert_pins` in preferences). A
  *different* one is refused once, the pin is dropped, and the login reports
  `ERROR_CERTIFICATE_CHANGED`; the next deliberate attempt re-pins.
- Refusing outright would be a dead end with only a TV remote to fix it, and these
  certificates renew every ~3 months, so a change is routine rather than alarming.

This is encryption plus continuity, **not** CA-backed authentication: an attacker present
on the very first connection is trusted. That is the usual trust-on-first-use trade-off;
the alternative — accepting any certificate silently — would not notice a change at all.

## 8. Error codes

| Code | Meaning | Handling |
| --- | --- | --- |
| `0` | success | — |
| `401` | session rejected at login | most likely a stale `backId` in the handshake; see above |
| `401`, `5001` (HTTP API) | token expired | re-login and replay once |
| `5000` | signature rejected | flip signing rule, replay, persist |
| errno `8192` | bad login parameters (usually `si`) | — |
| errno `131072` | bad credentials | — |
| errno `4224` | not logged in | — |
| errno `4352` | permission denied | — |

## 9. Risks

This is a private, unversioned protocol (v1 and v2 endpoints already coexist) whose
salt and secret come from the vendor's own frontend bundle. A server-side change
can break every third-party client at once, and the credentials must be persisted
in the clear for re-login because no refresh token exists.

A durable alternative would be WebDAV (built into fnOS, ports 5005/5006), which is
sanctioned and carries no reverse-engineered secrets — at the cost of losing
server-side thumbnails, timeline, albums and people/place metadata. The UI is
deliberately kept behind `PhotoRepository`, so swapping the transport would not
touch a single screen.
