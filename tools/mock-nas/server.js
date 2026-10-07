'use strict';

/*
 * Mock fnOS server for verifying the TV Photo client end to end.
 *
 * Unlike the mock shipped with the reference project, this one actually VERIFIES
 * the `authx` signature and the `si` field type. That matters because the two
 * public clients disagree about whether the signature hashes decoded parameter
 * values or the percent-encoded query string; the server can be pinned to either
 * rule at runtime so the client's auto-negotiation can be proven rather than
 * assumed.
 *
 *   GET /__control/mode            -> report the current rule and stats
 *   GET /__control/mode?value=raw  -> require decoded values (default)
 *   GET /__control/mode?value=encoded -> require the encoded query string
 *   GET /__control/reset           -> clear counters
 *   GET /__control/add-photo?album=N -> upload one photo into album N, newest first,
 *                                       as a phone would while a slideshow is running
 *   GET /__control/grant-album?name=X -> share one more album with this account, after
 *                                       the client has already listed the section
 *   GET /__control/delete-photo?id=N  -> delete a photo, so a running slideshow's poll
 *                                       has to notice that it is gone
 *   GET /__control/slow-media?ms=N&sizes=o -> delay image responses, so the viewer's
 *                                       "wait for the next photo" state is observable
 */

const http = require('http');
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 5666);

/**
 * The client is HTTPS only, so the mock serves TLS as well as cleartext.
 *
 * The certificate is the same shape the real NAS presents — self-signed, `O=fnOS`,
 * `CN=fnOS`, and a `subjectAltName` of `DNS:fnOS` that matches nothing a client can
 * type — so the client's first-use trust path is exercised exactly as it is against the
 * real server rather than being quietly bypassed. Cleartext stays up for `verify.js` and
 * for reading raw protocol traffic.
 */
const HTTPS_PORT = Number(process.env.HTTPS_PORT || 5667);
const TLS_P12 = path.join(__dirname, 'tls', 'mock.p12');
const TLS_PASSPHRASE = 'mockpass';

/**
 * A port already in use is the usual reason this mock dies at start-up, and an
 * unhandled `error` event on a server takes the whole process down with a bare code and
 * no explanation — which reads like a crash in the mock rather than a second copy of it
 * still running. Say so instead.
 */
function reportBindFailure(error) {
  console.log(`  FATAL: could not listen (${error.code}): ${error.message}`);
  console.log(`         is another mock already on ${PORT}/${HTTPS_PORT}?`);
  process.exit(1);
}

// Global constants baked into the fnOS web frontend (not per-install values).
const SALT = 'NDzZTVxnRKP8Z0jXg1VAMonaG8akvh';
const SECRET = 'EAECCF25-80A6-4666-A7C2-A76904A74AB6';

// 18 digits: the client must echo this back as a string, never as a number.
const SI = '72057984125699406';

// A login handshake must advertise the zero placeholder, not the session id left
// over from a previous login. Real fnOS is *suspected* to enforce this (a stale id
// would explain errno 401 on a re-login); enforcing it here turns that suspicion
// into a test, so dropping FnReqId.reset() makes the sign-out/sign-in flow fail
// loudly instead of silently regressing.
const ZERO_BACK_ID = '0000000000000000';

const ACCESS_TOKEN_PREFIX = 'mock-token-';

const state = {
  mode: 'raw', // 'raw' | 'encoded'
  tokens: new Set(),
  requests: 0,
  signOk: 0,
  signBad: 0,
  // Artificial latency on selected image sizes, so the viewer's "keep the current
  // photo up until the next one is ready" behaviour can be observed rather than timed.
  mediaDelayMs: 0,
  mediaDelaySizes: ['o'],
  // Counted separately from `requests` (which only covers the JSON API) so a client's
  // image cache can be observed: a cached original must not be re-fetched.
  mediaRequests: 0,
  recentMedia: [],
  lastBadReason: null,
  // Every login handshake, so the client's request-id bookkeeping is observable.
  // A second login that still carries the first session's backId is a bug.
  handshakes: [],
};

// ---------------------------------------------------------------- RSA keypair
const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicKeyEncoding: { type: 'spki', format: 'pem' },
  privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
});

// ------------------------------------------------------------------ crypto
const md5 = (s) => crypto.createHash('md5').update(s, 'utf8').digest('hex');

function aesDecrypt(b64, key, ivB64) {
  const iv = Buffer.from(ivB64, 'base64');
  const decipher = crypto.createDecipheriv('aes-256-cbc', Buffer.from(key, 'utf8'), iv);
  return Buffer.concat([decipher.update(Buffer.from(b64, 'base64')), decipher.final()]).toString('utf8');
}

