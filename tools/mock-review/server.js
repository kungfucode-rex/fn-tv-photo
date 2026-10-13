'use strict';

/*
 * Mock 图评 (tuping) server for developing the TV end and the H5 page without a
 * NAS, and the first target the contract test runs against.
 *
 * It implements the protocol in docs/tuping-design.md and nothing else. The one
 * thing it is NOT allowed to do is accept everything: a mock that waves a broken
 * client through is worse than no mock at all, because the failure then shows up
 * on a TV with no console. So the rules the protocol calls hard — device
 * re-issue without a token, an unauthenticated thumbnail URL, an over-long or
 * advertising comment, the five-minute duplicate window, the idempotency key, the
 * third report — are all enforced here and are all refusals, not warnings.
 *
 * Endpoints, all of them under /v1 unless noted:
 *
 *   POST   /v1/devices                -> device token, owner token, base URL
 *   POST   /v1/photos                 -> register a thumbnail, get a code back
 *   GET    /v1/photos/{code}          -> one photo's public details
 *   DELETE /v1/photos/{id}            -> owner-token delete: photo, code, thumb, comments
 *   GET    /v1/qr/{code}.png?size=440 -> the QR PNG the TV draws in the corner
 *   GET    /t/{code}?w=&exp=&sig=     -> signed thumbnail bytes
 *   GET    /v1/comments?imageId=…     -> comment history, oldest first
 *   POST   /v1/comments               -> submit a comment
 *   POST   /v1/comments/{id}/report   -> report a comment
 *   GET    /v1/live/{imageId}         -> WebSocket: every new comment, plus pings
 *   GET    /c/{code}                  -> the H5 page (no script, no https)
 *   POST   /c/{code}                  -> the H5 form, 303 back to the page
 *   GET    /c/{code}/report/{id}      -> one-press report from the page
 *   GET    /admin                     -> the admin login form
 *   POST   /admin                     -> log in, 303 back to the page you came from
 *   POST   /admin/logout              -> drop the session
 *   GET    /c/{code}/edit/{id}        -> the admin's one-comment edit form
 *   POST   /c/{code}/edit/{id}        -> save that edit, 303 back to the photo
 *   POST   /c/{code}/delete/{id}      -> soft-delete that comment, 303 back
 *   GET    /healthz                   -> liveness, version, thumbnail bytes
 *
 * The admin door (§17.15) is the same token the real server calls TUPING_ADMIN_TOKEN,
 * here MOCK_REVIEW_ADMIN_TOKEN and defaulting to `admin` so a bare `node verify.js`
 * can walk through it. The session is a cookie signed over its own expiry, exactly as
 * the real one is, because "a caller cannot mint their own session" is a property of
 * the door rather than of the Go standard library.
 *
 * Control endpoints. These exist so a test can observe or force the states that
 * are otherwise only reachable by waiting, and they are the reason this file is
 * longer than the protocol:
 *
 *   GET  /__control/reset                          -> clear counters, return them
 *                                                     (`?data=1` also forgets every
 *                                                     photo, code, device and comment)
 *   GET  /__control/stats                          -> counters, including lastBadReason
 *   GET  /__control/photos                         -> every photo the mock holds
 *   GET  /__control/register?localId=&source=&device=&sha256=&bytes=
 *                                                  -> a GET-shaped registration, for tests
 *                                                  that only need an identity
 *   GET  /__control/comment?imageId=&content=      -> the newest comment's JSON, so a
 *                                                  test can confirm a push from the
 *                                                  server side rather than from a screen
 *   POST /__control/rate-limit?key=&limit=&windowMs=
 *                                                  -> shrink one budget, so a 429 is
 *                                                  reachable without 300 registrations
 *   GET  /__control/slow?ms=N                      -> delay every response, so "the TV
 *                                                  keeps the photo up while waiting" is
 *                                                  something a camera can catch
 *   GET  /__control/ping-ms?ms=N                   -> shorten the live keepalive, so
 *                                                  {"t":"ping"} is observable without
 *                                                  waiting 30 seconds (ms=0 restores it)
 *   GET  /__control/fail-next?n=N&status=500       -> fail the next N non-control
 *                                                  requests, so the client's
 *                                                  silent-failure path is reachable
 *
 * Two deliberate deviations, both printed at start-up:
 *
 *   1. No pHash. Identity is sha256Original when the album sent it, and otherwise
 *      a cheap deterministic digest (SHA-256) of the thumbnail bytes, so identical
 *      bytes are one identity and different bytes are two. The real server's
 *      perceptual layer would also merge a re-encoded or padded copy of a photo;
 *      this mock will not, and verify.js therefore never asserts that it does.
 *   2. The signed thumbnail URL is a real HMAC over code|exp|w with a key minted
 *      per process. That is enough to make a tampered or expired URL a 401, which
 *      is what the client has to cope with; it is not a claim about how the real
 *      server derives its key.
 */

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');
const { WebSocketServer } = require('ws');
const QRCode = require('qrcode');

const PORT = Number(process.env.MOCK_REVIEW_PORT || 5670);

// How often a live socket is told "still here". The protocol says at least every
// 30 seconds. It is settable at runtime through /__control/ping-ms so a test does
// not have to sit through one — the real server's interval is not configurable,
// and a contract test that waited 30 seconds per run would stop being run.
let pingMs = Number(process.env.MOCK_REVIEW_PING_MS || 30000);

// The version this mock claims to be. The album only logs it, but the contract
// test checks that the field is present and is a string.
const VERSION = 'mock-review/1.0.0';

/**
 * The admin password, and the key that signs admin sessions (§17.15).
 *
 * The token has a default here and none on the real server, and that difference is
 * deliberate: on a NAS, "no TUPING_ADMIN_TOKEN" means the operator decided not to have
 * an admin, and offering a login that cannot succeed would be a lie. In a mock that is
 * started by a test, a default is what makes `node verify.js` able to walk through the
 * door at all.
 */
const ADMIN_TOKEN = process.env.MOCK_REVIEW_ADMIN_TOKEN || 'admin';
const ADMIN_SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const ADMIN_SECRET = crypto.randomBytes(32);

/**
 * A port already in use is the usual way this mock dies at start-up, and an
 * unhandled 'error' on a server takes the process down with a bare code and no
 * explanation — which reads like a crash in the mock rather than a second copy
 * still running. Say which.
 */
function reportBindFailure(error) {
  console.log(`  FATAL: could not listen (${error.code}): ${error.message}`);
  console.log(`         is another server already on ${PORT}? (MOCK_REVIEW_PORT=${process.env.MOCK_REVIEW_PORT || ''})`);
  process.exit(1);
}

// ---------------------------------------------------------------- identifiers

// The Crockford base32 alphabet ULIDs are written in. Image ids and comment ids
// are both ULIDs, so they sort by time as strings.
const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

// 62^6 ≈ 5.7x10^10. The code is the only thing between a stranger and a photo, so
// it is drawn from crypto randomness and never reused.
const BASE62 = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz';
const CODE_LENGTH = 6;

/**
 * Mints a ULID: 48 bits of milliseconds, 80 bits of randomness, Crockford base32,
 * 26 characters. Within one millisecond the entropy is incremented as a big-endian
 * integer so ids stay strictly increasing, exactly as the real server does — a
 * history page that sorts by id has to agree with one that sorts by time.
 */
const ulidGen = (() => {
  let lastMS = 0;
  let entropy = crypto.randomBytes(10);
  return () => {
    const ms = Date.now();
    if (ms > lastMS) {
      lastMS = ms;
      entropy = crypto.randomBytes(10);
    } else {
      for (let i = entropy.length - 1; i >= 0; i--) {
        entropy[i] = (entropy[i] + 1) & 0xff;
        if (entropy[i] !== 0) break;
      }
    }
    const b = Buffer.alloc(16);
    b.writeUIntBE(ms, 0, 6);
    entropy.copy(b, 6);
    let out = '';
    // 128 bits do not divide into 5-bit groups; the standard trick is to emit the
    // top 2 bits as the first character and then walk the remaining 126 bits.
    out += CROCKFORD[(b[0] & 0xe0) >> 5];
    let acc = 0;
    let bits = 0;
    for (let i = 0; i < 16; i++) {
      acc = (acc << 8) | b[i];
      bits += 8;
      while (bits >= 5) {
        out += CROCKFORD[(acc >> (bits - 5)) & 0x1f];
        bits -= 5;
      }
    }
    return out;
  };
})();

const newId = () => ulidGen();

const newCode = () => {
  const raw = crypto.randomBytes(CODE_LENGTH);
  let out = '';
  for (let i = 0; i < CODE_LENGTH; i++) out += BASE62[raw[i] % BASE62.length];
  return out;
};

const newOpaqueToken = (bytes = 24) => crypto.randomBytes(bytes).toString('base64url');

const validCode = (code) => typeof code === 'string'
  && code.length === CODE_LENGTH
  && [...code].every((ch) => BASE62.includes(ch));

const ULID_RE = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const validUlid = (id) => typeof id === 'string' && ULID_RE.test(id);

// ---------------------------------------------------------------- state

const state = {
  devices: new Map(), // deviceId -> { token, ownerToken }
  deviceLocal: new Map(), // `deviceId|source|localId` -> { imageId, updatedAt }
  photos: new Map(), // imageId -> photo
  codes: new Map(), // code -> imageId
  bySha256: new Map(), // sha256Original -> imageId
  byFingerprint: new Map(), // fingerprint of the thumbnail bytes -> imageId
  comments: new Map(), // commentId -> comment
  counters: {
    requests: 0,
    comments: 0,
    photos: 0,
    signBad: 0,
    rateLimited: 0,
    liveSubscribers: 0,
    lastBadReason: null,
  },
  // Shrunk or extended by /__control/rate-limit. Empty means "use the protocol's
  // numbers", which are the ones in the limiter below.
  rateOverrides: new Map(),
  budgets: new Map(), // windowKey -> { count, windowStart }
  slowMs: 0,
  failNext: 0,
  failStatus: 500,
};

// ---------------------------------------------------------------- rate limits

/**
 * Fixed-window budgets, one row per key per window, which is the shape the real
 * server uses. The key is a plain string here rather than an HMAC of one: this
 * process holds nothing worth protecting, and a readable key makes a 429 explain
 * itself in the log.
 */
const PROTOCOL_BUDGETS = [
  { match: /^dev:ip:.*:min$/, limit: 20, windowMs: 60000 },
  { match: /^dev:ip:.*:day$/, limit: 200, windowMs: 86400000 },
  { match: /^reg:dev:.*:hour$/, limit: 300, windowMs: 3600000 },
  { match: /^cmt:anon:.*:min$/, limit: 5, windowMs: 60000 },
  { match: /^cmt:anon:.*:day$/, limit: 50, windowMs: 86400000 },
  { match: /^cmt:ip:.*:min$/, limit: 30, windowMs: 60000 },
  { match: /^cmt:img:.*:hour$/, limit: 200, windowMs: 3600000 },
  { match: /^admin:login:.*$/, limit: 20, windowMs: 600000 },
];

