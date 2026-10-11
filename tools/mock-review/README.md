# mock-review — the 图评 (tuping) mock and its contract test

Two Node scripts that let the TV end and the H5 page be developed and tested
without a NAS, plus the contract test that keeps the mock honest by running the
same assertions against the real Go server.

```
tools/mock-review/
  server.js     the mock: the whole protocol in memory, with a control surface
  verify.js     the contract test: an independent client, written from the protocol
  package.json  qrcode (real QR PNGs) and ws (the live socket)
```

## Running it

```powershell
cd tools/mock-review
npm install
node server.js            # listens on 0.0.0.0:5670, MOCK_REVIEW_PORT overrides
node verify.js            # 170 checks against http://127.0.0.1:5670
```

The mock also serves the admin door (§17.15) at `/admin`, with the password in
`MOCK_REVIEW_ADMIN_TOKEN` (default `admin` — the same word the recipe below gives the
real server). Log in there and every comment on a photo grows 编辑 and 删除, which is
how the H5 page can be worked on without a NAS.

`server.js` prints the URL a phone should open:

```
  Phone should open : http://127.0.0.1:5670/c/<code>
```

Set `MOCK_REVIEW_BASE_URL` when that address is not the address the test uses —
a second NIC, a container, or a phone on the LAN. It is what goes into the QR
code and into signed thumbnail URLs, and getting it wrong produces the failure
this whole service is easiest to get wrong: a QR code that no phone can open
while the TV looks perfectly fine.

### Running the same test against the real server

`verify.js` is the contract test, so it is meant to be pointed at the Go server
too. That is the only thing that says the two implementations mean the same thing
by the same words; a test that only passes against the mock proves the mock agrees
with itself.

```powershell
# build and start the server (from the repo root)
$env:PATH = "F:\go-toolchain\go\bin;$env:PATH"
$env:GOMODCACHE = "F:\go-toolchain\modcache"; $env:GOTOOLCHAIN = "local"; $env:CGO_ENABLED = "0"
cd server
go build -o ..\artifacts\tmp\tupingd.exe .\cmd\tupingd

$env:TUPING_BASE_URL = "http://127.0.0.1:5671"
$env:TUPING_SECRET = "verify-secret"
$env:TUPING_ADMIN_TOKEN = "admin"
$env:TUPING_DB = "..\artifacts\tmp\verify.db"
$env:TUPING_THUMBS = "..\artifacts\tmp\verify-thumbs"
..\artifacts\tmp\tupingd.exe -addr 127.0.0.1:5671

# then, from tools/mock-review
node verify.js --base http://127.0.0.1:5671
```

The admin checks in `[17b]` need the password the target was started with. Both sides
default to `admin`; a deployment that chose another word says so once:

```powershell
node verify.js --base http://host:5671 --admin-token <TUPING_ADMIN_TOKEN>
```

Both runs end with `ALL CHECKS PASSED` and exit 0 — 170 checks against the mock, 164
against the real server. The six-check difference is three mock-only blocks, each of
which prints a note on the real-server run saying what it is skipping and why:

| Skipped | Why |
| --- | --- |
| the live keepalive ping | The interval is 30s and is not configurable there. Waiting for one would make the test slower than the thing it checks. `MOCK_REVIEW_PING_MS` shortens it on the mock, so the check does run there. |
| the shrunk-budget `429` and the counter/lastBadReason read-back | These use `/__control`, which only the mock has. The `429` path itself is protocol behaviour; it is just not reachable in a contract test without spending a real budget. The same block holds the one-component-JPEG refusal, which is a mock emulation of the Go decoder's own refusal and so is only meaningful where the emulation lives. |
| `/__control` counters | Same reason. |

Everything else runs against both. Field checks tolerate *extra* fields, because
the real server sends more than the protocol requires and a test that broke on
that would be measuring the wrong thing.

`verify.js` builds its own test photographs, by hand, as baseline 4:2:0 JPEGs.
That is deliberate: it needs bytes that Go's `image/jpeg` can decode, that are
far enough apart perceptually that the server's pHash layer will not merge two of
them, and that are byte-identical between two runs when a check depends on that.
The images are seeded per run (`VERIFY_SEED` pins the seed), so a second run
against a server that still holds the first run's photos does not look like a
server that merged something it should not have.