function signFor(pathname, payload, nonce, timestamp) {
  return md5([SALT, pathname, nonce, timestamp, md5(payload), SECRET].join('_'));
}

/**
 * Rebuilds the parameter string the way the two candidate rules would.
 * `raw` uses decoded values; `encoded` uses the query exactly as received.
 */
function payloadCandidates(url) {
  const entries = [...url.searchParams.entries()].sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  const raw = entries.map(([k, v]) => `${k}=${v}`).join('&');
  // Clients sign the encoded query WITHOUT the leading '?', so strip it.
  const encoded = url.search.startsWith('?') ? url.search.slice(1) : url.search;
  return { raw, encoded };
}

function verifyAuth(req, url) {
  const header = req.headers['authx'];
  if (!header) return { ok: false, reason: 'missing authx' };

  const parts = {};
  for (const kv of String(header).split('&')) {
    const i = kv.indexOf('=');
    if (i > 0) parts[kv.slice(0, i)] = kv.slice(i + 1);
  }
  if (!parts.nonce || !parts.timestamp || !parts.sign) {
    return { ok: false, reason: `malformed authx: ${header}` };
  }

  // Replay window, mirroring the documented +/-5 minute check.
  const skew = Math.abs(Date.now() - Number(parts.timestamp));
  if (!Number.isFinite(skew) || skew > 5 * 60 * 1000) {
    return { ok: false, reason: `timestamp skew ${skew}ms` };
  }

  const { raw, encoded } = payloadCandidates(url);
  const using = state.mode === 'encoded' ? encoded : raw;
  const expected = signFor(url.pathname, using, parts.nonce, parts.timestamp);

  if (expected === parts.sign) {
    state.signOk++;
    return { ok: true };
  }

  state.signBad++;
  const other = state.mode === 'encoded' ? raw : encoded;
  const otherSign = signFor(url.pathname, other, parts.nonce, parts.timestamp);
  state.lastBadReason =
    otherSign === parts.sign
      ? `signature matches the OTHER rule (${state.mode === 'encoded' ? 'raw' : 'encoded'})`
      : 'signature matches neither rule';
  return { ok: false, reason: state.lastBadReason };
}

// ------------------------------------------------------------------- images
const pngCache = new Map();

function crc32(buf) {
  let c;
  const table = crc32.table || (crc32.table = (() => {
    const t = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c;
    }
    return t;
  })());
  let crc = -1;
  for (let i = 0; i < buf.length; i++) crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xff];
  return (crc ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

/** A distinct gradient per photo id so images are visually distinguishable. */
function renderPng(id, width, height) {
  const key = `${id}:${width}x${height}`;
  const cached = pngCache.get(key);
  if (cached) return cached;

  const hue = (id * 47) % 360;
  const [br, bg, bb] = hslToRgb(hue / 360, 0.55, 0.45);
  const [dr, dg, db] = hslToRgb(((hue + 40) % 360) / 360, 0.6, 0.22);

  const raw = Buffer.alloc((width * 3 + 1) * height);
  let o = 0;
  for (let y = 0; y < height; y++) {
    raw[o++] = 0;
    const t = y / Math.max(1, height - 1);
    for (let x = 0; x < width; x++) {
      const s = (x / Math.max(1, width - 1)) * 0.35 + t * 0.65;
      raw[o++] = Math.round(br + (dr - br) * s);
      raw[o++] = Math.round(bg + (dg - bg) * s);
      raw[o++] = Math.round(bb + (db - bb) * s);
    }
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // truecolour
  const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 6 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
  pngCache.set(key, png);
  return png;
}

function hslToRgb(h, s, l) {
  const f = (n) => {
    const k = (n + h * 12) % 12;
    const a = s * Math.min(l, 1 - l);
    return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, Math.min(9 - k, 1)))));
  };
  return [f(0), f(8), f(4)];
}

const SIZE_MAP = { xxs: [107, 60], xs: [320, 180], s: [427, 240], m: [960, 540], o: [1280, 720] };

// -------------------------------------------------------------------- data
const uuid = (n) => `${n.toString(16).padStart(8, '0')}-mock-4000-8000-000000000000`;

const DAYS = 400;
const PER_DAY = 3;
const photos = [];
const albums = [];
const sharedAlbums = [];
const folders = [];

/** Photo ids, shared with /__control/add-photo so a late arrival cannot collide. */
let photoIdSeq = 1000;

/** Granted-album ids, shared with /__control/grant-album. */
let sharedAlbumIdSeq = 200;