function budgetFor(key) {
  const override = state.rateOverrides.get(key);
  if (override) return override;
  for (const b of PROTOCOL_BUDGETS) {
    if (b.match.test(key)) return b;
  }
  return null;
}

/**
 * Consumes one unit of a budget and reports whether it was there.
 *
 * Like the real server, the counter is incremented even when the answer is
 * "no": a client that keeps hammering a full budget does not get a fresh window
 * by being refused, and the limit is on attempts rather than on successes.
 */
function allow(key) {
  const budget = budgetFor(key);
  if (!budget || budget.limit <= 0) return true;
  const windowMs = budget.windowMs > 0 ? budget.windowMs : 60000;
  const now = Date.now();
  const windowStart = now - (now % windowMs);
  const row = state.budgets.get(key);
  if (!row || row.windowStart !== windowStart) {
    state.budgets.set(key, { windowStart, count: 1 });
    return 1 <= budget.limit;
  }
  row.count += 1;
  if (row.count > budget.limit) {
    console.log(`  [limit] ${key} is at ${row.count}/${budget.limit} in this window`);
    return false;
  }
  return true;
}

function clientIP(req) {
  const addr = req.socket.remoteAddress || '';
  return addr.replace(/^::ffff:/, '');
}

// ---------------------------------------------------------------- helpers

const isHex64 = (s) => typeof s === 'string' && /^[0-9a-f]{64}$/.test(s);

const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

/**
 * The mock's stand-in for "decode, normalise and re-encode the thumbnail".
 *
 * What the real server gets out of that step is a JPEG with no metadata at all.
 * This one strips APP1/Exif segments instead — the bytes a camera or a phone
 * would have put there — and refuses what the real decoder refuses.
 *
 * The component check is the one to keep. Go's `image/jpeg` rejects a
 * one-component JPEG with "unsupported JPEG feature: number of components", so a
 * grayscale thumbnail is a 400 from the real server. A mock that accepted it
 * would let a client ship that bug all the way to a TV, which is the one thing a
 * mock must not do: this was found by exactly that mismatch.
 */
function stripMetadata(buf) {
  if (buf.length < 4 || buf[0] !== 0xff || buf[1] !== 0xd8) {
    return { ok: false, reason: 'not a JPEG (no SOI marker)' };
  }
  const out = [Buffer.from([0xff, 0xd8])];
  let i = 2;
  let removed = 0;
  let components = 0;
  while (i + 1 < buf.length) {
    if (buf[i] !== 0xff) {
      // Past the last header: scan data and the EOI go out untouched.
      break;
    }
    const marker = buf[i + 1];
    if (marker === 0xd8 || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) {
      out.push(buf.subarray(i, i + 2));
      i += 2;
      continue;
    }
    if (marker === 0xda || marker === 0xd9) break;
    if (i + 3 >= buf.length) break;
    const len = buf.readUInt16BE(i + 2);
    if (len < 2 || i + 2 + len > buf.length) {
      return { ok: false, reason: 'truncated JPEG segment' };
    }
    // SOF0..SOF15 carry the frame header. The component count is the last byte of
    // the eight-byte fixed part: marker(2) length(2) precision(1) height(2)
    // width(2) components(1), so it lands at i+9. SOF4/SOF8/SOF12 are not frame
    // headers at all (DHT, JPG, DAC).
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      if (len < 8) return { ok: false, reason: 'truncated frame header' };
      components = buf[i + 9];
    }
    // APP1 is where Exif lives. Dropping it is the whole point of the step.
    if (marker !== 0xe1) out.push(buf.subarray(i, i + 2 + len));
    else removed++;
    i += 2 + len;
  }
  if (components === 0) return { ok: false, reason: 'no frame header, so no pixels' };
  if (components < 3) {
    return { ok: false, reason: `a ${components}-component JPEG is not decodable by the server` };
  }
  if (i < buf.length) out.push(buf.subarray(i));
  return { ok: true, bytes: Buffer.concat(out), removed, components };
}

function sendJson(res, obj, status = 200) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

/**
 * Every error in this protocol has one shape, and the message is written for the
 * person who caused it rather than for a log.
 */
const ERROR_MESSAGES = {
  bad_request: '这个请求看不懂',
  not_found: '没找到这个短码',
  rate_limited: '发得太快了，歇一会儿',
  content_rejected: '这句话里有不允许的内容',
  too_long: '太长了，最多 20 个字',
  photo_locked: '这张照片关闭了评论',
  unauthorized: '没有权限，或者链接已经过期',
  server_error: '服务器出错了',
};

function fail(res, code, status, message) {
  if (code === 'rate_limited') state.counters.rateLimited++;
  return sendJson(res, {
    error: { code, message: message || ERROR_MESSAGES[code] || '出错了' },
  }, status);
}

const unauthorized = (res, why) => {
  state.counters.signBad++;
  state.counters.lastBadReason = why;
  console.log(`  [401] ${why}`);
  return fail(res, 'unauthorized', 401);
};

const badRequest = (res, why) => {
  state.counters.lastBadReason = why;
  console.log(`  [400] ${why}`);
  return fail(res, 'bad_request', 400);
};

function sendHtml(res, html, status = 200, headers = {}) {
  const body = Buffer.from(html, 'utf8');
  res.writeHead(status, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    ...headers,
  });
  res.end(body);
}

const escapeHtml = (s) => String(s)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;');

function readBody(req, limit = 1 << 20) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('body too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

async function readJson(req) {
  const raw = await readBody(req);
  if (!raw.length) throw new Error('empty body');
  return JSON.parse(raw.toString('utf8'));
}

/**
 * Multipart parsing, only as far as this protocol needs it: the two parts are
 * `meta` (a JSON string) and `thumb` (bytes), and anything else is ignored.
 *
 * The boundary comes from the header, and a body that does not contain the two
 * closing dashes is a truncated upload rather than an empty one — that
 * distinction is what turns "the phone's connection dropped" into a clear 400.
 */
function parseMultipart(buf, boundary) {
  const delim = Buffer.from(`--${boundary}`);
  const parts = [];
  let start = buf.indexOf(delim);
  if (start < 0) return null;
  start += delim.length;
  while (start < buf.length) {
    if (buf[start] === 0x2d && buf[start + 1] === 0x2d) return parts; // closing --
    // Skip the CRLF that follows the boundary.
    if (buf[start] === 0x0d && buf[start + 1] === 0x0a) start += 2;
    const headerEnd = buf.indexOf('\r\n\r\n', start);
    if (headerEnd < 0) return null;
    const header = buf.subarray(start, headerEnd).toString('utf8');
    const bodyStart = headerEnd + 4;
    const next = buf.indexOf(delim, bodyStart);
    if (next < 0) return null;
    // The CRLF before the next boundary belongs to the delimiter, not the body.
    let bodyEnd = next;
    if (buf[bodyEnd - 2] === 0x0d && buf[bodyEnd - 1] === 0x0a) bodyEnd -= 2;
    const nameMatch = /name="([^"]*)"/.exec(header);
    parts.push({ name: nameMatch ? nameMatch[1] : '', body: buf.subarray(bodyStart, bodyEnd) });
    start = next + delim.length;
  }
  return parts;
}

// ---------------------------------------------------------------- identity

/**
 * The whole of §3.2's decision tree, minus the perceptual layer.
 *
 * Order matters and is the protocol's: sha256Original first (the album held the
 * original bytes, which is strictly more identity than anything derived from a
 * thumbnail), then "this device already registered this photo" (the
 * pre-registration cost control, answered from the stored row), then the bytes
 * themselves.
 */
function resolveIdentity({ deviceId, source, localId, sha256Original, thumbBytes, width, height }) {
  if (sha256Original && state.bySha256.has(sha256Original)) {
    const imageId = state.bySha256.get(sha256Original);
    if (state.photos.has(imageId)) return { imageId, matched: 'sha256' };
    state.bySha256.delete(sha256Original);
  }

  if (deviceId && localId) {
    const key = `${deviceId}|${source}|${localId}`;
    const remembered = state.deviceLocal.get(key);
    if (remembered && Date.now() - remembered.updatedAt <= 30 * 24 * 3600 * 1000) {
      const photo = state.photos.get(remembered.imageId);
      if (photo) {
        // Answered from the stored row: the thumbnail is not consulted at all,
        // which is the point of pre-registering.
        remembered.updatedAt = Date.now();
        return { imageId: photo.id, matched: '' };
      }
      state.deviceLocal.delete(key);
    }
  }

  const fingerprint = sha256(thumbBytes);
  if (state.byFingerprint.has(fingerprint)) {
    const imageId = state.byFingerprint.get(fingerprint);
    if (state.photos.has(imageId)) return { imageId, matched: 'phash' };
    state.byFingerprint.delete(fingerprint);
  }

  return { imageId: null, matched: 'new', fingerprint };
}

function rememberDevicePhoto(deviceId, source, localId, imageId) {
  if (!deviceId || !localId) return;
  state.deviceLocal.set(`${deviceId}|${source}|${localId}`, { imageId, updatedAt: Date.now() });
}

// ---------------------------------------------------------------- signatures

/**
 * The HMAC key for signed thumbnail URLs. A fresh one per process is deliberate:
 * a URL signed by one run of the mock must not keep working against the next, and
 * nothing outside this file is expected to be able to forge or verify it.
 */
const URL_SECRET = crypto.randomBytes(32);

const signThumb = (code, exp, width) => crypto
  .createHmac('sha256', URL_SECRET)
  .update(`${code}|${exp}|${width}`)
  .digest('base64url');

const thumbUrl = (code, width = 320) => {
  const exp = Date.now() + 10 * 60 * 1000;
  return `${baseUrl()}/t/${code}?w=${width}&exp=${exp}&sig=${signThumb(code, exp, width)}`;
};

/**
 * The base URL that goes into a QR code and into a signed URL.
 *
 * MOCK_REVIEW_BASE_URL exists because the address a phone must reach is not
 * always the address the test talks to — a container, or a second NIC. Defaulting
 * to loopback keeps `node verify.js` on one machine working with no configuration.
 */
function baseUrl() {
  return (process.env.MOCK_REVIEW_BASE_URL || `http://127.0.0.1:${PORT}`).replace(/\/+$/, '');
}

const qrUrl = (code, size = 440) => `${baseUrl()}/v1/qr/${code}.png?size=${size}`;

// Rebuilt from the request's Host when the mock is reached over the LAN, so a
// phone that opens the page gets a thumbnail URL that phone can also fetch.
function requestBaseUrl(req) {
  if (process.env.MOCK_REVIEW_BASE_URL) return baseUrl();
  const host = req.headers.host;
  return host ? `http://${host}` : baseUrl();
}

