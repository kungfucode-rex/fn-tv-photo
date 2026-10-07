# Third-Party Android TV Client → fnOS (飞牛) Photos: Access Options Research

**Scope:** pure web/source research. No project code written.
**Question:** how can an Android TV app running on a *separate* device (not on the NAS) authenticate to and read/manage photos on a 飞牛 fnOS server?
**Date of research:** 2026-10-05

---

## 0. Executive summary

fnOS exposes photos through **four independent, unrelated channels**, and they have *nothing* in common with each other:

| # | Channel | Auth | Photo features | Verdict |
|---|---|---|---|---|
| 1 | **Native photo-gallery API** — WebSocket login + `AccessToken` + `authx` on `/p/api/v1/...` | reverse-engineered, hardcoded global salt+secret | timeline, albums, folders, people, places, AI search, thumbnails, video/Live-Photo streaming, favourites | **best UX, highest fragility** |
| 2 | **WebDAV** (built into fnOS) | plain HTTP Basic with the fnOS account | files/folders only — no album metadata | **most stable & sanctioned** |
| 3 | **DLNA/UPnP** (built into fnOS, minidlna-based) | none | jpg/jpeg browsable; filesystem tree, not fnOS albums | zero-auth, weak structure |
| 4 | **Official Open API** (developer.fnnas.com) | `TRIM_API_TOKEN` over a **Unix socket** | file authorisation only — no photo API at all | **not reachable from outside the NAS** |

Plus **SMB/NFS/FTP** (same as WebDAV: files only) and the **album "分享链接" share-link** feature (one album at a time, password/expiry, no enumeration API).