## The control surface

`server.js` implements the protocol and nothing else, and then a small set of
endpoints that exist only so a test can observe or force things that are
otherwise unreachable or slow. None of them exist on the real server.

| Endpoint | What it is for |
| --- | --- |
| `GET /__control/reset` | Clear counters, budgets and forced delays, and return them. `?data=1` also forgets every photo, code, device and comment. |
| `GET /__control/stats` | `requests`, `comments`, `photos`, `signBad`, `rateLimited`, `liveSubscribers`, `lastBadReason`, plus the live budgets. `lastBadReason` is why the last 401/400 happened. |
| `GET /__control/photos` | Every photo the mock holds, with its code, sha256, size and comment count. |
| `GET /__control/register?localId=&source=&device=&sha256=&bytes=` | A `GET`-shaped registration, for a test that only needs an identity and not a real upload. |
| `GET /__control/comment?imageId=&content=` | The newest matching comment, so a push can be confirmed from the server side instead of from a screen. |
| `POST /__control/rate-limit?key=&limit=&windowMs=` | Shrink one budget, so a `429` is reachable without spending 300 registrations. The key is matched against the protocol's budgets by pattern. |
| `GET /__control/slow?ms=N` | Delay every response, so "the TV keeps the photo up while waiting" is something a camera can catch. |
| `GET /__control/ping-ms?ms=N` | Shorten the live keepalive so `{"t":"ping"}` is observable without waiting 30 seconds. `ms=0` restores it. |
| `GET /__control/fail-next?n=N&status=500` | Fail the next N non-control requests, so the client's silent-failure path is reachable on demand. |

## What it deliberately does not implement

The mock is not a smaller version of the server. Three things are missing on
purpose, and saying so is the point — a mock that quietly accepts everything is
worthless, and a mock whose gaps are undocumented is worse, because a client that
passes here and fails on a TV has been actively misled.

**No pHash, and no variance guard rail.** Identity is `sha256Original` when the
album sent it, and otherwise a SHA-256 of the thumbnail bytes: identical bytes are
one photo, different bytes are two. The real server also merges a re-encoded,
padded or resized copy of a photo, and refuses to auto-merge images whose 64×64
grayscale variance is below 500 (flat images collide with each other rather than
hashing randomly — `docs/tuping-design.md` §3.3). Those are the server's jobs and
they need a real DCT and real photographs to be worth testing, so `verify.js`
never asserts perceptual merging and never uploads a flat image. The cross-device
merge check uses *identical bytes* precisely because that part is the client's
contract and the rest is the server's.

**Signed-URL crypto strength, not signed-URL behaviour.** The signature is a real
HMAC over `code|exp|w` with a key minted per process, so a tampered or expired URL
is a 401 — which is what a client has to cope with, and what the test checks. It
is not a claim about how the real server derives its key, and nothing outside
`server.js` can or should verify a signature the mock produced.

**No persistence, and no retention sweep.** Everything is in this process and is
gone when it exits; there is no TTL, no cleanup, and `?data=1` on the reset
endpoint is the only way to get back to an empty world. The real server keeps
SQLite and a thumbnail directory and expires thumbnails that nobody reads. A test
that needs "the same photo a week later" needs the real server.

**No `/v1/admin/*`.** The moderation queue and the global block/delete endpoints are
operator tooling with no client contract behind them, so the mock never had them. The
admin *door* on the page is a different thing and is implemented (§17.15): `/admin`, the
signed session cookie, and 编辑 / 删除 on a comment, all of which `verify.js` checks
against both servers. What the mock will not do is answer the JSON queue, so a test that
needs it needs the real server.

Two smaller divergences, for the same reason — they are visible rather than
hidden:

- Thumbnails are not decoded and re-encoded. The bytes are served with their
  APP1/Exif segments stripped, which is what the client can observe (no GPS
  reaches the phone), but a mock cannot prove a re-encode happened.
- A one-component JPEG is refused, because Go's decoder refuses one. That check
  is deliberate: without it a grayscale thumbnail would pass here and be a 400
  from the real server, which is exactly the kind of difference this mock exists
  to catch. Add the equivalent for any other decoder quirk that is discovered.

The test photographs are generated at run time by `verify.js` and are not
photographs of anything.