// ---------------------------------------------------------------- HTML page

/**
 * The CSS every page of this mock shares.
 *
 * It moved out of `renderPage` when the admin door added two more pages (§17.15): three
 * copies of the same palette is three places for the mock's page and the real server's
 * page to drift apart, and "the two implementations look the same" is the whole reason
 * this file renders HTML at all.
 */
const SHARED_CSS = `
  :root { color-scheme: light dark; --bg:#fff; --fg:#1b1b1f; --muted:#6b6b74; --line:#e3e3e8; --card:#f6f6f8; --accent:#3355cc; --ok:#1f7a3d; --bad:#b3261e; }
  @media (prefers-color-scheme: dark) { :root { --bg:#121215; --fg:#ecedf1; --muted:#a0a0ab; --line:#2c2c33; --card:#1b1b21; --accent:#8fa8ff; --ok:#6ee7a0; --bad:#ff9d95; } }
  * { box-sizing: border-box; }
  body { margin: 0 auto; padding: 16px; max-width: 560px; background: var(--bg); color: var(--fg);
         font: 16px/1.55 -apple-system, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif; -webkit-text-size-adjust: 100%; }
  h1 { font-size: 17px; margin: 18px 0 8px; }
  .hint { color: var(--muted); font-size: 14px; margin: 0 0 12px; }
  label { display: block; font-size: 14px; color: var(--muted); margin: 10px 0 4px; }
  input[type=text], input[type=password], textarea { width: 100%; font: inherit; font-size: 16px; color: var(--fg); background: var(--card);
                               border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; }
  textarea { min-height: 76px; resize: vertical; }
  button { margin-top: 14px; width: 100%; font: inherit; font-size: 17px; font-weight: 600; color: #fff;
           background: var(--accent); border: 0; border-radius: 10px; padding: 13px 16px; }
  .notice { border-radius: 8px; padding: 10px 12px; margin: 0 0 14px; font-size: 15px; }
  .notice.ok { background: var(--card); color: var(--ok); border: 1px solid var(--ok); }
  .notice.bad { background: var(--card); color: var(--bad); border: 1px solid var(--bad); }
  a { color: var(--accent); }
  footer { color: var(--muted); font-size: 13px; margin: 22px 0 8px; text-align: center; }
  /* The admin's controls (§17.15). A guest never receives these elements, so none of
     this is about them; they are simply absent when .admin is false. */
  .adminbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin: 0 0 14px;
              padding: 8px 12px; background: var(--card); border: 1px solid var(--line); border-radius: 8px;
              font-size: 14px; color: var(--muted); }
  form.inline { display: inline; margin: 0; padding: 0; }
  .adminbar button, .admin button, .admin a { display: inline-block; width: auto; margin: 0; padding: 5px 12px;
              font: inherit; font-size: 14px; font-weight: 500; line-height: 1.4; text-decoration: none;
              color: var(--fg); background: var(--bg); border: 1px solid var(--line); border-radius: 6px; }
  .admin .remove { color: var(--bad); border-color: var(--bad); }
`;

/**
 * The H5 page, server-rendered with no JavaScript at all (§10.1).
 *
 * Two rules are load-bearing and are why this is a template function rather than
 * a file: nothing in it may reference https (on a LAN the page is plain http and
 * a single https asset is blocked as mixed content), and nothing in it may be a
 * <script>. A failing form comes back through here with the typed text in it,
 * which is the difference between "too fast, try again" and retyping a comment
 * on a phone keyboard.
 *
 * `admin` and `adminLoginUrl` are the whole of the difference between what a guest
 * gets and what a logged-in admin gets (§17.15): a bar at the top, 编辑 / 删除 under
 * each comment, and — for the guest — the footer link that opens the login form.
 */
function renderPage({ code, thumb, comments, nickname, content, idem, notice, noticeKind, channel, admin, adminLoginUrl }) {
  const rows = comments.map((c) => `
    <li class="${c.mine ? 'mine' : ''}">
      <span class="who">${escapeHtml(c.nickname)}</span><span class="when">${escapeHtml(c.when)}</span>
      <p class="text">${escapeHtml(c.content)}</p>
      ${admin ? `<div class="admin">
        <a href="/c/${escapeHtml(code)}/edit/${escapeHtml(c.id)}">编辑</a>
        <form class="inline" method="post" action="/c/${escapeHtml(code)}/delete/${escapeHtml(c.id)}">
          <button class="remove" type="submit">删除</button>
        </form>
      </div>` : ''}
    </li>`).join('');

  const banner = notice
    ? `<p class="notice ${noticeKind}">${escapeHtml(notice)}</p>`
    : '';

  const adminBar = admin ? `<div class="adminbar">
  <span>管理员模式 · 可以编辑和删除评论</span>
  <form class="inline" method="post" action="/admin/logout"><button type="submit">退出</button></form>
</div>` : '';

  const loginLink = adminLoginUrl && !admin
    ? ` · <a href="${escapeHtml(adminLoginUrl)}">管理员登录</a>`
    : '';

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>图评</title>
<style>${SHARED_CSS}
  img.photo { display: block; width: 100%; height: auto; border-radius: 10px; background: var(--card); }
  ul.comments { list-style: none; margin: 22px 0 0; padding: 14px 0 0; border-top: 1px solid var(--line); }
  ul.comments li { padding: 10px 12px; border-bottom: 1px solid var(--line); }
  ul.comments li.mine { background: var(--card); border-radius: 8px; }
  .who { font-weight: 600; }
  .when { color: var(--muted); font-size: 13px; margin-left: 8px; }
  .text { margin: 2px 0 0; white-space: pre-wrap; word-break: break-word; }
  .empty { color: var(--muted); margin: 22px 0 0; padding-top: 14px; border-top: 1px solid var(--line); }
  form { padding-top: 4px; }
  ul.comments li .admin { display: flex; gap: 8px; margin-top: 8px; }
</style>
</head>
<body>
${adminBar}
${banner}
<img class="photo" src="${escapeHtml(thumb)}" alt="照片">
<h1>评论 ${comments.length}</h1>
<p class="hint">这张照片的评论所有人都能看见。别写个人信息。</p>
<form method="post" action="/c/${escapeHtml(code)}">
  <label for="nickname">昵称（可留空，默认匿名）</label>
  <input id="nickname" name="nickname" type="text" maxlength="12" value="${escapeHtml(nickname)}">
  <label for="content">说点什么</label>
  <textarea id="content" name="content" maxlength="20" required>${escapeHtml(content)}</textarea>
  <input type="hidden" name="idem" value="${escapeHtml(idem)}">
  <button type="submit">提交</button>
</form>
${rows ? `<ul class="comments">${rows}</ul>` : '<p class="empty">还没有人评论过这张照片。</p>'}
<footer>图评 · 局域网自托管 · ${escapeHtml(channel)}${loginLink}</footer>
</body>
</html>
`;
}

/**
 * The admin's login page (§17.15). Same rules as the guest page: no script, one plain
 * form POST, and a 303 back to a GET.
 *
 * `next` is a hidden field rather than something this page builds into a link, because
 * the server re-checks it (safeNext): an open redirect on the one page where a person
 * types a secret is how the secret gets collected.
 */
function renderLoginPage({ next = '', loggedIn = false, notice = '', noticeKind = '' }) {
  const banner = notice ? `<p class="notice ${noticeKind}">${escapeHtml(notice)}</p>` : '';
  const body = loggedIn
    ? `<p class="hint">已经登录了。登录期间，照片页上的每条评论旁边会出现 编辑 和 删除。</p>
${next ? `<p class="hint"><a href="${escapeHtml(next)}">回到刚才那一页</a></p>` : ''}
<form class="signout" method="post" action="/admin/logout">
  <button type="submit">退出管理员</button>
</form>`
    : `<p class="hint">输入部署时设置的 <code>MOCK_REVIEW_ADMIN_TOKEN</code>（默认 <code>admin</code>）。登录后可以编辑和删除评论，匿名访客发评论不受影响。</p>
<form method="post" action="/admin">
  <label for="token">管理员令牌</label>
  <input id="token" name="token" type="password" required autocomplete="current-password" autofocus>
  <input type="hidden" name="next" value="${escapeHtml(next)}">
  <button type="submit">登录</button>
</form>`;

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>图评 · 管理员</title>
<style>${SHARED_CSS}
  form.signout button { color: var(--fg); background: var(--card); border: 1px solid var(--line); }
</style>
</head>
<body>
${banner}
<h1>图评管理员</h1>
${body}
<footer>图评 · 局域网自托管</footer>
</body>
</html>
`;
}

/**
 * One comment, admin only (§17.15). A separate page rather than a textarea under every
 * comment: the guest page is opened by strangers on phones, and a form per comment would
 * grow it for a control only the admin ever sees.
 */
function renderEditPage({ code, id, nickname, content, when, notice = '', noticeKind = '' }) {
  const banner = notice ? `<p class="notice ${noticeKind}">${escapeHtml(notice)}</p>` : '';
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>图评 · 编辑评论</title>
<style>${SHARED_CSS}
  form { padding-top: 4px; }
  form.remove button { color: var(--bad); background: var(--card); border: 1px solid var(--bad); }
</style>
</head>
<body>
${banner}
<h1>编辑评论</h1>
<p class="hint">原话发表于 ${escapeHtml(when)}。</p>
<form method="post" action="/c/${escapeHtml(code)}/edit/${escapeHtml(id)}">
  <label for="nickname">昵称</label>
  <input id="nickname" name="nickname" type="text" maxlength="12" value="${escapeHtml(nickname)}" autocomplete="nickname">
  <label for="content">内容</label>
  <textarea id="content" name="content" maxlength="20" required>${escapeHtml(content)}</textarea>
  <button type="submit">保存</button>
</form>
<form class="remove" method="post" action="/c/${escapeHtml(code)}/delete/${escapeHtml(id)}">
  <button type="submit">删除这条评论</button>
</form>
<footer><a href="/c/${escapeHtml(code)}">回到这张照片</a></footer>
</body>
</html>
`;
}

/**
 * "how long ago", computed here because the page has no script to compute it.
 * Past 24 hours it is a date, exactly as §10.1 asks.
 */
function relativeTime(createdAt) {
  const delta = Date.now() - createdAt;
  if (delta < 60000) return '刚刚';
  if (delta < 3600000) return `${Math.floor(delta / 60000)} 分钟前`;
  if (delta < 86400000) return `${Math.floor(delta / 3600000)} 小时前`;
  const d = new Date(createdAt);
  const month = d.getMonth() + 1;
  if (d.getFullYear() === new Date().getFullYear()) return `${month}月${d.getDate()}日`;
  return `${d.getFullYear()}年${month}月${d.getDate()}日`;
}

function renderErrorPage(code, status, title, detail) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${escapeHtml(title)}</title></head>
<body style="font:16px/1.6 system-ui, sans-serif; margin:24px auto; max-width:560px; padding:0 16px">
<h1 style="font-size:18px">${escapeHtml(title)}</h1>
<p>${escapeHtml(detail)}</p>
<p style="color:#6b6b74;font-size:14px">错误码：${escapeHtml(code)}（HTTP ${status}）</p>
</body>
</html>
`;
}