(function seed() {
  const today = new Date('2026-10-05T12:00:00Z');
  for (let d = 0; d < DAYS; d++) {
    const day = new Date(today.getTime() - d * 86400000);
    const ymd = day.toISOString().slice(0, 10);
    for (let i = 0; i < PER_DAY; i++) {
      const isVideo = d % 9 === 0 && i === 0;
      const isLive = !isVideo && d % 7 === 0 && i === 1;
      const width = isVideo ? 1920 : 4000;
      const height = isVideo ? 1080 : 2250;
      const gid = photos.length;
      const pid = photoIdSeq++;
      const u = uuid(pid);
      const hh = String(8 + i).padStart(2, '0');
      // Explicit album membership, replacing the old "every 6th photo by array index"
      // rule: inserting a photo anywhere used to re-file the entire library, which is
      // precisely what /__control/add-photo does.
      const ownAlbum = gid % 6 === 0 ? 6 : gid % 6;
      const albumIds = [ownAlbum];
      // The two granted albums used to alias these slices through the same modulo.
      if (ownAlbum === 5) albumIds.push(101);
      if (ownAlbum === 6) albumIds.push(102);
      photos.push({
        id: pid,
        ownerId: 1000,
        albumIds,
        dateTime: `${ymd} ${hh}:15:04`,
        photoDateTime: `${ymd} ${hh}:15:04`,
        fileType: isVideo ? 'mp4' : 'jpeg',
        category: isVideo ? 'video' : 'photo',
        fileName: isVideo ? `VID_${ymd.replace(/-/g, '')}_${hh}.mp4` : `IMG_${ymd.replace(/-/g, '')}_${hh}.jpg`,
        fileSize: isVideo ? 7654321 : 3145728 + i * 4096,
        isCollect: i === 3 ? 1 : 0,
        mp: '12MP',
        filePath: `/vol1/1000/Photos/${ymd}/IMG_${pid}.jpg`,
        showFilePath: `/Photos/${ymd}`,
        height,
        width,
        geo: '',
        isLive: isLive ? 1 : 0,
        rotation: 0,
        isCanPreview: 1,
        photoUUID: u,
        additional: {
          thumbnail: {
            xxsUrl: `/p/api/v1/stream/p/t/${pid}/xxs/${u}`,
            xsUrl: `/p/api/v1/stream/p/t/${pid}/xs/${u}`,
            sUrl: `/p/api/v1/stream/p/t/${pid}/s/${u}`,
            mUrl: `/p/api/v1/stream/p/t/${pid}/m/${u}`,
            originalUrl: `/p/api/v1/stream/p/t/${pid}/o/${u}`,
            ...(isVideo || isLive ? { videoUrl: `/p/api/v1/stream/v/${pid}` } : {}),
          },
          tags: [],
        },
      });
    }
  }

  for (let a = 1; a <= 6; a++) {
    albums.push({
      albumId: a,
      albumName: ['家庭合影', '旅行 2026', '宝宝成长', '美食记录', '风景随拍', '老照片'][a - 1],
      source: 'user',
      photoCount: 20 + a * 3,
      videoCount: a % 3,
      posterImgUrl: `/p/api/v1/stream/p/t/${1000 + a}/s/${uuid(1000 + a)}`,
      startDateTime: '2025-01-01 00:00:00',
      endDateTime: '2026-10-01 00:00:00',
      shared: 0,
      ownerId: 1000,
    });
  }

  // Albums other accounts have granted to this one. The field set mirrors a live
  // `album_grant/list_to_me` response: the same object as `album/list`, plus
  // `ownerName`. There is no `posterImgUrl`-only variant here on purpose — the real
  // server sends both keys, and the client prefers `posterImgUrl`.
  // MOCK_SHARED_ALBUMS=0 seeds none, which is the only way to see the client's
  // "nobody has shared with you" empty state on a device.
  if ((process.env.MOCK_SHARED_ALBUMS ?? '1') !== '0') sharedAlbums.push(
    {
      albumId: 101,
      albumName: '老王的婚礼',
      source: 'user',
      photoCount: 86,
      videoCount: 3,
      posterUrl: `/p/api/v1/stream/p/t/${1101}/s/${uuid(1101)}`,
      posterImgUrl: `/p/api/v1/stream/p/t/${1101}/s/${uuid(1101)}`,
      startDateTime: '2024:05:01 00:00:00',
      endDateTime: '2024:05:03 12:00:00',
      shared: 1,
      ownerId: 1002,
      ownerName: 'laowang',
      grants: null,
      hasOtherUserPhoto: null,
      grantPermission: '',
      readableUsers: null,
      category: 0,
      birthday: '',
      displayType: 1,
      modTime: 0,
    },
    {
      albumId: 102,
      albumName: '表妹的毕业照',
      source: 'user',
      photoCount: 240,
      videoCount: 5,
      posterUrl: `/p/api/v1/stream/p/t/${1102}/s/${uuid(1102)}`,
      posterImgUrl: `/p/api/v1/stream/p/t/${1102}/s/${uuid(1102)}`,
      startDateTime: '2023:06:20 00:00:00',
      endDateTime: '2023:06:22 18:30:00',
      shared: 1,
      ownerId: 1003,
      ownerName: 'xiaomei',
      grants: null,
      hasOtherUserPhoto: null,
      grantPermission: '',
      readableUsers: null,
      category: 0,
      birthday: '',
      displayType: 1,
      modTime: 0,
    },
  );

  folders.push(
    { folderId: 1, folderPath: '/vol1/1000/Photos', photoCount: photos.length, videoCount: 5, status: 1, isDefault: true },
    { folderId: 2, folderPath: '/vol1/1000/Photos/家庭', photoCount: 120, videoCount: 2, status: 1, isDefault: false },
    { folderId: 3, folderPath: '/vol1/1000/Photos/旅行', photoCount: 340, videoCount: 8, status: 1, isDefault: false },
    { folderId: 4, folderPath: '/vol1/1000/Photos/工作', photoCount: 60, videoCount: 0, status: 1, isDefault: false },
  );
})();