**Headline correction up front:** the endpoints `/p/api/v1/photo/folder/view` and `/api/v1/photos/albums`, which the brief (and `fnphoto-tv`'s README/API.md) names, **do not exist in any working code**. They appear *only* in that project's prose docs. The real endpoints are `/p/api/v1/photo/folder/list` and `/p/api/v1/album/list`. Details in §1.3.

---

## 1. fnOS native photo-gallery API

### 1.1 The two-legged protocol (this is the single most important finding)

Confirmed identically by two independent reverse-engineering efforts:

> 飞牛的服务端协议分两条链路，**缺一不可** — "fnOS's server protocol has two legs; **both are mandatory**"
> — [fnAlbum `docs/PROTOCOL.md`](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md)

| Leg | Purpose | Auth |
|---|---|---|
| **A. WebSocket encrypted login** — `ws://host:5666/websocket?type=main` | exchanges credentials for a session `AccessToken` | RSA public key + AES-256-CBC |
| **B. HTTP business API** — `/p/api/v1/...` on the same port | albums, photo lists, media streams | `AccessToken` header **+** `authx` signature header |

The two use **different crypto and different secrets**, and the `secret` returned by leg A is **not** used by leg B. Both facts are stated explicitly in [fnAlbum PROTOCOL.md](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md) ("`secret` 是代码里的固定常量，**与登录返回的 secret 无关**，别混用") and are borne out by the code in §1.4.

### 1.2 Leg A — WebSocket login handshake

**URL:** `ws://<host>:5666/websocket?type=main` (WSS on the HTTPS port, default 5667).
Note: the `?type=main` value selects a channel; fnOS's own web UI opens three sockets (`main`, `file`, `timer`) but the docs state one `type=main` socket is sufficient — [fnnas-api `api.md`](https://github.com/FNOSP/fnnas-api/blob/master/api.md), corroborated by [fnWebSocketClient.java](../../../Users/wlilo/AppData/Local/Temp/fnos-research/fnphoto-tv/app/src/main/java/com/fnphoto/tv/api/FnWebSocketClient.java).

**Step 1 — request the RSA public key**

```json
{"req":"util.crypto.getRSAPub","reqid":"r1"}
```

Response:

```json
{"pub":"-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8A...","si":"72057984125699406","result":"succ","reqid":"r1"}
```

- `pub` is **X.509 / SubjectPublicKeyInfo** (`BEGIN PUBLIC KEY`), *not* PKCS#1. Use `X509EncodedKeySpec`. fnAlbum also ships a PKCS#1 fallback parser.
- `si` is an **18-digit integer sent as a JSON string**. See the "fatal trap" below.

**Step 2 — build the plaintext login payload** (fields per [fnnas-api `docs/modules/auth.md`](https://github.com/FNOSP/fnnas-api/blob/master/docs/modules/auth.md) and [fnAlbum `Api.kt`](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/app/src/main/java/com/fnalbum/tv/Api.kt)):

```json
{
  "req": "user.login",
  "reqid": "login-<epoch-ms>",
  "user": "<account>",
  "password": "<plaintext>",
  "stay": true,
  "deviceType": "tv",
  "deviceName": "FnAlbumTV",
  "did": "<24 chars from nanoid-style alphabet>",
  "si": "72057984125699406"
}
```

`did` alphabet (verbatim from `FnCrypto.kt`): `useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict`.
`fnnas-api`/`fnphoto-tv` omit `did` and use `deviceType: "Browser"`, `deviceName: "Windows-Google Chrome"`, `stay: false` — so `did` is optional but `deviceType`/`deviceName` are sent by all implementations.

**Step 3 — encrypt and send the envelope**

1. generate a **32-character random ASCII string** → AES key, used as its **UTF-8 bytes** (AES-256);
2. **AES-256-CBC / PKCS5(PKCS7) padding**, random **16-byte IV**;
3. **RSA/ECB/PKCS1Padding** encrypt the AES key string;
4. send:

```json
{"req":"encrypted","iv":"<b64(iv)>","rsa":"<b64(rsa(aesKey))>","aes":"<b64(aes(payload))>"}
```

Note the whole request is the envelope: **there is no `reqid` outside the AES blob** — `reqid` lives inside the encrypted payload. ([fnphoto-tv FnWebSocketClient.java](https://github.com/ljmljz/fnphoto-tv/blob/main/app/src/main/java/com/fnphoto/tv/api/FnWebSocketClient.java) documents this explicitly.)

**Step 4 — response**

```json
{"uid":1000,"admin":true,"token":"TvPxZlDmGWj+Zhoj3ePKAXhNwEoRg20sOTC0+j/Yof8=",
 "secret":"bWH/dMzpTM2c498hzpW5FXic9ap5wPHhFiMqXnFBqs4=",
 "backId":"6815b82200000001","machineId":"...","result":"succ","reqid":"..."}
```

- `token` → the `AccessToken` for leg B (legacy implementations send it in an `accesstoken` header).
- `secret` → AES-decrypt it with the same key/IV to get a **base64 HMAC-SHA256 key** used to sign *subsequent WebSocket requests* (not HTTP requests). `fnnas-api` `handlers.py`: `client.sign_key = aes_decrypt(client.secret, client.key, client.iv)`.
- `backId` → replaces the middle 16 hex chars of subsequent `reqid`s.

**WS request signing** (for post-login WS commands such as `user.logout`, `file.ls`): `base64(HMAC-SHA256(base64decode(secret), compactJson))` **prepended** to the JSON body, e.g.
`TvZJMueyhbrH/BtEmr1zU/Eb/WtvrmkACx+5pio2PfE={"req":"file.ls","reqid":"..."}`
Signing is skipped for `encrypted`, `util.getSI`, `util.crypto.getRSAPub` — [fnnas-api `sdk/encryption.py` `get_signature_req`](https://github.com/FNOSP/fnnas-api/blob/master/sdk/encryption.py).

**Keepalive:** send `{"req":"ping"}` every 10–60 s; server replies `{"res":"pong"}` — [fnnas-api `docs/protocol.md`](https://github.com/FNOSP/fnnas-api/blob/master/docs/protocol.md).

**Fatal trap (errno 8192):** `si` must be echoed back **as a string**. If you parse it with `getLong`/`optLong` and re-serialise, JSON numbers go through a double and the **last digits are lost** (`...406` → `...410`), and the field type changes. The server then fails with `errno 8192`, while still correctly decrypting the payload and echoing `reqid` — making it look like a crypto bug. Source: [fnAlbum PROTOCOL.md §三](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md), which also notes `8192` maps to "server requires encrypted login" in [fnphoto-tv's Api.kt error map](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/app/src/main/java/com/fnalbum/tv/Api.kt).

### 1.3 Leg B — the `/p` HTTP API

All paths are prefixed `/p`. Port is the **fnOS web port, default 5666** (HTTPS 5667) — see §4.1. The photo app has no separate port of its own.

**Headers:**

```
AccessToken: <token from leg A>
authx: nonce=<6-digit>&timestamp=<epoch>&sign=<md5>
```

Header name casing varies (`AccessToken` in fnAlbum, `accesstoken` in fnphoto-tv) — HTTP headers are case-insensitive, so both work.

**`authx` signing algorithm** (verified byte-for-byte against a real server per the fnAlbum source comment):

```
# GET
paramStr  = params sorted ascending by key, joined as "k=v" with "&", RAW (un-encoded) values
paramHash = md5(paramStr)
# POST
paramHash = md5(body)                      # raw body text

nonce     = random 6-digit int, 100000..999999
timestamp = System.currentTimeMillis()      # epoch MILLISECONDS
sign      = md5(SALT + "_" + path + "_" + nonce + "_" + timestamp + "_" + paramHash + "_" + SECRET)
authx     = "nonce=<nonce>&timestamp=<timestamp>&sign=<sign>"
```

Critical details:
- **`path` must include the `/p` prefix** or the signature fails;
- the signed `paramStr` uses **raw** values, while the actual query string is **URL-encoded** `URLSearchParams`-style (space → `+`, unreserved `A-Za-z0-9*-._`, otherwise uppercase-hex UTF-8 percent-encoding);
- `SECRET` is the hardcoded constant, **not** the login `secret`.

**Timestamp unit conflict (flagged, resolved in favour of milliseconds):** [fnAlbum PROTOCOL.md](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md) *prose* says `timestamp=<秒级时间戳>` (seconds), but its own `FnCrypto.buildAuthx` uses `System.currentTimeMillis()` (ms), and [fnphoto-tv `FnAuthUtils`](https://github.com/ljmljz/fnphoto-tv/blob/main/app/src/main/java/com/fnphoto/tv/api/FnAuthUtils.java) also uses `System.currentTimeMillis()`. **Both implementations that demonstrably work use milliseconds**; the PROTOCOL.md sentence is an error in prose. Treat **milliseconds** as correct.

**Error codes:** `code:0` success · `401` and `5001` → session expired (re-login) · `5000` → signature check failed · `errno` codes on the WS side include `65534` = 验签失败 (signature failure), `131072` = wrong username/password, `8192` = bad params.

**Endpoint inventory.** Method/params below are taken from working client code, not from prose. Sources: [fnphoto-tv `FnHttpApi.java`](https://github.com/ljmljz/fnphoto-tv/blob/main/app/src/main/java/com/fnphoto/tv/api/FnHttpApi.java), [fnAlbum `Api.kt`](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/app/src/main/java/com/fnalbum/tv/Api.kt), and the route table of fnphoto-tv's own mock server [test/test_server.py](https://github.com/ljmljz/fnphoto-tv/blob/main/test/test_server.py).

| Method | Path | Key params | Notes |
|---|---|---|---|
| GET | `/p/api/v1/gallery/timeline` | `is_collect` (opt) | day buckets: `{year,month,day,itemCount}` |
| GET | `/p/api/v1/gallery/getList` | `start_time`, `end_time`, `limit`, `offset`, `mode=index` | main photo list; contiguous paging |
| GET | `/p/api/v1/gallery/recent` | `limit`, `offset` | |
| GET | `/p/api/v1/explore/recent_timeline` | — | |
| GET | `/p/api/v1/explore/geos` | `offset`, `limit` | place list |
| GET | `/p/api/v1/album/list` | `sort_by`, `sort_direction`, `offset`, `limit` | albums |
| GET | `/p/api/v1/album/photos` | `album_id`, `sort_by=date_time`, `sort_direction`, `offset`, `limit` | |
| GET | `/p/api/v1/photo/folder/list` | `desc`, `orderBy` | managed folders |
| GET | `/p/api/v1/folder_view/getFolderList` | `folderPath`, `desc`, `orderBy` | subfolders |
| GET | `/p/api/v1/folder_view/getFileList` | `folderPath`, `desc`, `orderBy`, `limit`, `offset` | files in folder |
| GET | `/p/api/v1/photo/detail/{id}` | — | EXIF-ish detail |
| GET | `/p/api/v1/photo/search` | `keyword`, `limit`, `offset` | |
| POST | `/p/api/v1/search/results` | JSON body (filters) | geo/smart filters |
| POST | `/p/api/v1/photo/collect` | `id`, `collect` (form-encoded) | favourite toggle |
| GET | `/p/api/v1/photo/collect/list` | `limit`, `offset` | |
| GET | `/p/api/v1/ai-person/list` | `getAll=true&limit=-1&orderBy=0` | face clusters |
| GET | `/p/api/v1/ai-person/photoLibrary/timeLine` | `id` | |
| GET | `/p/api/v1/ai-person/photoLibrary/list` | `personId`/`id`, `start_time`, `end_time`, `limit`, `offset` | params inconsistent across callers |
| GET | `/p/api/v1/user_photo/stat` | — | `{photoCount,videoCount,isAdmin}` |
| GET | `/p/api/v1/app/version` | — | |
| GET | `/p/api/v1/user/info` | — | |
| GET | `/p/api/v1/server/sys_info` | — | |
| GET | `/p/api/v1/server/users_all` | — | admin |
| GET | `/p/api/v1/server/folder_manage` | — | storage |
| GET | `/p/api/v1/photo/support/list` | — | supported upload types |
| GET | `/p/api/v2/photo/upload/path` | — | **note `/p/api/v2/`** |

**Exactly which of your named endpoints are real:**

| Endpoint in the brief | Real? | Evidence |
|---|---|---|
| `/p/api/v1/gallery/timeline` | ✅ real | in code + mock server route table |
| `/p/api/v1/gallery/getList` | ✅ real | in code |
| `/p/api/v1/photo/folder/view` | ❌ **does not exist** | appears **only** in `README.md`/`README_CN.md` prose; code uses `/p/api/v1/photo/folder/list` |
| `/api/v1/photos/albums` | ❌ **does not exist** | appears only in a code *comment* + README prose; the actual call is `/p/api/v1/album/list` |
| `/p/api/v1/stream/p/t/{id}` | ✅ real, full form `/p/api/v1/stream/p/t/{id}/{size}/{uuid}` | in code |

So `fnphoto-tv`'s README/API.md are **partly stale or unverified**, and `API.md`'s WebSocket section is outright wrong (it documents `ws://{host}:5666/p/api/v1/auth/ws` with `{"msg_id","action":"login"}`, which contradicts its own `FnWebSocketClient.java`, which uses `/websocket?type=main` + `util.crypto.getRSAPub` + the `encrypted` envelope). **Trust the code, not that API.md.** The `API.md` non-WS endpoint section does match the code.

### 1.4 Where the API key and secret come from — and their actual values

`FnAuthUtils.java` (fnphoto-tv) and `FnCrypto.kt` (fnAlbum) contain **byte-for-byte identical** constants:

```java
// fnphoto-tv, FnAuthUtils.java:17-18
private static final String API_KEY    = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
private static final String API_SECRET = "EAECCF25-80A6-4666-A7C2-A76904A74AB6";
```

```kotlin
// fnAlbum, FnCrypto.kt
const val SALT        = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh"
const val SIGN_SECRET = "EAECCF25-80A6-4666-A7C2-A76904A74AB6"
```

Answering your question directly:

- **Not per-install.** Two unrelated developers, working independently, hardcode the same pair. `SALT` is a 30-char lowercase-alphanumeric string (shaped like an app key) and `SIGN_SECRET` is a UUID with all-hex groups (shaped like an app secret). They are **global constants baked into the fnOS photo-gallery web frontend bundle**, recovered by inspecting that bundle / proxying traffic.
- **Not from the login exchange.** `secret` from leg A is a different value, per-session, and is used for WS signing only. Both projects state this.
- **A second, different secret exists for the FN Connect cloud API:** `/api/v1/fn/con` is signed with `SALT` + `"zIGtkc3dqZnJpd29qZXJqa2w7c"` and additionally carries an `fn-sign` header = `sha256("trim_connect`" + fnId + "`" + timestamp + "`" + "anna")`. Source: [FnConnectApi.java](https://github.com/ljmljz/fnphoto-tv/blob/main/app/src/main/java/com/fnphoto/tv/api/FnConnectApi.java).

I could **not** verify the original extraction source (fnOS web JS bundle path/hash) — **unverified**. I also could not run a cross-repo code search to see how widely these constants appear: `grep.app` returned HTTP 429 (Vercel bot check) and the `club.fnnas.com` forum is behind a Cloudflare WAF that blocks automated fetch.

### 1.5 Thumbnails vs originals

Paths come back from the server inside `additional.thumbnail.*`, so **always prefer the returned relative URL** and just prepend `http://host:5666`.

```
/p/api/v1/stream/p/t/{id}/{size}/{uuid}
```

`{size}` ∈ `xxs | xs | s | m | l | o` (`o` = original). Measured resolutions ([fnAlbum PROTOCOL.md §四](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md)):

| size | resolution | size on disk |
|---|---|---|
| `xxs` | 107×60 | 2.2 KB |
| `xs` | 320×180 | 9.7 KB |
| `s` | 427×240 | 16.4 KB |
| `m` | 1920×1080 | 204 KB |
| `l` | **unavailable** | — |
| `o` | original | — |

Field names seen in responses: `xsUrl`, `sUrl`, `mUrl`, `xxsUrl`, `originalUrl`, `videoUrl`.

**Auth difference — very important for a TV client:**

| Resource | `AccessToken` | `authx` |
|---|---|---|
| album/photo list endpoints | ✅ required | ✅ required |
| thumbnails `stream/p/...` | ✅ required | ❌ **not** required |
| video / Live-Photo `stream/v/{id}` | ✅ required | ❌ **not** required |

So a plain `AccessToken` header (e.g. in a Coil/Glide/ExoPlayer interceptor) is enough for images and video. Source: [fnAlbum PROTOCOL.md §二](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md).

An alternative thumbnail URL form appears in fnphoto-tv's mock server — `/p/api/v1/stream/p/t/{id}/o/{uuid}?size=m` (size as a query param on the `/o/` path). Both forms are plausible; since the server hands you the URL, this is a non-issue in practice. (`test_server.py` is the app author's own mock, so treat it as a consistency check, not ground truth.)

**Video / Live Photo:** `/p/api/v1/stream/v/{id}`; MP4/H.264 for video, MOV/HEVC for Live Photos, and **`Range` is supported (HTTP 206)**, so seeking works without full download. `isLive == 1` marks a Live Photo and its clip URL is `additional.thumbnail.videoUrl`.

**Time format trap:** `start_time`/`end_time` use `YYYY:MM:DD HH:MM:SS` (**colons in the date**). And `end_time` must be pushed to `23:59:59` — if left at `00:00:00` **that day's photos are silently omitted**. fnAlbum queries everything with `1970:01:01 00:00:00` → `2099:12:31 23:59:59`. Sources: [MainFragment.java](https://github.com/ljmljz/fnphoto-tv/blob/main/app/src/main/java/com/fnphoto/tv/MainFragment.java), [fnAlbum PROTOCOL.md](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md).

### 1.6 Session token lifetime and refresh

- **Lifetime: unverified.** No source states a TTL. `fnnas-api`'s `docs/modules/auth.md` literally ends with an empty `## 刷新Token` heading — a refresh endpoint is hinted at but undocumented.
- **Refresh: no refresh-token flow observed.** Both working clients handle expiry by **full re-login**: fnphoto-tv's `Reauthenticator` re-runs the whole WebSocket login on HTTP 401 and rewrites the stored token; fnAlbum throws `FnAuthException("登录状态已失效")` on `code 401/5001` and relies on the login dialog. Sources: [Reauthenticator.java](https://github.com/ljmljz/fnphoto-tv/blob/main/app/src/main/java/com/fnphoto/tv/api/Reauthenticator.java), [ApiInterceptor.java](https://github.com/ljmljz/fnphoto-tv/blob/main/app/src/main/java/com/fnphoto/tv/api/ApiInterceptor.java).
- **Practical implication:** **store the username+password**, not just the token, and be ready to re-login transparently. fnphoto-tv persists `saved_pass` in `SharedPreferences` for exactly this.
- Related but separate: the WS layer supports token-based re-login (`loginViaToken`) and token management (`user.listTokens`, `listLoginDevices`) — see the `fnos` TS SDK in §2.2. Whether those tokens are the same `AccessToken` is **unverified**.

### 1.7 Error codes worth handling

From [fnnas-api `docs/protocol.md`](https://github.com/FNOSP/fnnas-api/blob/master/docs/protocol.md): `4224` not logged in · `4352` permission denied · `65534` signature failed · `8192` bad parameter · `131072` bad username/password · `100000001` rate-limited ("操作太频繁"). On the HTTP side: `code 5000` = authx signature rejected.

---

## 2. `fnnas-api` and the rest of the `FNOSP` org

### 2.1 `FNOSP/fnnas-api` — what it actually is

- **URL:** https://github.com/FNOSP/fnnas-api · docs site https://fnosp.github.io/fnnas-api/ · MIT · **Python** · 43★ / 15 forks · created 2025-05-06, last push **2025-11-14**.
- **It is NOT the photo API.** It implements the **WebSocket OS-level API** (`auth.login`, `user.info`, `file.ls`, `file.checkUpload`, upload, `setting_port`, storage, user list) — *not* the `/p/api/v1/...` gallery API. Its own `api.md` credits the original work to https://github.com/k23223/fnnas-api.
- **Layout:** `docs/` (Docsify site: `protocol.md`, `modules/{auth,file,stor,sysinfo,user}.md`), `sdk/` (`base_client.py`, `encryption.py`, `handlers.py`, `FnOsClient.py`, `utils.py`, `exceptions.py`), plus `api.md` and `README.md`.
- **No API key/secret constants at all** — it never touches the `authx` scheme. The embedded photo-gallery salt/secret exist only in the Android clients (§1.4).
- **TODO list still open:** file download unchecked; and it notes "I switched to an SMB approach so this may not be updated".
- Its `docs/protocol.md` is the best public reference for the WS framing, `reqid` format, ping/pong, `{sign}` prefixing, and the (large) errno table.

### 2.2 Other `FNOSP` repos (full org listing, 26 repos)

Most relevant:

| Repo | Lang | ★ | Relevance |
|---|---|---|---|
| [`FlyNarwhal`](https://github.com/FNOSP/FlyNarwhal) | Dart | **495** | Flutter cross-platform client for 飞牛影视 (video, not photos). Actively developed (`fly-narwhal-server` pushed 2026-10). Best example of a large third-party fnOS client. |
| [`fnnas-api`](https://github.com/FNOSP/fnnas-api) | Python | 43 | as above |
| [`fnos-developer-skill`](https://github.com/FNOSP/fnos-developer-skill) | Python | 17 | "a fnOS software-development Skill for LLMs" — relevant to the **official** `.fpk` path (§5), not to external clients |
| [`fnos-mcp-server`](https://github.com/FNOSP/fnos-mcp-server) | Go | 4 | MCP server for interacting with fnOS |
| [`fnos-dsh`](https://github.com/FNOSP/fnos-dsh) | TypeScript | 15 | DeepSeek Harness app + plugin monorepo |
| [`FnChatBot`](https://github.com/FNOSP/FnChatBot) | Go | 1 | AI-agent chat bot for fnOS, custom MCP + skills |
| `App.Bin.*`, `App.Docker.*`, `FnDepot` | Shell/Vue | — | `.fpk` app packaging examples (`fnpack`-style) — useful templates if you go the NAS-companion route |

**Bottom line for Q2:** `fnnas-api` is a genuine, useful, MIT-licensed reference for the **WebSocket** protocol, but it is **not** a photo-gallery client, contains **no** `authx` key material, and was last pushed 2025-11. The `FNOSP` org has nothing that implements the `/p` photo API.

### 2.3 The most valuable non-FNOSP repos I found

These matter more than `fnnas-api` for your goal:

| Repo | Lang | What it proves |
|---|---|---|
| [`HelloWangXiangDong/fnAlbum`](https://github.com/HelloWangXiangDong/fnAlbum) | Kotlin, MIT | **The single best reference.** Android TV client, pure JVM (no `.so`), MIT, **and it ships [`docs/PROTOCOL.md`](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md)** — the most precise public write-up of the whole two-leg protocol, plus measured thumbnail tiers and the `si`/`end_time` traps. Constants verified in `FnCrypto.kt`. |
| [`ljmljz/fnphoto-tv`](https://github.com/ljmljz/fnphoto-tv) | Java, MIT | Your baseline. **Widest endpoint coverage** (27 endpoints incl. people/places/search/collect). Caveats: `API.md` is unreliable for auth, README lists two non-existent endpoints, contains dead code (`FnWebSocketManager` implements a `/ws/{channel}?token=` scheme that isn't real — it's unreferenced), and bundles a 192 KB CA file. |
| [`cumtfc/yingge-album-tv-android`](https://github.com/cumtfc/yingge-album-tv-android) | Java, **AGPL-3.0** | "影格相册 TV" — Android TV, timeline/folders/albums/people/places/smart-categories, LAN auto-discovery. Ships `scripts/check-sensitive.sh` (secret-scanning) — a good practice signal. |
| [`qiancheng817/feiniu-album-tv`](https://github.com/qiancheng817/feiniu-album-tv) | Java, AGPL-3.0 | Another Android TV fnOS album app. |
| [`jonas-pi/FMphoto`](https://github.com/jonas-pi/FMphoto) | HarmonyOS, PolyForm-NC | 22★, **published on Huawei AppGallery** — includes FN ID login, HDR preview, cross-platform Live Photos, AI search, backup. Proves the API is production-viable; non-commercial licence. |
| [`Timandes/fnos-cli`](https://github.com/Timandes/fnos-cli) | JS, Apache-2.0 | 21 domains / **111 WS commands**, 2FA support, token reuse. Reference for the OS-level WS surface. |
| [`Timandes/pyfnos`](https://github.com/Timandes/pyfnos) + [`Timandes/fnos-ts-client`](https://github.com/Timandes/fnos-ts-client) | Python / TS | The upstream SDKs behind `fnos-cli`. Their `Share` class exposes `webdavOptions()`, `webdavShareOptions()`, `dlnaOptions()`, `dlnaShareOptions()`, `smbShareOptions()`, `nfsShareOptions()`, `ftpOptions()`, `listLinks()`, `getLinkDefaults()`, `getLinkPermission()` — confirming WebDAV/DLNA/NFS/SMB/FTP and share links are all configurable **over the WS API**, and that the WS API has **no photo/gallery domain at all**. |

**Strong negative result:** across `fnos-cli`'s 111 commands and the `fnos`/`pyfnos` SDKs there is **no photo or gallery class**. This independently confirms the split: WS = OS/files/shares, `/p` = the photo app. Available at [npm `fnos`](https://www.npmjs.com/package/fnos) and [PyPI `fnos`](https://pypi.org/project/fnos/).

---

## 3. WebDAV and SMB on fnOS

### 3.1 WebDAV — **built in**, ports **5005 (HTTP) / 5006 (HTTPS)**

- **Built-in, not an app.** fnOS ships WebDAV/SMB/FTP/NFS as core services: "系统内置的防火墙、**DLNA 服务**和**文件共享协议（支持 SMB、WebDAV、FTP、NFS 等）**" — [smzdm fnOS review](https://post.smzdm.com/p/az7pl3on/). The official help centre has a `文件共享协议` ("File sharing protocols") section covering WebDAV, NFS, SMB3 and DLNA alongside each other — [help.fnnas.com `/articles/v1/file-service`](https://help.fnnas.com/articles/v1/file-service).
- **Ports:** "一般会给你两个选项：**HTTP（5005 端口）**和 **HTTPS（5006 端口）**" — [老阳的杂货铺, 飞牛NAS使用FN Connect方式连接WebDAV](https://yangjinyou.com/110.html). Independently consistent with the same 5005/5006 convention quoted for Synology in [Tencent Cloud article](https://cloud.tencent.com.cn/developer/article/2566314).
- **Client URL form:** the service is at the **root** of its port — `http://<nas>:5005/` or `https://<nas>:5006/` — with **path left empty**. The cited walkthrough says: 服务器地址 = the host, 协议 = HTTPS, 端口 = 443/5006, 账号密码 = 你的飞牛 NAS 账号密码, **路径：留空就行** ("path: just leave it empty"). A `/webdav` path prefix was **not** confirmed anywhere — treat any `/webdav` suffix as **unverified**.
- **Auth:** the **fnOS account username/password** over **HTTP Basic**. No app-specific password is documented. Interaction with **2FA** accounts is **unverified** — fnOS does support 2FA ([help: 如何启用账号双重验证](https://help.fnnas.com/articles/v1/settings/setup-2fa)), and a forum thread title complains that WebDAV folder sharing lacks permission management ([tid=32372](https://club.fnnas.com/forum.php?mod=viewthread&tid=32372)), but I could not verify WebDAV+2FA behaviour. **Test this before designing around it.**
- **What it exposes: unverified in detail.** The forum title "飞牛的用户**WEBDAV文件夹共享**能加下权限管理嘛！" implies both **user folders** and **shared folders** are visible and that permission granularity is coarse. I could not read the thread (Cloudflare WAF). **Verify empirically.**
- **Known issues:** `ophub/fnnas` issue **#363** "开启 WebDAV 和 FTP 服务时始终提示'失败出错'" reports WebDAV/FTP failing to enable — I could **not** fetch this page to confirm (fetch failed), so treat as **unverified but worth checking**: https://github.com/ophub/fnnas/issues/363. Multiple forum threads about WebDAV failing to start exist ([tid=62406](https://club.fnnas.com/forum.php?mod=viewthread&tid=62406), [tid=37607](https://club.fnnas.com/forum.php?mod=viewthread&tid=37607)).
- **No documented WebDAV PROPFIND/range/feature matrix** — **unverified**.

**Exact menu path — UNVERIFIED, and my sources conflict:**
  - [Tencent Cloud](https://cloud.tencent.com.cn/developer/article/2566314) claims `控制中心 > 文件服务` → WebDAV. **Low confidence** — that article gives a generic walkthrough and its fnOS section looks copied from its Synology section (which is `控制面板 > 文件服务`); "控制中心" is not even fnOS's wording.
  - [yangjinyou.com](https://yangjinyou.com/110.html) says only "找到 **WebDAV 服务**，把它打开" without naming a parent menu.
  - The official help centre documents the *client-side* Windows WebDAV caveats ([`file-service/webdav`](https://help.fnnas.com/articles/v1/file-service/webdav)) but **has no article on enabling the server side**.
  - Best guess from the fnOS UI structure: **设置 (Settings) → 文件共享 / 网络服务**, near where SMB/S-NFS/FTP/DLNA toggles live. **Do not rely on this — verify on the device.**

### 3.2 SMB/CIFS

- Built-in (same "文件共享协议" family). Standard **445** (SMB2/3) / 139; default ports are **not** documented by fnOS and should be treated as the OS defaults — **unverified**.
- **SMB 3 multichannel** is supported and has an official article ([help: SMB 3 多通道生效条件](https://help.fnnas.com/articles/v1/file-service/smb3-multichannel)), so SMB2/3 is the norm.
- **SMB1:** no evidence either way — **unverified**. This matters because older/cheaper Android TV boxes' SMB clients sometimes need SMB1. There is a community thread on enabling **anonymous SMB access** ([tid=41677](https://club.fnnas.com/forum.php?mod=viewthread&tid=41677)) and a third-party Magisk module that syncs fnOS SMB photos ([CharlesGool/SMB_Smart_Sync](https://github.com/CharlesGool/SMB_Smart_Sync)) — evidence SMB works fine from Android/Linux clients. On Android TV, **Kodi** (SMB2/3 works on Kodi 19+) or **VLC** are the pragmatic clients; `jcifs-ng` is the usual Java library if you build it yourself.

### 3.3 NFS / FTP / SFTP

- **NFS: built in**, with an official caveat article ([help: Windows 通过 NFS 挂载飞牛注意事项](https://help.fnnas.com/articles/v1/file-service/nfs)). Port 2049 (standard). Poor fit for Android TV (no native client).
- **FTP: built in** (appears in the fnOS 文件共享协议 list and in the `Share.ftpOptions()`/`ftpShareOptions()` WS methods). Ports 21/990 presumed — **unverified**.
- **SFTP: unverified.** SSH exists (`Network.getSshStatus()` in the `fnos` SDK), so SFTP likely works, but I found no fnOS doc.

---

## 4. Ports and remote access — confirmed facts

### 4.1 Default ports (official)

From [help.fnnas.com: 如何修改飞牛系统的端口？](https://help.fnnas.com/articles/v1/settings/port-customization):

> fnOS 从 **V0.8.22** 版本之后，默认端口修改为 **HTTP 5666** 和 **HTTPS 5667** 端口。
> 在公测阶段飞牛仍继续占用 **8000 和 8001** 端口…正式版本之后将不再使用 8000 和 8001 端口。

- Changeable at **设置 → 安全性** (Settings → Security). Force-HTTPS and 80/443 redirection toggles live there too.
- **5666 is therefore the correct base for both the WebSocket and the `/p` API.** (fnphoto-tv/`UrlUtils` and fnAlbum both default to 5666; `fnos-cli`/`pyfnos` examples use `:5666` and `wss://…:5667`.)
- The 影视 (video) app uses a separate port (community cites **8005**) — the **photo** app does **not**; it is served under `/p` on the main port. Community corroboration: [forum tid=43247](https://club.fnnas.com/forum.php?mod=viewthread&tid=43247) asks "does the built-in album have its own port like 影视's 8005?".

### 4.2 FN Connect (cloud relay) — address discovery only

> **Update (measured later, and it changes the conclusion):** the lookup below works, but
> the relay address it returns (`fn`) **cannot carry the photo API**. `kungfucode.fnos.net:443`
> answers every real path with a `302` to `https://fnos.net/<fnId>`, which serves the FN
> Connect landing page rather than proxying to the NAS — and that page turns out to be a
> reachability *checker*, not a tunnel client. FN ID sign-in has therefore been removed
> from this app; a remote client needs a DDNS name, a forwarded port, or IPv6. Detail and
> evidence: [fnos-photo-api.md §6](fnos-photo-api.md#6-fn-connect--removed).

`POST https://fnos.net/api/v1/fn/con` with body `{"fnId":"<FN ID>"}` and headers:
`fn-sign: sha256("trim_connect`"+fnId+"`"+timestamp+"`"+"anna")` and an `authx` header using `SALT` + secret `zIGtkc3dqZnJpd29qZXJqa2w7c`.
Response carries `ipv4`, `ipv6`, `ddns`, `fn` (relay), `publicIpv4`, `publicIpv6`, `port.httpPort`, `ver`, `checkSum` — i.e. a DNS-less address-discovery service. Source: [FnConnectApi.java](https://github.com/ljmljz/fnphoto-tv/blob/main/app/src/main/java/com/fnphoto/tv/api/FnConnectApi.java). fnAlbum and FMphoto also support FN ID login. This is how the official app finds your NAS; it also yields a **WebDAV relay host** like `dav.xxx.5ddd.com:443` ([yangjinyou.com](https://yangjinyou.com/110.html)).

---

## 5. DLNA / UPnP on fnOS

- **Built in** (not an app): "系统内置的…**DLNA 服务**" — [smzdm review](https://post.smzdm.com/p/az7pl3on/).
- **Photos ARE served.** Official format list — 图片：**jpg, jpeg** (plus mpg/mp4/mkv/… for video and mp3/flac/… for audio) — [help: DLNA 支持的媒体文件格式](https://help.fnnas.com/articles/v1/file-service/dlna).
- **Ports: 8200/tcp (HTTP) and 1900/udp (SSDP).** From a detailed fnOS DLNA firewall write-up: DLNA needs **both** 8200/tcp **and** 1900/udp; allowing only 8200/tcp means clients never see the server because **the SSDP broadcast is blocked**. The port is **user-configurable** (the author was running it on 48200). The same post confirms the fnOS firewall lives at **设置 → 安全性 → 防火墙** with inbound rules and a "局域网：默认允许访问" toggle — [ruohai.wang: 飞牛fnOS下DLNA服务的防火墙设置](https://ruohai.wang/202505/fnos-minidlna-filewall-setting/).
- **Backend is minidlna-based**, per that same post (the author had previously hand-rolled `minidlna` on Debian and compares the two).
- **Does it expose fnOS *albums*? — INFERENCE, marked unverified.** minidlna exposes **the filesystem tree**, not application-level album objects; fnOS albums are metadata in the photo app's database (fnAlbum's `album/list` returns `albumId`/`albumName`/`photoCount`, not paths). I therefore expect DLNA to show **folders on disk, not "albums"**, and to show photos only from directories it is configured/indexed to share. I could **not** find a source that states this explicitly, nor one that documents the DLNA enable/share configuration UI. **Treat album structure as not available via DLNA until verified.**
- Enabling DLNA on fnOS also has a community thread ([tid=41677](https://club.fnnas.com/forum.php?mod=viewthread&tid=41677)) and a "DLNA enabled but other devices can't find fnOS" thread ([tid=60431](https://club.fnnas.com/forum.php?mod=viewthread&tid=60431)) — both unreadable (WAF), but their titles corroborate that the 1900/udp gotcha is the common failure.

---

## 6. Other channels

### 6.1 Official Open API — confirmed **not** usable by an external LAN client

From [developer.fnnas.com/api/calling/](https://developer.fnnas.com/api/calling/) (verbatim):

```
POST /api/v1/trimapp
Unix Socket: /var/run/trim_open_gateway_apiscope.socket
Content-Type: application/json
Authorization: Bearer <token>
```

> 后端 API **只能由应用服务端通过 Unix Socket 调用**。不要在前端浏览器中直接调用后端 API，也不要把 token 暴露给前端。

- The token is injected into the app's environment as **`TRIM_API_TOKEN`** by fnOS when it launches the app's backend script (`cmd/main`). **No TCP listener is documented.**
- The frontend SDK is [`@trimjs/web-app`](https://www.npmjs.com/package/@trimjs/web-app) and requires `micro_app=true` in the manifest.
- **Scope of the platform is file authorisation and page routing only** — scopes are `trim.file.sharedAccess`, `trim.file.userAccess`, `trim.file.userAcl`, `trim.file.path`, `trim.system.getPlatformConfig`. **There is no photo/album API in the official open platform at all.** Overview: [developer.fnnas.com/api/overview/](https://developer.fnnas.com/api/overview/).

**Consequence:** to use the official API at all you must ship an **`.fpk` companion app on the NAS** that bridges the Unix socket to a LAN HTTP/WebSocket endpoint of your own design. That is a legitimate, sanctioned architecture — but it is a second deliverable, in a second language, installed by the user on the NAS, and it requires the user to trust/install an `.fpk`.

### 6.2 Album "分享链接" (share links)

- **Photos/albums can be shared externally since fnOS V0.9.21 + 相册 app V0.8.55 (Aug 2025).** "新增'分享链接'功能，允许亲朋好友**免登录**访问链接并浏览相册…支持设置**密码和到期时间**" — [IT之家 via ifeng](https://tech.ifeng.com/c/8m0RQ2iAdTo), [DoNews](https://www.donews.com/news/detail/8/5977659.html).
- Share links **stay in sync** with the album ("分享外部链接将同步照片更新，支持同步展示宝宝相册、普通相册时间轴等") and can be **listed/edited/deleted** under 相册 → 共享 → 分享链接.
- Global permission + **domain and bandwidth limits** are configured at **系统设置 → 远程访问 → 外链设置**.
- File-level external links (separate feature, since **V0.8.26**) are created in **文件管理** via right-click → **分享外部链接**, with **expiry, ≥4-char password, and max-visit count**; managed under **文件管理 → 外链分享**. Default link is an FN Connect URL if FN Connect is on, otherwise a **LAN IPv4** URL — [help: 如何创建文件的外部分享链接？](https://help.fnnas.com/articles/v1/file/create-share-link).
- **Is there a share-link enumeration API?** I found **no** documented `/p/api/v1/share/...` endpoint, and no client implements one. **Unverified — assume none.** This channel is therefore good for a **"pin one album / kiosk slideshow"** mode and for **remote** access, but **not** for browsing a user's library.

### 6.3 Docker on fnOS

fnOS supports Docker and Docker Compose, and its app centre ships dozens of container apps ([smzdm review](https://post.smzdm.com/p/az7pl3on/)). A Dockerised photo server (**Immich**, **PhotoPrism**, or a thin proxy in front of the native API) is a legitimate and commonly-suggested workaround that would give a stable, documented REST API to your TV client.

The catch is **which directories you can bind-mount**: fnOS stores data under `/vol1/...` (visible in `fnnas-api`'s upload example `vol1/1001/tmp_img/bg.jpg`), and the **photo app's own library/index location is not publicly documented** — **unverified**. You would most likely point Immich/PhotoPrism at the **original photo folders on disk** (the same ones the photo app indexes) rather than at the photo app's database, which means a second index and a duplicate thumbnail cache. Two community projects confirm people do exactly this kind of external processing — [`god1ong/fn-photo-scan`](https://github.com/god1ong/fn-photo-scan) (Dockerised rescan of mounted remote dirs) and [`DouglaxYuan/fnos-photo-orchestrator`](https://github.com/DouglaxYuan/fnos-photo-orchestrator).

### 6.4 What I could **not** verify

- **`club.fnnas.com` is behind a Cloudflare WAF** that blocked every automated fetch (empty body + `CF_APP_WAF` JS challenge). All forum evidence above comes from search-result titles/snippets only, and is labelled as such.
- `ophub/fnnas` issue #363 could not be fetched (network error).
- `grep.app` code search returned HTTP 429 (bot check) — so I could not measure how many repos embed the salt/secret, nor find other independent extractions.
- No source states the **`AccessToken` TTL**, and no **refresh-token** flow is documented anywhere.
- WebDAV: exact UI menu path, whether home folders *and* shared folders are both exposed, per-user scoping, 2FA interaction, and PROPFIND feature support.
- DLNA: the server-side enablement UI and whether album metadata (as opposed to filesystem folders) is exposed.

---

## 7. Risk assessment and recommendation

### 7.1 Stability vs. cost, per option

| Option | Survives fnOS updates? | Reverse-engineered secrets? | ToS risk | Photo features | Build cost |
|---|---|---|---|---|---|
| **WebDAV** | **High** — a stable protocol, officially shipped, intentionally user-facing | **None** | None | files/folders; you build thumbnails + timeline yourself | Low |
| **SMB** | **High** | None | None | files/folders | Low–Med (SMB lib on Android TV; SMB1 caveat) |
| **DLNA** | **High** | None | None | flat jpg browsing; **no albums**; no auth | Low (or use an existing client) |
| **Native `/p` API** | **Low** — private, unversioned, changes between releases (`/p/api/v1` vs `/p/api/v2` already coexist); `fnAlbum` explicitly warns 接口属于私有协议，可能随官方版本更新而变化 | **Yes** — global salt + secret baked into the web bundle; anyone can rotate them server-side and break every third-party client at once | **Grey.** No published terms for this API; `fnphoto-tv` shows the API_KEY/SECRET as *placeholders* ("`YOUR_API_KEY`") in its README, implying the author considered publishing the real values sensitive. But multiple MIT/AGPL projects ship them | **Best by far** — timeline, albums, folders, people, places, AI search, favourites, Live Photos, thumbnails, range-streaming | Med |
| **`.fpk` companion + official OpenAPI** | **Highest** — sanctioned, versioned, documented | None | None | whatever you implement; the official API only grants **file access**, so you still must build the photo layer | **High** — second codebase, second language, user must install it on the NAS |
| **Docker photo server (Immich/PhotoPrism)** | High | None | None | rich — but a **second, independent** library and index | Med–High |
| **Album share links** | High | None | None | one album at a time, no enumeration | Very low |

### 7.2 Recommendation (ranked, for a third-party Android TV client on the LAN)

1. **🥇 Native photo-gallery API — build this if you want a real photo app.**
   It is the only option that delivers what a TV photo client actually needs: server-side thumbnails at known resolutions, date-grouped timeline, albums, folders, people/places, favourites, and HTTP-206 range streaming for video and Live Photos. It is **proven in production** by at least four independent clients (fnAlbum, fnphoto-tv, 影格相册 TV, FMphoto — the last shipped on Huawei AppGallery). Mitigations are straightforward and concrete:
   - **never hardcode the salt/secret in a published artifact if avoidable** — fetch them from your own backend, or at minimum keep them behind a build-time config and document that they are extracted from the fnOS web bundle;
   - isolate them behind one `FnCrypto`-style module so a rotation is a one-file change;
   - persist **username+password** (not just the token) and re-login transparently on `401`/`5001`, because there is no documented refresh flow;
   - expect breakage on fnOS releases and version-gate accordingly.
   Use [`fnAlbum/docs/PROTOCOL.md`](https://github.com/HelloWangXiangDong/fnAlbum/blob/main/docs/PROTOCOL.md) as your spec — it is materially more accurate than `fnphoto-tv/API.md`.

2. **🥈 WebDAV — ship this as the fallback / "stable mode".**
   Ports **5005 (HTTP) / 5006 (HTTPS)**, plain HTTP Basic with the fnOS account, path at the root, and an FN Connect relay (`dav.<id>.5ddd.com:443`) if the user is remote. Zero reverse-engineering, zero ToS exposure, and it will keep working across fnOS versions. You lose album metadata, people/places, and server-side thumbnails — so the UI must degrade to a folder browser with client-side thumbnail generation. Design your app with a **pluggable backend** so the same UI can sit on either transport.

3. **🥉 `.fpk` companion app on the NAS using the official OpenAPI — the durable long-term answer.**
   If this is going to be maintained for years, this is the only architecture that is *officially supported* and *documented*. The official platform currently exposes **only file authorisation**, so the companion would need to (a) obtain authorised directory paths via `trim.file.*` scopes, (b) expose them to the TV client over LAN HTTP/WS. High cost, but no secrets and no grey area. [`FNOSP/fnos-developer-skill`](https://github.com/FNOSP/fnos-developer-skill) and the `App.Bin.*`/`App.Docker.*` repos are starting points.

4. **DLNA/UPnP — only as a zero-effort bonus.** Built in, no auth, but **no album structure** (filesystem tree only) and no access control. Fine for "show me the photos in this folder"; not a foundation. If you support it at all, prefer delegating to an existing TV client (Kodi/VLC/BubbleUPnP) rather than writing SSDP/UPnP yourself.

5. **SMB — roughly equivalent to WebDAV in stability, weaker on Android.** Only worth it if you already have an SMB stack; watch the SMB1 requirement on old boxes.

6. **Docker photo server (Immich/PhotoPrism) — best if you control the NAS and want a *documented* API.** You trade "talks to the user's existing fnOS library" for "a stable REST API", plus a second index and duplicated thumbnails. Good for a self-hosted power user; poor for a general consumer app.

7. **Album share links — a feature, not a foundation.** Excellent for a "pin this album" / kiosk / remote-sharing mode. No enumeration API, so it cannot browse a library.

### 7.3 One-line answer to "which is most stable vs. which needs reverse-engineered secrets"

**Most stable:** WebDAV (or an `.fpk` companion on the official API) — standard protocols, officially shipped, no secrets, unlikely to break.
**Needs reverse-engineered secrets:** the native `/p` photo API — it depends on a **global, hardcoded `SALT`/`SIGN_SECRET` pair** (`NDzZTVxnRKP8Z0jXg1VAMonaG8akvh` / `EAECCF25-80A6-4666-A7C2-A76904A74AB6`) extracted from the fnOS web frontend, plus an unversioned private protocol with an empty "refresh token" doc. It offers the best features and carries the most long-term risk; it is a *grey* area rather than a clearly prohibited one, but it is the only option where the vendor could break every third-party client with a single server-side change.

---

## Appendix: sources

**Primary source code (cloned and read directly)**
- [`ljmljz/fnphoto-tv`](https://github.com/ljmljz/fnphoto-tv) — `FnAuthUtils.java`, `FnHttpApi.java`, `FnWebSocketClient.java`, `FnWebSocketManager.java`, `FnProtocolUtils.java`, `ApiInterceptor.java`, `Reauthenticator.java`, `FnConnectApi.java`, `UrlUtils.java`, `MainFragment.java`, `FolderBrowseActivity.java`, `test/test_server.py`, `API.md`, `README.md`
- [`FNOSP/fnnas-api`](https://github.com/FNOSP/fnnas-api) — `docs/protocol.md`, `api.md`, `docs/modules/auth.md`, `sdk/encryption.py`, `sdk/handlers.py`, `sdk/base_client.py`, `sdk/FnOsClient.py`, `README.md`
- [`HelloWangXiangDong/fnAlbum`](https://github.com/HelloWangXiangDong/fnAlbum) — `docs/PROTOCOL.md`, `app/src/main/java/com/fnalbum/tv/FnCrypto.kt`, `app/src/main/java/com/fnalbum/tv/Api.kt`, `README.md`

**Other clients / SDKs**
- [cumtfc/yingge-album-tv-android](https://github.com/cumtfc/yingge-album-tv-android) · [qiancheng817/feiniu-album-tv](https://github.com/qiancheng817/feiniu-album-tv) · [jonas-pi/FMphoto](https://github.com/jonas-pi/FMphoto) · [Timandes/fnos-cli](https://github.com/Timandes/fnos-cli) · [Timandes/pyfnos](https://github.com/Timandes/pyfnos) · [npm `fnos`](https://www.npmjs.com/package/fnos) · [PyPI `fnos`](https://pypi.org/project/fnos/) · [god1ong/fn-photo-scan](https://github.com/god1ong/fn-photo-scan) · [CharlesGool/SMB_Smart_Sync](https://github.com/CharlesGool/SMB_Smart_Sync)

**Official fnOS**
- [developer.fnnas.com — 概述](https://developer.fnnas.com/api/overview/) · [调用方式](https://developer.fnnas.com/api/calling/) · [开发指南](https://developer.fnnas.com/docs/guide/)
- [help.fnnas.com — 如何修改飞牛系统的端口？](https://help.fnnas.com/articles/v1/settings/port-customization) · [文件共享协议](https://help.fnnas.com/articles/v1/file-service) · [Windows 通过 WebDAV 挂载飞牛注意事项](https://help.fnnas.com/articles/v1/file-service/webdav) · [DLNA 支持的媒体文件格式](https://help.fnnas.com/articles/v1/file-service/dlna) · [Windows 通过 NFS 挂载飞牛注意事项](https://help.fnnas.com/articles/v1/file-service/nfs) · [SMB 3 多通道生效条件](https://help.fnnas.com/articles/v1/file-service/smb3-multichannel) · [如何创建文件的外部分享链接？](https://help.fnnas.com/articles/v1/file/create-share-link) · [如何共享相册给设备内其他用户？](https://help.fnnas.com/articles/v1/photo/photo-share) · [如何启用账号双重验证（2FA）](https://help.fnnas.com/articles/v1/settings/setup-2fa)
- [fnOS Web API 文档 (fnnas-api docs site)](https://fnosp.github.io/fnnas-api/#/)

**Community / third-party write-ups**
- [老阳的杂货铺 — 飞牛NAS使用FN Connect方式连接WebDAV](https://yangjinyou.com/110.html) (ports 5005/5006, FN Connect WebDAV)
- [ruohai.wang — 飞牛fnOS下DLNA服务的防火墙设置](https://ruohai.wang/202505/fnos-minidlna-filewall-setting/) (8200/tcp + 1900/udp, minidlna)
- [腾讯云开发者社区 — 群晖/极空间/飞牛NAS搭建WebDav](https://cloud.tencent.com.cn/developer/article/2566314) (menu path claim — low confidence)
- [什么值得买 — 飞牛私有云fnOS详解与体验](https://post.smzdm.com/p/az7pl3on/) (built-in DLNA + SMB/WebDAV/FTP/NFS)
- [IT之家 via 凤凰网 — fnOS App v1.21.1 相册新增"分享链接"](https://tech.ifeng.com/c/8m0RQ2iAdTo) · [DoNews — 相册新增分享功能](https://www.donews.com/news/detail/8/5977659.html)
- [ophub/fnnas issue #363](https://github.com/ophub/fnnas/issues/363) — WebDAV/FTP enable failure (**could not fetch; unverified**)

**Blocked / inaccessible during research (disclosed)**
- `club.fnnas.com` forum — Cloudflare WAF blocked all automated fetches: [tid=15939](https://club.fnnas.com/forum.php?mod=viewthread&tid=15939), [tid=62406](https://club.fnnas.com/forum.php?mod=viewthread&tid=62406), [tid=60431](https://club.fnnas.com/forum.php?mod=viewthread&tid=60431), [tid=41677](https://club.fnnas.com/forum.php?mod=viewthread&tid=41677), [tid=32372](https://club.fnnas.com/forum.php?mod=viewthread&tid=32372), [tid=43247](https://club.fnnas.com/forum.php?mod=viewthread&tid=43247)
- `grep.app` code search — HTTP 429 (Vercel bot check)
- `r.jina.ai` text-extraction proxy — connection failed