// ---------------------------------------------------------------- content

/**
 * The advertisement patterns §8 names. These are the traps the design calls out
 * by name, so they are refused rather than quietly held for review.
 */
const REJECT_SUBSTRINGS = ['加微信', '微信号', '优惠券', '代购', '刷单'];
const REJECT_PATTERNS = [
  { name: 'url-http', test: (s) => s.includes('http://') },
  { name: 'url-https', test: (s) => s.includes('https://') },
  { name: 'url-www', test: (s) => s.includes('www.') },
  { name: 'url-com', test: (s) => s.includes('.com') },
  // An 11-digit run starting with 1, which is what a mainland mobile number is.
  { name: 'phone', test: (s) => /(?:^|\D)1\d{10}(?!\d)/.test(s) },
];

// Matches guard.MaxContentRunes: what a television can draw on one danmaku line at the
// 45%-of-screen ceiling — about 21 CJK characters at the layer's largest font.
const MAX_CONTENT_RUNES = 20;
const MAX_NICKNAME_RUNES = 12;
// Matches guard.DefaultNickname. It was 微信用户, which told a guest on a page with no
// login that their comment came from a WeChat account (§17.11).
const DEFAULT_NICKNAME = '匿名';

const runeLength = (s) => [...s].length;

/**
 * Screens one comment. Returns a verdict rather than throwing, because the two
 * refusals have different error codes and the page has to say which.
 */
function screen(content) {
  const trimmed = content.trim();
  if (!trimmed) {
    return { verdict: 'reject', code: 'bad_request', detail: '说点什么吧' };
  }
  if (runeLength(trimmed) > MAX_CONTENT_RUNES) {
    return {
      verdict: 'reject',
      code: 'too_long',
      detail: `最多 ${MAX_CONTENT_RUNES} 个字，现在有 ${runeLength(trimmed)} 个`,
    };
  }
  for (const word of REJECT_SUBSTRINGS) {
    if (trimmed.includes(word)) {
      return { verdict: 'reject', code: 'content_rejected', detail: '这句话里有不允许的内容', matched: word };
    }
  }
  for (const pattern of REJECT_PATTERNS) {
    if (pattern.test(trimmed)) {
      return { verdict: 'reject', code: 'content_rejected', detail: '不能发广告或联系方式', matched: pattern.name };
    }
  }
  return { verdict: 'allow' };
}

function normalizeNickname(nickname) {
  let out = String(nickname || '').trim().replace(/\s+/g, ' ');
  if (!out) out = DEFAULT_NICKNAME;
  if (runeLength(out) > MAX_NICKNAME_RUNES) out = [...out].slice(0, MAX_NICKNAME_RUNES).join('');
  return out;
}

// ---------------------------------------------------------------- comments

const DUPLICATE_WINDOW_MS = 5 * 60 * 1000;
const REPORT_THRESHOLD = 3;

/**
 * Inserts a comment unless it is a double tap or a retry.
 *
 * Two separate rules, in this order, because they answer different questions:
 *
 *   - the idempotency key answers "the same submission, sent twice" and must
 *     return the original id even minutes later;
 *   - the duplicate window answers "the same words on the same photo inside five
 *     minutes" and is a content rule, not a transport one.
 */
function addComment({ imageId, content, nickname, anonId, client, idemKey }) {
  if (idemKey) {
    for (const c of state.comments.values()) {
      if (c.imageId === imageId && c.idemKey === idemKey) return { comment: c, created: false };
    }
  }

  const since = Date.now() - DUPLICATE_WINDOW_MS;
  let newest = null;
  for (const c of state.comments.values()) {
    if (c.imageId !== imageId || c.content !== content || c.createdAt < since) continue;
    if (c.status !== 1 && c.status !== 0) continue;
    if (!newest || c.createdAt > newest.createdAt) newest = c;
  }
  if (newest) return { comment: newest, created: false };

  const comment = {
    id: newId(),
    imageId,
    content,
    nickname,
    anonId,
    client,
    idemKey,
    status: 1,
    reportCount: 0,
    createdAt: Date.now(),
  };
  state.comments.set(comment.id, comment);
  state.counters.comments++;
  const photo = state.photos.get(imageId);
  if (photo) photo.commentCount = commentsFor(imageId).length;
  return { comment, created: true };
}

function commentsFor(imageId) {
  return [...state.comments.values()]
    .filter((c) => c.imageId === imageId)
    .sort((a, b) => (a.createdAt - b.createdAt) || (a.id < b.id ? -1 : 1));
}

const visibleComments = (imageId) => commentsFor(imageId).filter((c) => c.status === 1);

const commentJson = (c) => ({
  id: c.id,
  imageId: c.imageId,
  nickname: c.nickname,
  content: c.content,
  createdAt: c.createdAt,
  status: c.status,
});

// ---------------------------------------------------------------- live sockets

/**
 * One hub, keyed by image id. A subscriber is a buffered queue that is dropped
 * from rather than blocked on: a TV that has stopped reading must never be able
 * to stall the HTTP handler that is writing somebody's comment.
 */
const hub = new Map(); // imageId -> Set<{ socket, queue, ... }>

const LIVE_QUEUE_MAX = 32;

function subscribe(imageId, ws) {
  const sub = { ws, queue: [], timer: null };
  if (!hub.has(imageId)) hub.set(imageId, new Set());
  hub.get(imageId).add(sub);
  state.counters.liveSubscribers = [...hub.values()].reduce((n, set) => n + set.size, 0);

  sub.timer = setInterval(() => push(sub, { t: 'ping' }), pingMs);
  if (sub.timer.unref) sub.timer.unref();
  return sub;
}

function unsubscribe(imageId, sub) {
  clearInterval(sub.timer);
  const set = hub.get(imageId);
  if (set) {
    set.delete(sub);
    if (!set.size) hub.delete(imageId);
  }
  state.counters.liveSubscribers = [...hub.values()].reduce((n, set) => n + set.size, 0);
}

function push(sub, message) {
  if (sub.ws.readyState !== 1) return;
  // Never let a slow reader grow this without bound; the next history fetch is
  // what fills the gap, which is the whole reason the push is allowed to drop.
  if (sub.queue.length >= LIVE_QUEUE_MAX) sub.queue.shift();
  sub.queue.push(message);
  if (sub.queue.length === 1) drain(sub);
}

function drain(sub) {
  while (sub.queue.length && sub.ws.readyState === 1) {
    const message = sub.queue.shift();
    sub.ws.send(JSON.stringify(message), (err) => {
      if (err) sub.queue.length = 0;
    });
  }
}

/**
 * Tells everyone watching a photo what a comment now says (§17.16).
 *
 * One message per state, not one per event: the same shape announces a new comment, an
 * edited one, and one that has just been held or deleted, and it carries the comment's
 * *actual* status. A hard-coded `status: 1` here (which is what this sent while the only
 * publisher was "a new comment arrived") would leave a deleted comment being replayed on
 * every television showing that photo — the client replaces what it holds by id, and 3 is
 * what makes the danmaku layer stop drawing it.
 */
function publishComment(imageId, comment) {
  const set = hub.get(imageId);
  if (!set) return 0;
  const message = {
    t: 'comment',
    id: comment.id,
    nickname: comment.nickname,
    content: comment.content,
    createdAt: comment.createdAt,
    status: comment.status,
  };
  for (const sub of set) push(sub, message);
  return set.size;
}

// ---------------------------------------------------------------- thumbnails