const photoById = new Map(photos.map((p) => [p.id, p]));

/** Deterministic slice of the library attributed to one face cluster. */
function photosOf(personId) {
  return photos.filter((_, i) => i % 5 === personId % 5);
}

// Face clusters, mirroring the gallery's AI output. One entry is deliberately
// hidden and one has no name, so both the filtering and the placeholder are exercised.
const people = ['妈妈', '爸爸', '宝宝', '爷爷', '奶奶', '小明', '小红', '朋友 A', '', '同事 C', '宠物'].map(
  (name, i) => ({
    id: i + 1,
    name,
    faceId: 900 + i,
    itemCount: 12 + i * 9,
    birthday: '',
    isHide: i === 5 ? 1 : 0,
  }),
);

function photosInRange(startTime, endTime) {
  const start = startTime.replace(/:/g, '-').replace(' ', 'T');
  const end = endTime.replace(/:/g, '-').replace(' ', 'T');
  return photos.filter((p) => {
    const t = p.dateTime.replace(' ', 'T');
    return t >= start && t <= end;
  });
}

function timeline() {
  const byDay = new Map();
  for (const p of photos) {
    const key = p.dateTime.slice(0, 10);
    byDay.set(key, (byDay.get(key) || 0) + 1);
  }
  return [...byDay.entries()]
    .sort((a, b) => (a[0] < b[0] ? 1 : -1))
    .map(([day, count]) => {
      const [y, m, d] = day.split('-').map(Number);
      return { year: y, month: m, day: d, itemCount: count };
    });
}

// ------------------------------------------------------------------ helpers
function sendJson(res, obj, status = 200) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': body.length });
  res.end(body);
}

const ok = (data) => ({ code: 0, msg: '', data });
const fail = (code, msg) => ({ code, msg });

function listResponse(entries, url, defaultLimit = 60) {
  const limit = Number(url.searchParams.get('limit') ?? defaultLimit);
  const offset = Number(url.searchParams.get('offset') ?? 0);
  const effLimit = limit < 0 ? entries.length : limit;
  const slice = entries.slice(offset, offset + effLimit);
  return { count: entries.length, hasNext: offset + effLimit < entries.length, list: slice };
}

// -------------------------------------------------------------- HTTP server
const VIDEO_PATH = path.join(__dirname, 'assets', 'test_video.mp4');