function serveThumb(req, res, url, code) {
  if (!validCode(code)) return unauthorized(res, `thumbnail: malformed code "${code}"`);

  const width = url.searchParams.get('w');
  const exp = url.searchParams.get('exp');
  const sig = url.searchParams.get('sig') || '';

  if (!sig) return unauthorized(res, `thumbnail ${code}: no signature`);
  if (!exp || !/^\d+$/.test(exp)) return unauthorized(res, `thumbnail ${code}: bad exp "${exp}"`);
  if (!width || !/^\d+$/.test(width)) return unauthorized(res, `thumbnail ${code}: bad w "${width}"`);

  const expected = signThumb(code, Number(exp), Number(width));
  if (sig.length !== expected.length
      || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) {
    return unauthorized(res, `thumbnail ${code}: signature mismatch`);
  }
  // Expiry is checked after the signature on purpose: an unsigned request must
  // not be able to tell "expired" from "forged".
  if (Number(exp) < Date.now()) return unauthorized(res, `thumbnail ${code}: signature expired`);

  const imageId = state.codes.get(code);
  const photo = imageId ? state.photos.get(imageId) : null;
  if (!photo || !photo.thumbBytes) {
    return fail(res, 'not_found', 404, '缩略图已过期');
  }

  const body = photo.thumbBytes;
  res.writeHead(200, {
    'Content-Type': 'image/jpeg',
    'Content-Length': body.length,
    'Cache-Control': 'private, max-age=600',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(body);
}

// ---------------------------------------------------------------- QR

const clampQrSize = (size) => {
  if (!Number.isFinite(size) || size <= 0) return 440;
  if (size < 96) return 96;
  if (size > 1024) return 1024;
  return Math.round(size);
};

/**
 * A real QR code, not a placeholder square: the TV's whole job is to be scanned,
 * and a mock that returned a grid of noise would pass a byte-count check while
 * being useless on a screen. `margin: 4` is the four-module quiet zone the design
 * depends on — the TV draws this PNG on top of a photograph, and a code with a
 * thin margin is a code that does not scan. The PNG comes back at exactly `size`
 * pixels on both axes, which is more than the protocol's "at least".
 */
async function serveQr(res, code, size) {
  const imageId = state.codes.get(code);
  if (!imageId || !state.photos.has(imageId)) return fail(res, 'not_found', 404, '这个短码不认得了');

  try {
    const png = await QRCode.toBuffer(`${baseUrl()}/c/${code}`, {
      type: 'png',
      errorCorrectionLevel: 'M',
      width: clampQrSize(size),
      margin: 4,
    });
    res.writeHead(200, {
      'Content-Type': 'image/png',
      'Content-Length': png.length,
      'Cache-Control': 'public, max-age=86400, immutable',
      'X-Content-Type-Options': 'nosniff',
    });
    return res.end(png);
  } catch (error) {
    console.log(`  [500] QR render failed: ${error.message}`);
    return fail(res, 'server_error', 500);
  }
}

// ---------------------------------------------------------------- device auth

const requireDevice = (req, res) => {
  const id = req.headers['x-device-id'];
  const token = req.headers['x-device-token'];
  if (!id || !token) {
    unauthorized(res, `${req.method} ${req.url}: missing device headers`);
    return null;
  }
  const device = state.devices.get(String(id));
  if (!device || device.token !== String(token)) {
    unauthorized(res, `${req.method} ${req.url}: unknown device or wrong token (${id})`);
    return null;
  }
  return { id: String(id), device };
};

// ---------------------------------------------------------------- handlers

async function handleDeviceRegister(req, res) {
  let body;
  try {
    body = await readJson(req);
  } catch {
    return badRequest(res, 'POST /v1/devices: body is not JSON');
  }

  const ip = clientIP(req);
  if (!allow(`dev:ip:${ip}:min`) || !allow(`dev:ip:${ip}:day`)) {
    return fail(res, 'rate_limited', 429, '请求太频繁了');
  }

  let deviceId = typeof body.deviceId === 'string' ? body.deviceId.trim() : '';
  const name = typeof body.name === 'string' ? body.name : '';
  const minted = !deviceId;
  if (minted) deviceId = newId();

  const existing = state.devices.get(deviceId);
  if (existing) {
    // A device the server has already issued must prove it holds the token. This
    // is the rule that stops anyone from re-issuing themselves somebody else's
    // device, and it is checked before anything is rotated.
    if (!body.token || String(body.token) !== existing.token) {
      return unauthorized(res, `POST /v1/devices: device ${deviceId} presented no or a wrong token`);
    }
  }

  const device = { token: newOpaqueToken(), ownerToken: newOpaqueToken(), name };
  state.devices.set(deviceId, device);

  console.log(`  [devices] ${deviceId} ${existing ? 'rotated' : minted ? 'minted' : 'new'}${name ? ` name=${name}` : ''}`);
  return sendJson(res, {
    deviceId,
    deviceToken: device.token,
    ownerToken: device.ownerToken,
    baseUrl: requestBaseUrl(req),
    serverTime: Date.now(),
  });
}

async function handlePhotoRegister(req, res) {
  const identity = requireDevice(req, res);
  if (!identity) return;

  if (!allow(`reg:dev:${identity.id}:hour`)) {
    return fail(res, 'rate_limited', 429, '注册太频繁了');
  }

  const contentType = String(req.headers['content-type'] || '');
  const boundaryMatch = /boundary=([^;]+)/.exec(contentType);
  if (!contentType.includes('multipart/form-data') || !boundaryMatch) {
    return badRequest(res, 'POST /v1/photos: not multipart/form-data');
  }

  let raw;
  try {
    raw = await readBody(req, 9 << 20);
  } catch (error) {
    return badRequest(res, `POST /v1/photos: ${error.message}`);
  }

  const parts = parseMultipart(raw, boundaryMatch[1].trim().replace(/^"|"$/g, ''));
  if (!parts) return badRequest(res, 'POST /v1/photos: multipart body is truncated');

  const metaPart = parts.find((p) => p.name === 'meta');
  const thumbPart = parts.find((p) => p.name === 'thumb');

  // Both parts are required by the protocol: meta carries the identity hints, and
  // the thumbnail is the preview the phone will see.
  if (!metaPart) return badRequest(res, 'POST /v1/photos: missing meta part');
  if (!thumbPart || !thumbPart.body.length) return badRequest(res, 'POST /v1/photos: missing thumb part');

  let meta;
  try {
    meta = JSON.parse(metaPart.body.toString('utf8'));
  } catch {
    return badRequest(res, 'POST /v1/photos: meta is not valid JSON');
  }
  if (meta === null || typeof meta !== 'object') {
    return badRequest(res, 'POST /v1/photos: meta is not an object');
  }

  let sha256Original = null;
  if (meta.sha256Original !== null && meta.sha256Original !== undefined && meta.sha256Original !== '') {
    if (!isHex64(String(meta.sha256Original).toLowerCase())) {
      return badRequest(res, `POST /v1/photos: sha256Original is not 64 lowercase hex (${String(meta.sha256Original).slice(0, 20)})`);
    }
    sha256Original = String(meta.sha256Original).toLowerCase();
  }

  const cleaned = stripMetadata(thumbPart.body);
  if (!cleaned.ok) return badRequest(res, `POST /v1/photos: thumb rejected: ${cleaned.reason}`);

  return registerPhoto(req, res, {
    deviceId: identity.id,
    source: typeof meta.source === 'string' && meta.source ? meta.source : 'unknown',
    localId: typeof meta.localId === 'string' ? meta.localId : '',
    sha256Original,
    thumbBytes: cleaned.bytes,
    width: Number.isFinite(meta.width) ? Math.trunc(meta.width) : 0,
    height: Number.isFinite(meta.height) ? Math.trunc(meta.height) : 0,
    stripped: cleaned.removed,
  });
}

/** Shared by POST /v1/photos and /__control/register, so the two cannot drift. */
function registerPhoto(req, res, input) {
  const resolved = resolveIdentity(input);
  let photo = resolved.imageId ? state.photos.get(resolved.imageId) : null;

  if (photo) {
    // An identity that already exists keeps its code and its first_seen_at, and
    // gains whichever of the new facts it was missing.
    photo.lastSeenAt = Date.now();
    if (input.sha256Original && !photo.sha256Original) {
      photo.sha256Original = input.sha256Original;
      state.bySha256.set(input.sha256Original, photo.id);
    }
    if (input.thumbBytes && !photo.thumbBytes) photo.thumbBytes = input.thumbBytes;
    if (input.width && input.height) {
      photo.width = input.width;
      photo.height = input.height;
    }
  } else {
    const id = newId();
    photo = {
      id,
      code: null,
      sha256Original: input.sha256Original,
      fingerprint: resolved.fingerprint || sha256(input.thumbBytes),
      thumbBytes: input.thumbBytes,
      width: input.width,
      height: input.height,
      visibility: 1,
      scope: 'image',
      deviceId: input.deviceId,
      firstSeenAt: Date.now(),
      lastSeenAt: Date.now(),
      commentCount: 0,
    };
    state.photos.set(id, photo);
    state.byFingerprint.set(photo.fingerprint, id);
    if (photo.sha256Original) state.bySha256.set(photo.sha256Original, id);
    state.counters.photos++;
  }

  const code = photo.code || issueCode(photo);
  rememberDevicePhoto(input.deviceId, input.source, input.localId, photo.id);
  if (input.sha256Original && !photo.sha256Original) {
    photo.sha256Original = input.sha256Original;
    state.bySha256.set(input.sha256Original, photo.id);
  }

  console.log(
    `  [photos] ${photo.id} code=${code} matched=${resolved.matched || 'reused'}`
    + `${input.stripped ? ` stripped=${input.stripped}` : ''}`,
  );

  const base = requestBaseUrl(req);
  return sendJson(res, {
    imageId: photo.id,
    code,
    matched: resolved.matched,
    qrUrl: `${base}/v1/qr/${code}.png?size=440`,
    thumbUrl: thumbUrl(code, 320).replace(baseUrl(), base),
    commentCount: visibleComments(photo.id).length,
    scope: photo.scope,
    serverTime: Date.now(),
  });
}

/**
 * Mints a code for a photo. The retry loop is not decoration: a duplicate code
 * would hand one stranger another stranger's photo, so "practically never" is
 * not good enough.
 */
function issueCode(photo) {
  for (let attempt = 0; attempt < 32; attempt++) {
    const code = newCode();
    if (!state.codes.has(code)) {
      state.codes.set(code, photo.id);
      photo.code = code;
      return code;
    }
  }
  throw new Error('could not mint a unique code');
}

/**
 * The body `GET /v1/photos/{code}` and `GET /v1/photos/by-local` both answer with.
 *
 * Shared so the two cannot drift: the album end keys its cache by local id and then fetches
 * comments by `imageId`, so two endpoints disagreeing about that id would be two threads for
 * one picture (§17.28).
 */
function photoDetailBody(req, photo, code) {
  const base = requestBaseUrl(req);
  return {
    imageId: photo.id,
    code,
    scope: photo.scope,
    commentCount: visibleComments(photo.id).length,
    thumbUrl: thumbUrl(code, 320).replace(baseUrl(), base),
    qrUrl: `${base}/v1/qr/${code}.png?size=440`,
    width: photo.width,
    height: photo.height,
    visibility: photo.visibility,
    serverTime: Date.now(),
  };
}

function handlePhotoGet(req, res, code) {
  if (!validCode(code)) return fail(res, 'not_found', 404, '短码无效');
  const imageId = state.codes.get(code);
  const photo = imageId ? state.photos.get(imageId) : null;
  if (!photo) return fail(res, 'not_found', 404, '这张照片不在图评里');
  return sendJson(res, photoDetailBody(req, photo, code));
}

/**
 * "Have I already registered this picture?" answered as a **read** (§17.28).
 *
 * The album end's only handle on a photo before the server has spoken is the id its own
 * library uses, and this is what turns it into a code — without uploading a thumbnail, and
 * without creating anything. A miss is a 404 and means exactly one thing: nobody has opened
 * 图评 on this photo from this device.
 *
 * Nothing here writes, and there is deliberately no freshness window over `deviceLocal`: the
 * window in the real service is about whether a thumbnail has to be sent again, which is a
 * question about bytes, while this one is about identity.
 */
function handlePhotoByLocal(req, res, url) {
  const identity = requireDevice(req, res);
  if (!identity) return;

  const source = String(url.searchParams.get('source') || '').trim();
  const localId = String(url.searchParams.get('localId') || '').trim();
  if (!source || !localId) {
    return badRequest(res, 'GET /v1/photos/by-local: needs both source and localId');
  }

  const remembered = state.deviceLocal.get(`${identity.id}|${source}|${localId}`);
  const photo = remembered ? state.photos.get(remembered.imageId) : null;
  if (!photo || !photo.code) {
    return fail(res, 'not_found', 404, '这台设备还没给这张照片开过图评');
  }
  return sendJson(res, photoDetailBody(req, photo, photo.code));
}

/**
 * The album owner's one-press delete. Everything goes: the photo, its code, its
 * thumbnail and every comment on it, so the code and the signed URL both stop
 * resolving immediately afterwards.
 */
function handlePhotoDelete(req, res, id) {
  const identity = requireDevice(req, res);
  if (!identity) return;

  const photo = state.photos.get(id);
  if (!photo) return fail(res, 'not_found', 404, '这张照片不在图评里');

  const ownerToken = String(req.headers['x-owner-token'] || '');
  if (!ownerToken || ownerToken !== identity.device.ownerToken) {
    return unauthorized(res, `DELETE /v1/photos/${id}: wrong owner token`);
  }

  const removed = [photo.id];
  if (photo.code) state.codes.delete(photo.code);
  state.photos.delete(photo.id);
  if (photo.sha256Original) state.bySha256.delete(photo.sha256Original);
  state.byFingerprint.delete(photo.fingerprint);
  for (const [key, value] of state.deviceLocal) {
    if (value.imageId === photo.id) state.deviceLocal.delete(key);
  }
  let comments = 0;
  for (const [commentId, comment] of state.comments) {
    if (comment.imageId === photo.id) {
      state.comments.delete(commentId);
      comments++;
    }
  }
  console.log(`  [delete] ${photo.id} code=${photo.code} comments=${comments}`);
  return sendJson(res, { deleted: id, group: removed });
}

function handleCommentsList(req, res, url) {
  if (!requireDevice(req, res)) return;

  const imageId = url.searchParams.get('imageId') || '';
  if (!imageId) return badRequest(res, 'GET /v1/comments: no imageId');
  if (!state.photos.has(imageId)) return fail(res, 'not_found', 404, '这张照片不在图评里');

  const sinceRaw = url.searchParams.get('since');
  const since = sinceRaw && /^\d+$/.test(sinceRaw) ? Number(sinceRaw) : 0;
  // The protocol's numbers: 50 by default, 200 at most. A caller that asks for
  // more gets 200 rather than an error, because it asked a reasonable question.
  const limitRaw = url.searchParams.get('limit');
  let limit = limitRaw && /^\d+$/.test(limitRaw) ? Number(limitRaw) : 50;
  if (limit <= 0 || limit > 200) limit = limitRaw && Number(limitRaw) > 200 ? 200 : 50;

  // `since` is exclusive, and the order is oldest first, which is the order the
  // danmaku layer plays them in.
  const items = visibleComments(imageId)
    .filter((c) => c.createdAt > since)
    .slice(0, limit);

  return sendJson(res, { serverTime: Date.now(), items: items.map(commentJson) });
}

async function handleCommentCreate(req, res) {
  let body;
  try {
    body = await readJson(req);
  } catch {
    return badRequest(res, 'POST /v1/comments: body is not JSON');
  }

  const code = typeof body.code === 'string' ? body.code.trim() : '';
  const imageIdIn = typeof body.imageId === 'string' ? body.imageId.trim() : '';
  if (!code && !imageIdIn) return badRequest(res, 'POST /v1/comments: no code and no imageId');

  let imageId = imageIdIn;
  if (code) {
    const resolved = state.codes.get(code);
    if (!resolved) return fail(res, 'not_found', 404, '这个短码不认得了');
    imageId = resolved;
  }
  const photo = state.photos.get(imageId);
  if (!photo) return fail(res, 'not_found', 404, '这张照片不在图评里');

  const anonId = typeof body.anonId === 'string' && body.anonId ? body.anonId : `ip:${clientIP(req)}`;
  const ip = clientIP(req);
  if (!allow(`cmt:anon:${anonId}:min`)
      || !allow(`cmt:anon:${anonId}:day`)
      || !allow(`cmt:ip:${ip}:min`)
      || !allow(`cmt:img:${imageId}:hour`)) {
    return fail(res, 'rate_limited', 429, '发得太快了，歇一会儿');
  }

  if (photo.visibility === 2) return fail(res, 'photo_locked', 403, '这张照片关闭了评论');

  const verdict = screen(String(body.content ?? ''));
  if (verdict.verdict === 'reject') {
    console.log(`  [reject] ${verdict.code} matched=${verdict.matched || '-'}`);
    return fail(res, verdict.code, 400, verdict.detail);
  }

  const { comment, created } = addComment({
    imageId,
    content: String(body.content).trim(),
    nickname: normalizeNickname(body.nickname),
    anonId,
    client: typeof body.client === 'string' && body.client ? body.client : 'h5/1',
    idemKey: typeof body.idemKey === 'string' ? body.idemKey : '',
  });

  if (created && comment.status === 1) publishComment(imageId, comment);

  const payload = { id: comment.id, createdAt: comment.createdAt, status: comment.status };
  if (comment.status === 0) payload.message = '已提交，审核后显示';
  return sendJson(res, payload);
}

function handleCommentReport(req, res, id) {
  const comment = state.comments.get(id);
  if (!comment) return fail(res, 'not_found', 404, '这条评论不在了');

  comment.reportCount++;
  // The third report takes a visible comment down. It is not deleted: a
  // moderator has to be able to see what was complained about.
  if (comment.reportCount >= REPORT_THRESHOLD && comment.status === 1) {
    comment.status = 0;
    const photo = state.photos.get(comment.imageId);
    if (photo) photo.commentCount = visibleComments(comment.imageId).length;
    console.log(`  [report] ${id} reached ${comment.reportCount}, status 1 -> 0`);
  }
  return sendJson(res, {
    id: comment.id,
    reportCount: comment.reportCount,
    status: comment.status,
  });
}

// ---------------------------------------------------------------- H5 page

function pageComments(imageId, mineId) {
  return visibleComments(imageId).slice(-20).map((c) => ({
    id: c.id,
    nickname: c.nickname,
    content: c.content,
    when: relativeTime(c.createdAt),
    mine: c.id === mineId,
  }));
}

function buildPage(req, code, photo, { nickname = '', content = '', idem = null, notice = '', noticeKind = '', query = new URLSearchParams() } = {}) {
  const base = requestBaseUrl(req);
  return renderPage({
    code,
    thumb: thumbUrl(code, 320).replace(baseUrl(), base),
    comments: pageComments(photo.id, query.get('mine')),
    nickname,
    content,
    idem: idem || newOpaqueToken(9),
    notice: notice || (query.get('ok') === '1' ? '已提交'
      : query.get('ok') === 'held' ? '已提交，审核后显示'
        : query.get('ok') === 'edited' ? '已保存'
          : query.get('ok') === 'deleted' ? '已删除'
            : query.get('reported') === '1' ? '已收到举报，谢谢' : ''),
    noticeKind,
    channel: VERSION,
    admin: isAdmin(req),
    // A path this process just made up, so it needs no validating here — the login
    // route re-checks whatever arrives in the query string (safeNext).
    adminLoginUrl: `/admin?next=${encodeURIComponent(`/c/${code}`)}`,
  });
}

function ensureAnonCookie(req, res) {
  const raw = String(req.headers.cookie || '');
  if (/(?:^|;\s*)tuping_anon=/.test(raw)) return null;
  const value = newOpaqueToken(16);
  // HttpOnly, because the page has no script and therefore no reason to read it;
  // not Secure, because on a LAN this page is plain http and a Secure cookie
  // would simply never be sent back.
  return `tuping_anon=${value}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax`;
}

function handlePage(req, res, code, query) {
  if (!validCode(code)) {
    return sendHtml(res, renderErrorPage('not_found', 404, '短码无效', '二维码可能不完整，回到电视上再扫一次。'), 404);
  }
  const imageId = state.codes.get(code);
  const photo = imageId ? state.photos.get(imageId) : null;
  if (!photo) {
    return sendHtml(res, renderErrorPage('not_found', 404, '这张照片不在图评里', '可能是它已经被删除了，或者电视连的是另一个图评服务器。'), 404);
  }
  const headers = {};
  const cookie = ensureAnonCookie(req, res);
  if (cookie) headers['Set-Cookie'] = cookie;
  return sendHtml(res, buildPage(req, code, photo, { query }), 200, headers);
}

async function handlePageSubmit(req, res, code) {
  if (!validCode(code)) {
    return sendHtml(res, renderErrorPage('not_found', 404, '短码无效', '二维码可能不完整，回到电视上再扫一次。'), 404);
  }
  const imageId = state.codes.get(code);
  const photo = imageId ? state.photos.get(imageId) : null;
  if (!photo) {
    return sendHtml(res, renderErrorPage('not_found', 404, '这张照片不在图评里', '可能它已经被删除了。'), 404);
  }

  const raw = await readBody(req, 1 << 20);
  const form = new URLSearchParams(raw.toString('utf8'));
  const content = (form.get('content') || '').trim();
  const nickname = (form.get('nickname') || '').trim();
  const idem = (form.get('idem') || '').trim() || newOpaqueToken(9);

  const headers = {};
  const cookie = ensureAnonCookie(req, res);
  if (cookie) headers['Set-Cookie'] = cookie;

  // Re-rendering with what the caller typed is the difference between "too fast,
  // try again" and retyping a comment on a phone keyboard.
  const failPage = (status, notice) => sendHtml(res, buildPage(req, code, photo, {
    nickname,
    content,
    idem,
    notice,
    noticeKind: 'bad',
  }), status, headers);

  const anonId = `ip:${clientIP(req)}`;
  if (!allow(`cmt:anon:${anonId}:min`)
      || !allow(`cmt:anon:${anonId}:day`)
      || !allow(`cmt:ip:${clientIP(req)}:min`)
      || !allow(`cmt:img:${photo.id}:hour`)) {
    state.counters.rateLimited++;
    return failPage(429, '发得太快了，歇一会儿再发');
  }

  if (photo.visibility === 2) return failPage(403, '这张照片关闭了评论');

  const verdict = screen(content);
  if (verdict.verdict === 'reject') {
    console.log(`  [reject] form ${verdict.code} matched=${verdict.matched || '-'}`);
    return failPage(400, verdict.detail);
  }

  const { comment, created } = addComment({
    imageId: photo.id,
    content,
    nickname: normalizeNickname(nickname),
    anonId,
    client: 'h5/1',
    idemKey: idem,
  });
  if (created && comment.status === 1) publishComment(photo.id, comment);

  // PRG: the browser is sent back to a GET, so a refresh cannot resubmit.
  const outcome = comment.status === 0 ? 'ok=held' : 'ok=1';
  res.writeHead(303, { Location: `/c/${code}?${outcome}&mine=${comment.id}`, ...headers });
  res.end();
}

function handlePageReport(req, res, code, id) {
  const comment = state.comments.get(id);
  if (!comment) {
    return sendHtml(res, renderErrorPage('not_found', 404, '这条评论不在了', '可能是它已经被删除了。'), 404);
  }
  comment.reportCount++;
  if (comment.reportCount >= REPORT_THRESHOLD && comment.status === 1) {
    comment.status = 0;
    const photo = state.photos.get(comment.imageId);
    if (photo) photo.commentCount = visibleComments(comment.imageId).length;
    // The third report takes it out of the thread, so the televisions watching are told
    // (§17.16). The status travels as 0, which is a real status and not "absent".
    publishComment(comment.imageId, comment);
  }
  res.writeHead(303, { Location: `/c/${code}?reported=1` });
  res.end();
}

// ---------------------------------------------------------------- the admin door

/**
 * The admin's door on the web page (§17.15): a login, a signed cookie, and edit/delete
 * on the comment page.
 *
 * The three properties that matter are the real server's three (api/admin.go) and they
 * are the reason this is not simply "a query parameter that says admin":
 *
 *   - the session cookie is a MAC over its own expiry, so a caller cannot mint a longer
 *     one by editing the number;
 *   - every mutating route is a POST, and the cookie is SameSite=Lax, so a stranger's
 *     page can neither delete a comment nor log the admin out with an <img>;
 *   - `next` is checked before it is used as a redirect, because this is the one page
 *     where a person types a secret.
 */
function adminSessionValue(exp) {
  const mac = crypto.createHmac('sha256', ADMIN_SECRET).update(String(exp)).digest('base64url');
  return `${exp}.${mac}`;
}

function isAdmin(req) {
  const raw = String(req.headers.cookie || '');
  const match = /(?:^|;\s*)tuping_admin=([^;]*)/.exec(raw);
  if (!match || !match[1]) return false;
  const [expPart, sigPart] = match[1].split('.');
  const exp = Number(expPart);
  if (!Number.isFinite(exp) || Date.now() > exp || !sigPart) return false;
  const expected = adminSessionValue(exp);
  return expected.length === match[1].length
    && crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(match[1]));
}

function adminCookieHeader() {
  const seconds = Math.floor(ADMIN_SESSION_TTL_MS / 1000);
  // HttpOnly, because the page has no script and therefore no reason to read it; not
  // Secure, because on a LAN this page is plain http and a Secure cookie would simply
  // never be sent back.
  return `tuping_admin=${adminSessionValue(Date.now() + ADMIN_SESSION_TTL_MS)}; Path=/; Max-Age=${seconds}; HttpOnly; SameSite=Lax`;
}

const clearedAdminCookieHeader = () =>
  'tuping_admin=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax';

/**
 * Keeps the post-login redirect on this mock. Only a path is accepted: `//evil.example`
 * starts with a slash and is a host, which is the whole trick.
 */
function safeNext(next) {
  const value = String(next || '');
  if (!value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) return '';
  if (/[\r\n]/.test(value)) return '';
  return value;
}

function handleAdminLoginPage(req, res, query) {
  return sendHtml(res, renderLoginPage({
    next: safeNext(query.get('next') || ''),
    loggedIn: isAdmin(req),
  }), 200);
}

async function handleAdminLogin(req, res) {
  const raw = await readBody(req, 1 << 16);
  const form = new URLSearchParams(raw.toString('utf8'));
  const next = safeNext(form.get('next') || '');
  const ip = clientIP(req);

  if (!allow(`admin:login:${ip}`)) {
    console.log(`  [429] admin login from ${ip}`);
    return sendHtml(res, renderLoginPage({ next, notice: '试得太频繁了，过一会儿再来', noticeKind: 'bad' }), 429);
  }

  // Trimmed, because a token pasted out of a compose file arrives with a newline on it,
  // and "the token is wrong" is the one message that must never be wrong.
  const supplied = (form.get('token') || '').trim();
  const equal = supplied.length === ADMIN_TOKEN.length
    && crypto.timingSafeEqual(Buffer.from(supplied, 'utf8'), Buffer.from(ADMIN_TOKEN, 'utf8'));
  if (!equal) {
    console.log(`  [401] admin login refused from ${ip}`);
    return sendHtml(res, renderLoginPage({ next, notice: '令牌不对', noticeKind: 'bad' }), 401);
  }

  console.log(`  [admin] logged in from ${ip}`);
  res.writeHead(303, { Location: next || '/admin', 'Set-Cookie': adminCookieHeader() });
  res.end();
}

function handleAdminLogout(req, res) {
  console.log('  [admin] logged out');
  res.writeHead(303, { Location: '/admin', 'Set-Cookie': clearedAdminCookieHeader() });
  res.end();
}

/**
 * The browser's guard: no session means the login form, told where to come back to,
 * rather than a 401 that leaves a browser with nowhere to go.
 */
function requireAdminPage(req, res) {
  if (isAdmin(req)) return true;
  res.writeHead(303, { Location: `/admin?next=${encodeURIComponent(req.url || '/')}` });
  res.end();
  return false;
}

/**
 * The code and the comment id, checked against each other. `/c/{code}/edit/{id}` must not
 * reach a comment from another photo: the page the edit redirects back to would then be
 * showing a thread that does not contain it.
 */
function adminTarget(res, code, id) {
  if (!validCode(code)) {
    sendHtml(res, renderErrorPage('not_found', 404, '短码无效', '二维码可能不完整，回到电视上再扫一次。'), 404);
    return null;
  }
  const imageId = state.codes.get(code);
  const photo = imageId ? state.photos.get(imageId) : null;
  if (!photo) {
    sendHtml(res, renderErrorPage('not_found', 404, '这张照片不在图评里', '可能它已经被删除了。'), 404);
    return null;
  }
  const comment = state.comments.get(id);
  if (!comment) {
    sendHtml(res, renderErrorPage('not_found', 404, '这条评论不在了', '可能它已经被删除了。'), 404);
    return null;
  }
  if (comment.imageId !== photo.id) {
    sendHtml(res, renderErrorPage('not_found', 404, '这条评论不属于这张照片', '地址里的评论 id 和短码对不上。'), 404);
    return null;
  }
  return { photo, comment };
}

function handleCommentEditPage(req, res, code, id) {
  if (!requireAdminPage(req, res)) return undefined;
  const target = adminTarget(res, code, id);
  if (!target) return undefined;
  const { comment } = target;
  return sendHtml(res, renderEditPage({
    code,
    id: comment.id,
    nickname: comment.nickname,
    content: comment.content,
    when: relativeTime(comment.createdAt),
  }), 200);
}

async function handleCommentEdit(req, res, code, id) {
  if (!requireAdminPage(req, res)) return;
  const target = adminTarget(res, code, id);
  if (!target) return;
  const { comment } = target;

  const raw = await readBody(req, 1 << 16);
  const form = new URLSearchParams(raw.toString('utf8'));
  const nickname = normalizeNickname(form.get('nickname') || '');
  const content = (form.get('content') || '').trim();

  // Re-rendering with what the caller typed: the same rule as the guest form (§10.1).
  const failEdit = (status, notice) => sendHtml(res, renderEditPage({
    code, id: comment.id, nickname, content, when: relativeTime(comment.createdAt),
    notice, noticeKind: 'bad',
  }), status);

  // The new text is not screened — the admin is who the queue escalates to — but the
  // length still is: 20 runes is what a television can draw on one danmaku line (§9.5).
  if (!content) return failEdit(400, '说点什么吧');
  if (runeLength(content) > MAX_CONTENT_RUNES) {
    return failEdit(400, `最多 ${MAX_CONTENT_RUNES} 个字，现在有 ${runeLength(content)} 个`);
  }

  comment.content = content;
  comment.nickname = nickname;
  console.log(`  [admin] comment ${comment.id} edited on ${code}`);
  // The television has to hear the new words: its history fetch asks for `since = the
  // newest it holds`, and an edit does not move that (§17.16).
  publishComment(comment.imageId, comment);
  res.writeHead(303, { Location: `/c/${code}?ok=edited&mine=${comment.id}` });
  res.end();
  return undefined;
}

function handleCommentDelete(req, res, code, id) {
  if (!requireAdminPage(req, res)) return;
  const target = adminTarget(res, code, id);
  if (!target) return;
  const { photo, comment } = target;

  // A soft delete, like the real server's: the row stays, status 3 keeps it out of the
  // page, the history and the count — and the socket is told, which is what takes a
  // deleted line off a television that is already showing this photo (§17.16).
  comment.status = 3;
  photo.commentCount = visibleComments(photo.id).length;
  console.log(`  [admin] comment ${comment.id} deleted from ${code}`);
  publishComment(photo.id, comment);
  res.writeHead(303, { Location: `/c/${code}?ok=deleted` });
  res.end();
}

// ---------------------------------------------------------------- health

function handleHealth(req, res) {
  let thumbBytes = 0;
  for (const photo of state.photos.values()) thumbBytes += photo.thumbBytes ? photo.thumbBytes.length : 0;
  return sendJson(res, {
    ok: true,
    version: VERSION,
    uptimeSec: Math.floor(process.uptime()),
    thumbBytes,
    baseUrl: requestBaseUrl(req),
  });
}

// ---------------------------------------------------------------- control

function controlStats() {
  return {
    ...state.counters,
    photos: state.photos.size,
    codes: state.codes.size,
    devices: state.devices.size,
    comments: state.comments.size,
    liveSubscribers: state.counters.liveSubscribers,
    slowMs: state.slowMs,
    failNext: state.failNext,
    pingMs,
    // The budgets themselves, not just a count of refusals: "which limit did I
    // hit" is the only question a 429 leaves you with.
    budgets: Object.fromEntries([...state.budgets.entries()].map(([k, v]) => [k, v.count])),
  };
}

/** `/__control/register`: the registration path a test can call with one GET. */
function controlRegister(req, res, url) {
  const deviceId = url.searchParams.get('device') || '';
  const source = url.searchParams.get('source') || 'fnos';
  const localId = url.searchParams.get('localId') || '';
  const sha = url.searchParams.get('sha256') || '';
  const bytes = Number(url.searchParams.get('bytes') || 0);
  const width = Number(url.searchParams.get('width') || 320);
  const height = Number(url.searchParams.get('height') || 240);

  if (sha && !isHex64(sha.toLowerCase())) return badRequest(res, `__control/register: bad sha256 ${sha}`);

  // A synthetic thumbnail, deterministic in the requested size, so a control
  // registration produces a stable identity without a file on disk.
  const seed = Buffer.from(`mock-review|${bytes}|${localId}|${sha}|${width}x${height}`);
  const fake = Buffer.concat([
    Buffer.from([0xff, 0xd8]),
    Buffer.from(`#mock-thumb:${sha256(seed).slice(0, 32)}`),
    Buffer.from([0xff, 0xd9]),
  ]);

  return registerPhoto(req, res, {
    deviceId,
    source,
    localId,
    sha256Original: sha ? sha.toLowerCase() : null,
    thumbBytes: fake,
    width,
    height,
    stripped: 0,
  });
}

// ---------------------------------------------------------------- routing

async function route(req, res, url) {
  const path = url.pathname;
  const method = req.method || 'GET';

  // ---- control surface ------------------------------------------------
  if (path.startsWith('/__control/')) {
    if (path === '/__control/reset') {
      const before = controlStats();
      state.counters = {
        requests: 0,
        comments: 0,
        photos: 0,
        signBad: 0,
        rateLimited: 0,
        liveSubscribers: state.counters.liveSubscribers,
        lastBadReason: null,
      };
      state.budgets.clear();
      state.rateOverrides.clear();
      state.slowMs = 0;
      state.failNext = 0;
      // The protocol only asks for the counters, and this is what a test gets by
      // default: clearing the photos would make "the same bytes are one identity"
      // untestable across a reset. `?data=1` throws the invented world away as
      // well, which is what a test needs when it wants a brand-new photo to
      // actually be one.
      if (url.searchParams.get('data') === '1') {
        state.devices.clear();
        state.deviceLocal.clear();
        state.photos.clear();
        state.codes.clear();
        state.bySha256.clear();
        state.byFingerprint.clear();
        state.comments.clear();
        console.log('  [control] reset including data');
      }
      console.log(`  [control] reset; before=${JSON.stringify(before)}`);
      return sendJson(res, { reset: true, data: url.searchParams.get('data') === '1', before, after: controlStats() });
    }
    if (path === '/__control/stats') return sendJson(res, controlStats());
    if (path === '/__control/photos') {
      return sendJson(res, {
        items: [...state.photos.values()].map((p) => ({
          imageId: p.id,
          code: p.code,
          sha256Original: p.sha256Original,
          width: p.width,
          height: p.height,
          commentCount: visibleComments(p.id).length,
          thumbBytes: p.thumbBytes ? p.thumbBytes.length : 0,
        })),
      });
    }
    if (path === '/__control/register') return controlRegister(req, res, url);
    if (path === '/__control/comment') {
      const imageId = url.searchParams.get('imageId') || '';
      const wanted = url.searchParams.get('content');
      const all = commentsFor(imageId).filter((c) => !wanted || c.content === wanted);
      const newest = all.length ? all[all.length - 1] : null;
      return sendJson(res, {
        found: Boolean(newest),
        count: all.length,
        item: newest ? commentJson(newest) : null,
      });
    }
    if (path === '/__control/rate-limit') {
      const key = url.searchParams.get('key') || '';
      if (!key) return badRequest(res, '__control/rate-limit: no key');
      const limit = Number(url.searchParams.get('limit') || 1);
      const windowMs = Number(url.searchParams.get('windowMs') || 60000);
      state.rateOverrides.set(key, { limit, windowMs });
      // Shrinking below what has already been spent is the point: the next
      // request on that key is then refused, which is how a 429 is reached in a
      // test without spending a real budget.
      state.budgets.delete(key);
      console.log(`  [control] budget ${key} -> limit=${limit} windowMs=${windowMs}`);
      return sendJson(res, { key, limit, windowMs });
    }
    if (path === '/__control/slow') {
      state.slowMs = Math.max(0, Number(url.searchParams.get('ms') || 0));
      console.log(`  [control] slow = ${state.slowMs}ms`);
      return sendJson(res, { slowMs: state.slowMs });
    }
    if (path === '/__control/ping-ms') {
      const ms = Number(url.searchParams.get('ms') || 0);
      // ms=0 restores the protocol's 30 seconds; any positive value is a test
      // asking to see the keepalive without waiting for it.
      pingMs = ms > 0 ? ms : 30000;
      // Sockets that are already open keep the timer they were given, so they are
      // re-armed rather than left on the old interval, which would make the
      // control look like it did nothing.
      for (const set of hub.values()) {
        for (const sub of set) {
          clearInterval(sub.timer);
          sub.timer = setInterval(() => push(sub, { t: 'ping' }), pingMs);
          if (sub.timer.unref) sub.timer.unref();
        }
      }
      console.log(`  [control] ping interval = ${pingMs}ms`);
      return sendJson(res, { pingMs });
    }
    if (path === '/__control/fail-next') {
      state.failNext = Math.max(0, Number(url.searchParams.get('n') || 0));
      state.failStatus = Number(url.searchParams.get('status') || 500);
      console.log(`  [control] next ${state.failNext} request(s) -> HTTP ${state.failStatus}`);
      return sendJson(res, { failNext: state.failNext, status: state.failStatus });
    }
    return fail(res, 'not_found', 404, `no control endpoint ${path}`);
  }

  // A forced failure, so the client's silent-failure path is reachable on demand
  // rather than by unplugging something.
  if (state.failNext > 0) {
    state.failNext--;
    const status = state.failStatus;
    console.log(`  [forced] ${method} ${path} -> ${status}`);
    return sendJson(res, {
      error: { code: status >= 500 ? 'server_error' : 'bad_request', message: '这是 __control/fail-next 造出来的失败' },
    }, status);
  }

  // ---- protocol -------------------------------------------------------
  if (path === '/healthz' && method === 'GET') return handleHealth(req, res);

  if (path === '/v1/devices' && method === 'POST') return handleDeviceRegister(req, res);
  if (path === '/v1/photos' && method === 'POST') return handlePhotoRegister(req, res);

  // Ahead of the wildcard below, and it has to be: `/v1/photos/by-local` would otherwise be
  // read as a code and answered "短码无效", which is a dead endpoint that looks alive.
  if (path === '/v1/photos/by-local' && method === 'GET') {
    return handlePhotoByLocal(req, res, url);
  }

  if (path.startsWith('/v1/photos/')) {
    const rest = path.slice('/v1/photos/'.length);
    if (method === 'GET') return handlePhotoGet(req, res, rest);
    if (method === 'DELETE') return handlePhotoDelete(req, res, rest);
  }

  if (path.startsWith('/v1/qr/') && method === 'GET') {
    const rest = path.slice('/v1/qr/'.length);
    if (!rest.endsWith('.png')) return fail(res, 'not_found', 404, '二维码地址要以 .png 结尾');
    const code = rest.slice(0, -4);
    if (!validCode(code)) return fail(res, 'not_found', 404, '短码无效');
    const size = url.searchParams.get('size');
    return serveQr(res, code, size === null || size === '' ? 440 : Number(size));
  }

  if (path.startsWith('/t/') && method === 'GET') {
    return serveThumb(req, res, url, path.slice('/t/'.length));
  }

  if (path === '/v1/comments' && method === 'GET') return handleCommentsList(req, res, url);
  if (path === '/v1/comments' && method === 'POST') return handleCommentCreate(req, res);

  const reportMatch = /^\/v1\/comments\/([^/]+)\/report$/.exec(path);
  if (reportMatch && method === 'POST') return handleCommentReport(req, res, reportMatch[1]);

  // The admin door (§17.15). Login and logout are POST-only, so a stray <img> or a
  // browser prefetch can sign nobody in or out.
  if (path === '/admin') {
    if (method === 'GET') return handleAdminLoginPage(req, res, url.searchParams);
    if (method === 'POST') return handleAdminLogin(req, res);
  }
  if (path === '/admin/logout' && method === 'POST') return handleAdminLogout(req, res);

  if (path.startsWith('/c/')) {
    const rest = path.slice('/c/'.length);
    const reportPage = /^([^/]+)\/report\/([^/]+)$/.exec(rest);
    if (reportPage && method === 'GET') return handlePageReport(req, res, reportPage[1], reportPage[2]);
    const editPage = /^([^/]+)\/edit\/([^/]+)$/.exec(rest);
    if (editPage && method === 'GET') return handleCommentEditPage(req, res, editPage[1], editPage[2]);
    if (editPage && method === 'POST') return handleCommentEdit(req, res, editPage[1], editPage[2]);
    const deletePage = /^([^/]+)\/delete\/([^/]+)$/.exec(rest);
    if (deletePage && method === 'POST') return handleCommentDelete(req, res, deletePage[1], deletePage[2]);
    if (method === 'GET') return handlePage(req, res, rest, url.searchParams);
    if (method === 'POST') return handlePageSubmit(req, res, rest);
  }

  return fail(res, 'not_found', 404, `no route for ${method} ${path}`);
}

// ---------------------------------------------------------------- server

/** Applies /__control/slow and counts the request before anything can short-circuit. */
function withControls(handler) {
  return (req, res) => {
    state.counters.requests++;
    if (state.slowMs > 0) {
      // Wrapping `end` rather than `writeHead` delays the whole response, headers
      // included, which is what "the server is slow" has to look like from the TV.
      const delay = state.slowMs;
      const end = res.end.bind(res);
      res.end = (...args) => setTimeout(() => end(...args), delay);
    }
    handler(req, res);
  };
}

const server = http.createServer(withControls((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  route(req, res, url).catch((error) => {
    console.log(`  [500] ${req.method} ${url.pathname}: ${error.stack || error.message}`);
    if (!res.headersSent) fail(res, 'server_error', 500, 'mock 内部出错了');
    else res.end();
  });
}));

/**
 * The WebSocket endpoint is upgraded by hand rather than through
 * `WebSocketServer({ server })`, because device auth has to be decided *before*
 * the upgrade: a 401 has to be an HTTP response, not a closed socket.
 */
const wss = new WebSocketServer({ noServer: true });

function handleUpgrade(req, socket, head) {
  const url = new URL(req.url, 'http://localhost');
  const match = /^\/v1\/live\/([^/]+)$/.exec(url.pathname);
  if (!match) {
    socket.write('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }

  const imageId = match[1];
  const id = req.headers['x-device-id'];
  const token = req.headers['x-device-token'];
  const device = id ? state.devices.get(String(id)) : null;
  if (!id || !token || !device || device.token !== String(token)) {
    state.counters.signBad++;
    state.counters.lastBadReason = `live ${imageId}: missing or wrong device token`;
    console.log(`  [401] live ${imageId}: missing or wrong device token`);
    socket.write('HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  if (!state.photos.has(imageId)) {
    socket.write('HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }

  wss.handleUpgrade(req, socket, head, (ws) => {
    const sub = subscribe(imageId, ws);
    console.log(`  [ws] live subscriber for ${imageId} (now ${state.counters.liveSubscribers})`);
    ws.on('close', () => {
      unsubscribe(imageId, sub);
      console.log(`  [ws] subscriber left ${imageId} (now ${state.counters.liveSubscribers})`);
    });
    ws.on('error', () => unsubscribe(imageId, sub));
    ws.on('message', () => { /* the TV has nothing to say; ignore it */ });
  });
}

server.on('upgrade', handleUpgrade);
server.on('error', reportBindFailure);

server.listen(PORT, '0.0.0.0', () => {
  console.log('='.repeat(70));
  console.log('  Mock 图评 server ready');
  console.log(`  Phone should open : ${baseUrl()}/c/<code>`);
  console.log(`  API               : http://localhost:${PORT}/v1/...   (health: /healthz)`);
  console.log(`  Control           : http://localhost:${PORT}/__control/stats`);
  console.log(`  Admin login       : ${baseUrl()}/admin   (MOCK_REVIEW_ADMIN_TOKEN, default "admin")`);
  console.log(`  Live ping every   : ${pingMs}ms   (MOCK_REVIEW_PING_MS, /__control/ping-ms)`);
  console.log('  NOTE: identity matching does NOT implement pHash, and it does not');
  console.log('        implement the variance guard rail either. Identical thumbnail');
  console.log('        bytes are one photo, different bytes are two; a re-encoded or');
  console.log('        padded copy of a photo that the real server would merge stays');
  console.log('        separate here, so verify.js never asserts perceptual merging.');
  console.log('  NOTE: no persistence. Everything lives in this process and is gone on');
  console.log('        exit; /__control/reset clears counters and budgets, and ?data=1');
  console.log('        throws the photos, codes, devices and comments away as well.');
  console.log('='.repeat(70));
});