function serveMedia(req, res, url) {
  state.mediaRequests++;
  // Recorded so a client's prefetch window can be observed: which originals it
  // actually asked for, and in response to where the user moved.
  state.recentMedia.push(url.pathname);
  if (state.recentMedia.length > 400) state.recentMedia.splice(0, state.recentMedia.length - 400);
  // Thumbnails and video need only the token, never a signature.
  const token = req.headers['accesstoken'] || req.headers['AccessToken'];
  if (!token || !state.tokens.has(String(token))) {
    return sendJson(res, fail(401, 'thumbnail requires a valid token'), 200);
  }

  const streamMatch = /^\/p\/api\/v1\/stream\/p\/t\/(\d+)\/([a-z]+)\//.exec(url.pathname);
  if (streamMatch) {
    const id = Number(streamMatch[1]);
    const size = streamMatch[2];
    const [w, h] = SIZE_MAP[size] || SIZE_MAP.m;
    const png = renderPng(id, w, h);
    const send = () => {
      res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length, 'Cache-Control': 'max-age=600' });
      res.end(png);
    };
    // An artificially slow original is how the viewer's paging is tested: with a
    // three-second photo, "the previous picture stays up with an arrow in the corner"
    // is something a screenshot can actually catch, and the black flash it replaced is
    // something it can miss.
    const delay = state.mediaDelaySizes.includes(size) ? state.mediaDelayMs : 0;
    if (delay > 0) return void setTimeout(send, delay);
    return send();
  }

  const faceMatch = /^\/p\/api\/v1\/stream\/face\/(\d+)$/.exec(url.pathname);
  if (faceMatch) {
    const png = renderPng(Number(faceMatch[1]), 240, 240);
    res.writeHead(200, { 'Content-Type': 'image/png', 'Content-Length': png.length, 'Cache-Control': 'max-age=600' });
    return res.end(png);
  }

  const videoMatch = /^\/p\/api\/v1\/stream\/v\/(\d+)$/.exec(url.pathname);
  if (videoMatch) {
    if (!fs.existsSync(VIDEO_PATH)) {
      return sendJson(res, fail(404, 'no test video bundled'), 200);
    }
    const stat = fs.statSync(VIDEO_PATH);
    const range = req.headers.range;
    if (range) {
      const m = /bytes=(\d*)-(\d*)/.exec(range);
      const start = m && m[1] ? Number(m[1]) : 0;
      const end = m && m[2] ? Number(m[2]) : stat.size - 1;
      res.writeHead(206, {
        'Content-Type': 'video/mp4',
        'Content-Range': `bytes ${start}-${end}/${stat.size}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': end - start + 1,
      });
      return fs.createReadStream(VIDEO_PATH, { start, end }).pipe(res);
    }
    res.writeHead(200, { 'Content-Type': 'video/mp4', 'Content-Length': stat.size, 'Accept-Ranges': 'bytes' });
    return fs.createReadStream(VIDEO_PATH).pipe(res);
  }

  return sendJson(res, fail(404, 'unknown media'), 200);
}

function serveApi(req, res, url) {
  const token = req.headers['accesstoken'] || req.headers['AccessToken'];
  if (!token || !state.tokens.has(String(token))) {
    state.requests++;
    return sendJson(res, fail(401, 'invalid access token'), 200);
  }

  const verdict = verifyAuth(req, url);
  if (!verdict.ok) {
    state.requests++;
    console.log(`  [403] ${url.pathname} -> ${verdict.reason}`);
    return sendJson(res, fail(5000, 'signature rejected'), 200);
  }

  state.requests++;
  const p = url.pathname;

  if (p === '/p/api/v1/gallery/timeline') {
    return sendJson(res, ok({ list: timeline() }));
  }

  if (p === '/p/api/v1/gallery/getList') {
    const start = url.searchParams.get('start_time') || '1970:01:01 00:00:00';
    const end = url.searchParams.get('end_time') || '2099:12:31 23:59:59';
    const entries = photosInRange(start, end).sort((a, b) => (a.dateTime < b.dateTime ? 1 : -1));
    return sendJson(res, ok(listResponse(entries, url)));
  }

  if (p === '/p/api/v1/album/list') {
    return sendJson(res, ok({ list: albums }));
  }

  if (p === '/p/api/v1/album/photos') {
    const albumId = Number(url.searchParams.get('album_id') || 1);
    const entries = photos.filter((photo) => photo.albumIds.includes(albumId));
    return sendJson(res, ok(listResponse(entries, url, 40)));
  }

  if (p === '/p/api/v1/album_grant/list_to_me') {
    // A route of its own rather than a filter on `album/list`, and it answers
    // `count: null, hasNext: null` — which is why the fnOS frontend asks for
    // everything in one request. `offset`/`limit` are still honoured.
    const limit = Number(url.searchParams.get('limit') || 300);
    const offset = Number(url.searchParams.get('offset') || 0);
    return sendJson(res, ok({
      count: null,
      hasNext: null,
      list: sharedAlbums.slice(offset, offset + limit),
    }));
  }

  if (p === '/p/api/v1/photo/folder/list') {
    return sendJson(res, ok({ list: folders }));
  }

  if (p === '/p/api/v1/folder_view/getFolderList') {
    const folderPath = url.searchParams.get('folderPath') || '';
    const children = ['子目录 A', '子目录 B', '2025 归档'].map((name, i) => ({
      name,
      path: `${folderPath}/${name}`.replace('//', '/') + (i === 2 ? '' : ''),
    }));
    return sendJson(res, ok({ list: children, total: children.length }));
  }

  if (p === '/p/api/v1/folder_view/getFileList') {
    const entries = photos.slice(0, 45).sort((a, b) => (a.dateTime < b.dateTime ? 1 : -1));
    return sendJson(res, ok(listResponse(entries, url, 40)));
  }

  if (p === '/p/api/v1/ai-person/list') {
    return sendJson(res, ok({ list: people }));
  }

  if (p === '/p/api/v1/ai-person/photoLibrary/timeLine') {
    const personId = Number(url.searchParams.get('id') || 0);
    const byDay = new Map();
    for (const photo of photosOf(personId)) {
      const ymd = photo.dateTime.slice(0, 10);
      byDay.set(ymd, (byDay.get(ymd) || 0) + 1);
    }
    const list = [...byDay.entries()]
      .sort((a, b) => (a[0] < b[0] ? 1 : -1))
      .map(([ymd, count]) => {
        const [y, m, d] = ymd.split('-').map(Number);
        return { year: y, month: m, day: d, itemCount: count };
      });
    return sendJson(res, ok({ list }));
  }

  if (p === '/p/api/v1/ai-person/photoLibrary/list') {
    // Models the real endpoint's strictness. It takes the range under both naming
    // styles plus an album_id carrying the person id, and it only accepts a SINGLE
    // day: a wider range is what produced the observed HTTP 400. Enforcing that here
    // means a regression fails loudly instead of quietly breaking person galleries.
    const personId = Number(url.searchParams.get('personId') || 0);
    const albumId = url.searchParams.get('album_id');
    const start = url.searchParams.get('start_time');
    const end = url.searchParams.get('end_time');

    const missing = [];
    if (!personId) missing.push('personId');
    if (albumId === null) missing.push('album_id');
    if (!start) missing.push('start_time');
    if (!end) missing.push('end_time');
    if (!url.searchParams.has('startTime')) missing.push('startTime');
    if (!url.searchParams.has('endTime')) missing.push('endTime');
    if (missing.length) {
      console.log(`  [400] person list missing: ${missing.join(', ')}`);
      return sendJson(res, { code: 400, msg: `missing parameters: ${missing.join(', ')}` }, 400);
    }

    if (String(start).slice(0, 10) !== String(end).slice(0, 10)) {
      console.log(`  [400] person list range spans days: ${start} .. ${end}`);
      return sendJson(res, { code: 400, msg: 'range must be within a single day' }, 400);
    }

    const ymd = String(start).slice(0, 10).replace(/:/g, '-');
    const entries = photosOf(personId).filter((photo) => photo.dateTime.startsWith(ymd));
    return sendJson(res, ok(listResponse(entries, url, 40)));
  }

  if (p === '/p/api/v1/user_photo/stat') {
    return sendJson(res, ok({ id: 1, photoCount: photos.filter((x) => x.category === 'photo').length, videoCount: photos.filter((x) => x.category === 'video').length, isAdmin: true, nasUid: 1000 }));
  }

  if (p === '/p/api/v1/app/version') {
    // Deliberately uses the other envelope so the client's tolerance is exercised.
    return sendJson(res, { errno: 0, result: 'succ', data: { version: '1.2.3-mock' } });
  }

  return sendJson(res, fail(404, `mock has no route for ${p}`), 200);
}

const server = http.createServer(handleRequest);

/** Shared by the cleartext and the TLS listener: the routes do not care who served them. */
function handleRequest(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (url.pathname === '/__control/mode') {
    const value = url.searchParams.get('value');
    if (value === 'raw' || value === 'encoded') state.mode = value;
    return sendJson(res, { mode: state.mode, requests: state.requests, mediaRequests: state.mediaRequests, signOk: state.signOk, signBad: state.signBad, lastBadReason: state.lastBadReason });
  }
  if (url.pathname === '/__control/reset') {
    state.requests = 0;
    state.signOk = 0;
    state.signBad = 0;
    state.mediaRequests = 0;
    state.recentMedia = [];
    state.lastBadReason = null;
    state.handshakes = [];
    return sendJson(res, { reset: true });
  }
  if (url.pathname === '/__control/login') {
    return sendJson(res, { count: state.handshakes.length, handshakes: state.handshakes });
  }
  if (url.pathname === '/__control/media') {
    return sendJson(res, { count: state.mediaRequests, recent: state.recentMedia });
  }
  if (url.pathname === '/__control/slow-media') {
    // GET /__control/slow-media?ms=3000&sizes=o  -> originals arrive three seconds late
    // GET /__control/slow-media?ms=0              -> back to normal
    state.mediaDelayMs = Math.max(0, Number(url.searchParams.get('ms') || 0));
    const sizes = url.searchParams.get('sizes');
    if (sizes) state.mediaDelaySizes = sizes.split(',').map((s) => s.trim()).filter(Boolean);
    return sendJson(res, { mediaDelayMs: state.mediaDelayMs, sizes: state.mediaDelaySizes });
  }
  if (url.pathname === '/__control/delete-photo') {
    // Models the host deleting a photo from the album while the slideshow is running, so
    // the client's next poll has to notice that a photo it is holding is gone.
    const id = Number(url.searchParams.get('id') || 0);
    const index = id
      ? photos.findIndex((p) => p.id === id)
      : photos.findIndex((p) => p.albumIds.includes(Number(url.searchParams.get('album') || 1)));
    if (index < 0) return sendJson(res, fail(404, `no photo ${id}`), 200);
    const [removed] = photos.splice(index, 1);
    return sendJson(res, { deleted: removed.id, fileName: removed.fileName, remaining: photos.length });
  }
  if (url.pathname === '/__control/photo') {
    // Lets a test confirm what a poll should have noticed.
    const id = Number(url.searchParams.get('id') || 0);
    const photo = photos.find((p) => p.id === id);
    return sendJson(res, { exists: Boolean(photo), fileName: photo ? photo.fileName : null });
  }
  if (url.pathname === '/__control/add-photo') {
    // Models someone uploading a photo to an album while the slideshow is running.
    // The new photo goes to the front of the library, which is where the server's
    // newest-first ordering would put it, so the client sees it on its next poll of
    // page 0. `albumIds` keeps every other album's membership untouched.
    const albumId = Number(url.searchParams.get('album') || 1);
    const pid = photoIdSeq++;
    const u = uuid(pid);
    const stamp = new Date().toISOString().slice(0, 10);
    photos.unshift({
      id: pid,
      ownerId: 1000,
      albumIds: [albumId],
      dateTime: `${stamp} 23:59:59`,
      photoDateTime: `${stamp} 23:59:59`,
      fileType: 'jpeg',
      category: 'photo',
      fileName: `UPLOAD_${pid}.jpg`,
      fileSize: 3145728,
      isCollect: 0,
      mp: '12MP',
      filePath: `/vol1/1000/Photos/${stamp}/UPLOAD_${pid}.jpg`,
      showFilePath: `/Photos/${stamp}`,
      height: 2250,
      width: 4000,
      geo: '',
      isLive: 0,
      rotation: 0,
      isCanPreview: 1,
      photoUUID: u,
      additional: {
        thumbnail: {
          xxsUrl: `/p/api/v1/stream/p/t/${pid}/xxs/${u}`,
          xsUrl: `/p/api/v1/stream/p/t/${pid}/xs/${u}`,
          sUrl: `/p/api/v1/stream/p/t/${pid}/s/${u}`,
          mUrl: `/p/api/v1/stream/p/t/${pid}/m/${u}`,
          originalUrl: `/p/api/v1/stream/p/t/${pid}/o/${u}`,
        },
        tags: [],
      },
    });
    return sendJson(res, { added: pid, albumId, total: photos.length });
  }
  if (url.pathname === '/__control/grant-album') {
    // Models someone sharing another album with this account after the client has
    // already listed the section once — the case a cached 他人分享 list would miss.
    const albumId = sharedAlbumIdSeq++;
    const owner = url.searchParams.get('owner') || 'laowang';
    const name = url.searchParams.get('name') || `新分享的相册 ${albumId}`;
    const poster = `/p/api/v1/stream/p/t/1100/s/${uuid(1100)}`;
    sharedAlbums.push({
      albumId,
      albumName: name,
      source: 'user',
      photoCount: 12,
      videoCount: 0,
      posterUrl: poster,
      posterImgUrl: poster,
      startDateTime: '2026:01:01 00:00:00',
      endDateTime: '2026:06:01 00:00:00',
      shared: 1,
      ownerId: 1002,
      ownerName: owner,
      grants: null,
      hasOtherUserPhoto: null,
      grantPermission: '',
      readableUsers: null,
      category: 0,
      birthday: '',
      displayType: 1,
      modTime: 0,
    });
    return sendJson(res, { albumId, name, owner, total: sharedAlbums.length });
  }
  if (url.pathname === '/__control/ping') {
    return sendJson(res, { ok: true, mode: state.mode });
  }

  if (url.pathname.startsWith('/p/api/v1/stream/')) return serveMedia(req, res, url);
  if (url.pathname.startsWith('/p/api/v1/')) return serveApi(req, res, url);

  return sendJson(res, fail(404, 'not found'), 200);
}

// ------------------------------------------------------------ WebSocket login
// Detached from either listener so the same socket handling serves ws:// and wss://:
// the client logs in over TLS now, and a mock that only upgraded cleartext would fail
// for a reason the real NAS does not have.
const wss = new WebSocketServer({ noServer: true });

function handleUpgrade(req, socket, head) {
  if (new URL(req.url, 'http://localhost').pathname !== '/websocket') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
}

wss.on('connection', (ws) => {
  console.log('  [ws] login socket opened');

  ws.on('message', (raw) => {
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }

    if (msg.req === 'ping') {
      return ws.send(JSON.stringify({ res: 'pong' }));
    }

    if (msg.req === 'util.crypto.getRSAPub') {
      const reqid = typeof msg.reqid === 'string' ? msg.reqid : '';
      // Layout is tttttttt + bbbbbbbbbbbbbbbb + eeee (28 hex chars).
      const backId = reqid.length === 28 ? reqid.slice(8, 24) : null;
      state.handshakes.push({ reqid, backId, at: new Date().toISOString() });
      console.log(`  [ws] getRSAPub reqid=${reqid} backId=${backId}`);

      if (backId && backId !== ZERO_BACK_ID) {
        console.log(`  [ws] rejecting handshake: stale backId=${backId} -> errno 401`);
        return ws.send(JSON.stringify({
          errno: 401,
          result: 'session id from a previous login',
        }));
      }

      return ws.send(JSON.stringify({ pub: publicKey, si: SI, result: 'succ' }));
    }

    if (msg.req === 'encrypted') {
      let payload;
      try {
        const aesKey = crypto.privateDecrypt(
          { key: privateKey, padding: crypto.constants.RSA_PKCS1_PADDING },
          Buffer.from(msg.rsa, 'base64'),
        ).toString('utf8');
        payload = JSON.parse(aesDecrypt(msg.aes, aesKey, msg.iv));
      } catch (e) {
        console.log(`  [ws] decrypt failed: ${e.message}`);
        return ws.send(JSON.stringify({ errno: 8192, result: `decrypt failed: ${e.message}` }));
      }

      // The documented trap: a numeric `si` loses precision and fails the type check.
      if (typeof payload.si !== 'string') {
        console.log(`  [ws] si was ${typeof payload.si}, expected string -> errno 8192`);
        return ws.send(JSON.stringify({ errno: 8192, result: 'si must be a string' }));
      }
      if (payload.si !== SI) {
        console.log(`  [ws] si mismatch: got ${payload.si}`);
        return ws.send(JSON.stringify({ errno: 8192, result: 'si mismatch' }));
      }
      if (!payload.user || !payload.password) {
        return ws.send(JSON.stringify({ errno: 131072, result: 'missing credentials' }));
      }

      const token = ACCESS_TOKEN_PREFIX + payload.user;
      state.tokens.add(token);
      console.log(`  [ws] login ok user=${payload.user} device=${payload.deviceName} stay=${payload.stay}`);

      return ws.send(JSON.stringify({
        result: 'succ',
        uid: 1000,
        admin: true,
        token,
        secret: 'mock-secret-value',
        backId: '0000000000000001',
        machineId: 'mock-machine',
        user: payload.user,
      }));
    }
  });
});

/**
 * The TLS listener, on the port the client will actually use.
 *
 * A missing keystore is reported rather than fatal: `verify.js` drives the cleartext
 * port and should keep working on a fresh checkout.
 */
let tlsServer = null;
try {
  tlsServer = https.createServer(
    { pfx: fs.readFileSync(TLS_P12), passphrase: TLS_PASSPHRASE },
    handleRequest,
  );
} catch (error) {
  console.log(`  NOTE: no HTTPS listener (${error.message})`);
  console.log('        regenerate tools/mock-nas/tls/mock.p12 — see README');
}

server.listen(PORT, '0.0.0.0', () => {
  console.log('='.repeat(70));
  console.log('  Mock fnOS server ready');
  console.log(`  REST/WS : http://localhost:${PORT}  (ws://localhost:${PORT}/websocket?type=main)`);
  if (tlsServer) {
    console.log(`  TLS     : https://localhost:${HTTPS_PORT}  (wss://localhost:${HTTPS_PORT}/websocket?type=main)`);
    console.log('            self-signed O=fnOS CN=fnOS, SAN DNS:fnOS — the shape a real NAS presents');
  }
  console.log(`  Sign rule: ${state.mode}   (change with /__control/mode?value=raw|encoded)`);
  console.log(`  Data    : ${photos.length} photos, ${albums.length} albums, ${sharedAlbums.length} shared with me, ${folders.length} folders`);
  console.log('  The emulator reaches the host at 10.0.2.2');
  console.log('='.repeat(70));
});

if (tlsServer) {
  tlsServer.listen(HTTPS_PORT, '0.0.0.0');
}

server.on('upgrade', handleUpgrade);
if (tlsServer) tlsServer.on('upgrade', handleUpgrade);
server.on('error', reportBindFailure);
if (tlsServer) tlsServer.on('error', reportBindFailure);
