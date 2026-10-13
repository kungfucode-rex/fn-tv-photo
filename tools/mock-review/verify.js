'use strict';

/*
 * Independent reference client for 图评 (tuping).
 *
 * This file is the contract test. It is written from the protocol text in
 * docs/tuping-design.md rather than from server.js, imports nothing from it, and
 * is meant to be pointed at either implementation:
 *
 *   node verify.js                              # the mock, on 127.0.0.1:5670
 *   node verify.js --base http://host:5671      # the real Go server
 *
 * That is the whole point of the exercise. A test that only passes against the
 * mock proves the mock agrees with itself; the same script against the Go server
 * is what says the two implementations mean the same thing by the same words.
 *
 * Two consequences follow from running against both:
 *
 *   - Every field check is tolerant of EXTRA fields. The real server sends more
 *     than the protocol requires, and a test that broke on that would be
 *     measuring the wrong thing.
 *   - Checks that a mock-only observation would satisfy are conditional on
 *     `--strict-mock` and print which server they are skipping for. There is
 *     deliberately only one class of those: reachability controls that live under
 *     /__control and therefore cannot exist on the real server.
 *
 * The test images are built here, by hand, as baseline grayscale JPEGs. That
 * keeps the script free of dependencies beyond `ws` and means the bytes on the
 * wire are reproducible: the same run produces the same thumbnails, so "the same
 * bytes are one identity" is a statement about bytes rather than about luck.
 */

const crypto = require('crypto');
const zlib = require('zlib');
const WebSocket = require('ws');

// ---------------------------------------------------------------- arguments

const argv = process.argv.slice(2);
const baseFlag = argv.indexOf('--base');
const BASE = (baseFlag >= 0 && argv[baseFlag + 1] ? argv[baseFlag + 1] : 'http://127.0.0.1:5670').replace(/\/+$/, '');
const STRICT_MOCK = argv.includes('--strict-mock');
const IS_MOCK = /:5670$/.test(BASE) || argv.includes('--mock');

/**
 * The admin door's password (§17.15).
 *
 * The mock defaults MOCK_REVIEW_ADMIN_TOKEN to `admin`, and the README's recipe starts
 * the real server with TUPING_ADMIN_TOKEN=admin, so the default works against both. A
 * deployment that chose another word passes it here (or in VERIFY_ADMIN_TOKEN) — and a
 * check that cannot know the password is a check that would fail for the wrong reason.
 */
const adminFlag = argv.indexOf('--admin-token');
const ADMIN_TOKEN = (adminFlag >= 0 && argv[adminFlag + 1]) || process.env.VERIFY_ADMIN_TOKEN || 'admin';

/**
 * A per-run seed for the test photographs.
 *
 * Without it the three images are the same bytes on every run, and a second run
 * against a server that remembers the first one is indistinguishable from a
 * server that merges photos it should not: the same thumbnail legitimately
 * resolves to the same identity, and `matched` comes back as `phash` rather than
 * `new`. Rather than weaken that assertion to tolerate a dirty server, the test
 * brings its own new photographs. VERIFY_SEED pins it for a reproducible run.
 */
const RUN_SEED = Number(process.env.VERIFY_SEED || 0) || (Date.now() % 100000);

// `--dump-images <dir>` writes the test photographs out and exits. It is here
// because "the server said 缩略图读不出来" is not evidence about which side is wrong:
// the way to settle that is to hand the exact bytes to the exact decoder the
// server uses and read its error. It costs a few lines and it is the difference
// between a guess and a diagnosis.
const dumpFlag = argv.indexOf('--dump-images');

const ok = (m) => console.log(`  \u2713 ${m}`);
const bad = (m) => console.log(`  \u2717 ${m}`);
const note = (m) => console.log(`  \u2026 ${m}`);

let failures = 0;
let checks = 0;

/** One assertion, counted whether it passes or fails. */
function expect(condition, what, detail) {
  checks++;
  if (condition) {
    ok(what);
    return true;
  }
  bad(`${what}${detail ? ` -> ${detail}` : ''}`);
  failures++;
  return false;
}

/**
 * A check that only the mock can satisfy, because it depends on a control
 * endpoint. Skipping is printed rather than silent: a skipped check that nobody
 * notices is a check that was quietly deleted.
 */
function expectMockOnly(condition, what, detail, why) {
  if (!IS_MOCK) {
    note(`skip (real server): ${what}${why ? ` —${why}` : ''}`);
    return false;
  }
  return expect(condition, what, detail);
}

// ---------------------------------------------------------------- transport

/**
 * One request, returning the status, the headers and the body. Redirects are NOT
 * followed: several checks are about the 303 itself, and a client that silently
 * followed it would never see the thing being tested.
 */
async function call(method, path, options = {}) {
  const { headers = {}, body, rawBody } = options;
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { Connection: 'close', ...headers },
    body: rawBody !== undefined ? rawBody : body,
    redirect: 'manual',
  });
  const buf = Buffer.from(await res.arrayBuffer());
  return { status: res.status, headers: res.headers, body: buf };
}

async function json(method, path, options) {
  const res = await call(method, path, options);
  let parsed = null;
  try {
    parsed = JSON.parse(res.body.toString('utf8'));
  } catch {
    parsed = null;
  }
  return { ...res, json: parsed };
}

const jsonHeaders = { 'Content-Type': 'application/json' };
const has = (obj, ...keys) => keys.every((k) => obj && Object.prototype.hasOwnProperty.call(obj, k));
const errorCode = (body) => body && body.error && body.error.code;
const errorMessage = (body) => body && body.error && body.error.message;

/** A relative URL from a response, rebuilt on the base actually under test. */
function asLocal(url) {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    return parsed.pathname + parsed.search;
  } catch {
    return url.startsWith('/') ? url : null;
  }
}

const isUlid = (s) => typeof s === 'string' && /^[0-9A-HJKMNP-TV-Z]{26}$/.test(s);
const isCode = (s) => typeof s === 'string' && /^[0-9A-Za-z]{6}$/.test(s);
const isMillisecond = (n) => Number.isInteger(n) && Math.abs(Date.now() - n) < 24 * 3600 * 1000;
const ulid = () => {
  let ms = Date.now();
  let out = '';
  const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  const entropy = crypto.randomBytes(10);
  const b = Buffer.alloc(16);
  b.writeUIntBE(ms, 0, 6);
  entropy.copy(b, 6);
  out += alphabet[(b[0] & 0xe0) >> 5];
  let acc = 0;
  let bits = 0;
  for (let i = 0; i < 16; i++) {
    acc = (acc << 8) | b[i];
    bits += 8;
    while (bits >= 5) {
      out += alphabet[(acc >> (bits - 5)) & 0x1f];
      bits -= 5;
    }
  }
  return out;
};

// ---------------------------------------------------------------- JPEG, by hand

/*
 * A baseline YCbCr 4:4:4 JPEG encoder, about a hundred and fifty lines.
 *
 * It exists so that this file can produce a photo without shipping one: the
 * checks need bytes that are (a) decodable by the decoder the real server uses,
 * Go's image/jpeg, (b) visually different enough that a perceptual layer would
 * not merge them by accident, and (c) identical between two runs when they are
 * meant to be identical.
 *
 * This is the standard JPEG, not a lookalike: the quantisation and Huffman tables
 * are the reference ones from ITU T.81 Annex K, and the entropy coder is the real
 * one. A fake that produced "FFD8..." and nothing a decoder could read would make
 * every registration check a test of this file rather than of the server.
 *
 * Three components, not one. Go's decoder refuses a one-component JPEG with
 * "unsupported JPEG feature: number of components", so a grayscale encoder looks
 * exactly like a server bug from the outside — the server answers 400 "缩略图读不出来"
 * and the test reports that a valid photo was rejected. 4:4:4 (no chroma
 * subsampling) keeps the block loop trivial, and chroma is where the visual
 * difference between two test photos mostly lives.
 */
const ZIGZAG = [
  0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
  12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
  35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
  58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
];
const LUMA_QUANT = [
  16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
  14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
  18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
  49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
];
const CHROMA_QUANT = [
  17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
  24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
  99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
  99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
];
const DC_BITS = [0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0];
const DC_VALUES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
const AC_BITS = [0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d];
const AC_VALUES = [
  0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
  0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
  0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
  0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
  0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
  0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
  0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
  0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
  0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
  0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
  0xf9, 0xfa,
];
// Chroma gets its own Huffman tables, as every real encoder gives it. Sharing the
// luma tables would work here, but a decoder is entitled to expect the standard
// assignment and the point of this file is to be ordinary.
const CHROMA_DC_BITS = [0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0];
const CHROMA_DC_VALUES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
const CHROMA_AC_BITS = [0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77];
const CHROMA_AC_VALUES = [
  0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
  0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
  0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
  0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
  0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
  0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
  0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
  0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
  0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
  0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
  0xf9, 0xfa,
];

/**
 * A quantisation table as the DQT segment carries it.
 *
 * The tables above are written the way every reference prints them — in natural,
 * row-major order — but a JPEG stores them in zigzag order, the same order the
 * coefficients are scanned in. Writing them straight through puts the wrong step
 * size on almost every coefficient, which does not look like a broken file: it
 * looks like a photograph quietly crushed toward flat. That is exactly what it did
 * here, and it took the server's variance guard — which then refused to merge two
 * identical photos — to make the fault visible at all.
 */
const zigzagOrder = (table) => ZIGZAG.map((index) => table[index]);

/** Builds a canonical Huffman code table from a (bit-length counts, symbols) pair. */
function huffman(bits, symbols) {
  const table = new Map();
  let code = 0;
  let k = 0;
  for (let len = 1; len <= 16; len++) {
    for (let i = 0; i < bits[len - 1]; i++) {
      table.set(symbols[k++], { code, len });
      code++;
    }
    code <<= 1;
  }
  if (k !== symbols.length) throw new Error(`huffman: ${k} of ${symbols.length} symbols placed`);
  return table;
}
const DC_CODES = huffman(DC_BITS, DC_VALUES);
const AC_CODES = huffman(AC_BITS, AC_VALUES);
const CHROMA_DC_CODES = huffman(CHROMA_DC_BITS, CHROMA_DC_VALUES);
const CHROMA_AC_CODES = huffman(CHROMA_AC_BITS, CHROMA_AC_VALUES);

const DCT_COS = [];
for (let u = 0; u < 8; u++) {
  DCT_COS[u] = [];
  for (let x = 0; x < 8; x++) DCT_COS[u][x] = Math.cos(((2 * x + 1) * u * Math.PI) / 16);
}
const dctScale = (u) => (u === 0 ? Math.SQRT1_2 : 1);
const DCT_TMP = new Float64Array(64);

/** The 8x8 DCT of T.81 A.3.3, done separably so it is linear rather than quartic. */
function dct2d(block, out) {
  for (let y = 0; y < 8; y++) {
    for (let u = 0; u < 8; u++) {
      let sum = 0;
      for (let x = 0; x < 8; x++) sum += block[y * 8 + x] * DCT_COS[u][x];
      DCT_TMP[y * 8 + u] = sum * dctScale(u);
    }
  }
  for (let u = 0; u < 8; u++) {
    for (let v = 0; v < 8; v++) {
      let sum = 0;
      for (let y = 0; y < 8; y++) sum += DCT_TMP[y * 8 + u] * DCT_COS[v][y];
      out[v * 8 + u] = 0.25 * sum * dctScale(v);
    }
  }
  return out;
}

function bitLength(value) {
  let n = 0;
  let x = value < 0 ? -value : value;
  while (x) {
    n++;
    x >>= 1;
  }
  return n;
}

/**
 * Encodes one RGB image. `pixel(x, y)` returns [r, g, b], each 0..255.
 *
 * ITU-R BT.601 is the conversion the JFIF markers below promise, so it is the one
 * used: a decoder that inverts it has to get its colour back.
 */
function encodeJpeg(width, height, pixel) {
  const out = [];
  const byte = (b) => out.push(b & 0xff);
  const word = (v) => {
    byte(v >> 8);
    byte(v);
  };
  /**
   * One marker segment.
   *
   * The length arithmetic is asserted rather than trusted. Go's JPEG decoder
   * switches on the segment's *length* to decide how many components a frame has
   * (reader.go, processSOF), so a SOF payload one byte too long is reported as
   * "unsupported JPEG feature: number of components" — a message that points at
   * the component count when the real fault is the length. That cost an afternoon
   * once; the throw makes it cost a second.
   */
  const segment = (marker, payload, expectedLength) => {
    if (payload.length !== expectedLength) {
      throw new Error(`jpeg: marker ff${marker.toString(16)} payload is ${payload.length} bytes, expected ${expectedLength}`);
    }
    byte(0xff);
    byte(marker);
    word(payload.length + 2);
    for (const b of payload) byte(b);
  };

  byte(0xff);
  byte(0xd8); // SOI
  // APP0/JFIF: harmless, and it is what makes the colour transform unambiguous
  // rather than a decoder's guess.
  segment(0xe0, [0x4a, 0x46, 0x49, 0x46, 0x00, 1, 1, 0, 0, 1, 0, 1, 0, 0], 14);
  segment(0xdb, [0x00, ...zigzagOrder(LUMA_QUANT), 0x01, ...zigzagOrder(CHROMA_QUANT)], 130); // DQT, tables 0 and 1
  // SOF0: 6 fixed bytes (precision, height, width, and the component count at
  // index 5) plus 3 per component (id, sampling factors, quantisation table):
  // 6 + 3*3 = 15 for three components. Go decides how many components there are
  // from this length rather than from the count byte, so a length that is wrong
  // comes back as "unsupported JPEG feature: number of components" — a message
  // that names the count while the fault is the length.
  segment(0xc0, [
    8, height >> 8, height & 0xff, width >> 8, width & 0xff,
    3, // three components
    1, 0x22, 0, // Y  : 2x2 sampling, quant table 0
    2, 0x11, 1, // Cb : 1x1, quant table 1
    3, 0x11, 1, // Cr : 1x1, quant table 1
  ], 15);
  // DHT: each table is one spec byte (class in the high nibble, destination in
  // the low) followed by 16 bit-length counts, then the symbols. The spec byte is
  // not optional — without it the decoder reads a bit-length count as a table
  // class and answers "bad Th value".
  segment(0xc4, [0x00, ...DC_BITS, ...DC_VALUES, 0x10, ...AC_BITS, ...AC_VALUES], 2 + 16 + 12 + 16 + 162); // DHT 0: luma
  segment(0xc4, [0x01, ...CHROMA_DC_BITS, ...CHROMA_DC_VALUES, 0x11, ...CHROMA_AC_BITS, ...CHROMA_AC_VALUES], 2 + 16 + 12 + 16 + 162); // DHT 1: chroma
  segment(0xda, [
    3,
    1, 0x00, // Y  : DC table 0, AC table 0
    2, 0x11, // Cb : DC table 1, AC table 1
    3, 0x11, // Cr : DC table 1, AC table 1
    0, 63, 0, // spectral selection
  ], 10);

  let acc = 0;
  let bits = 0;
  const emit = (value, length) => {
    for (let i = length - 1; i >= 0; i--) {
      acc = (acc << 1) | ((value >> i) & 1);
      bits++;
      if (bits === 8) {
        byte(acc);
        // 0xFF inside entropy-coded data is padded to 0xFF 0x00.
        if (acc === 0xff) byte(0x00);
        acc = 0;
        bits = 0;
      }
    }
  };

  const block = new Float64Array(64);
  const dct = new Float64Array(64);
  const coefficients = new Float64Array(64);
  const predictors = [0, 0, 0];

  const encodeBlock = (component, quant, dcCodes, acCodes) => {
    dct2d(block, dct);
    // Divide by the quantisation step, and by nothing else. The DCT above already
    // carries the 1/4 from T.81 A.3.3, so a further factor of 8 here (which this
    // encoder had) quantises eight times too hard: every AC coefficient rounds to
    // zero, the picture flattens to its block averages, and the variance guard on
    // the server then refuses to merge two identical photos. The file is still a
    // legal JPEG, which is why only a measurement catches it.
    for (let i = 0; i < 64; i++) coefficients[i] = Math.round(dct[i] / quant[i]);

    const diff = coefficients[0] - predictors[component];
    predictors[component] = coefficients[0];
    const dcSize = bitLength(diff);
    if (dcSize > 11) throw new Error(`jpeg: DC category ${dcSize}`);
    const dcCode = dcCodes.get(dcSize);
    emit(dcCode.code, dcCode.len);
    if (dcSize) emit(diff >= 0 ? diff : diff + (1 << dcSize) - 1, dcSize);

    let run = 0;
    for (let k = 1; k < 64; k++) {
      const v = coefficients[ZIGZAG[k]];
      if (v === 0) {
        run++;
        continue;
      }
      while (run > 15) {
        const zrl = acCodes.get(0xf0);
        emit(zrl.code, zrl.len);
        run -= 16;
      }
      const size = bitLength(v);
      if (size > 10) throw new Error(`jpeg: AC category ${size}`);
      const acCode = acCodes.get((run << 4) | size);
      if (!acCode) throw new Error(`jpeg: no AC code for run=${run} size=${size}`);
      emit(acCode.code, acCode.len);
      emit(v >= 0 ? v : v + (1 << size) - 1, size);
      run = 0;
    }
    if (run) {
      const eob = acCodes.get(0x00);
      emit(eob.code, eob.len);
    }
  };

  // 4:2:0, which is not a preference: Go's image/jpeg decodes baseline 4:2:0 and
  // 4:2:2 and refuses 4:4:4 outright, so a test picture in any other sampling is a
  // picture the real server cannot read.
  //
  // One MCU is 16x16 pixels: four luma blocks and one chroma block per chroma
  // component, each 8x8. Cb and Cr are box-filtered 2:1 in both directions, which
  // is what "4:2:0" means on the wire.
  if (width % 16 || height % 16) {
    throw new Error(`jpeg: 4:2:0 needs dimensions that are multiples of 16 (got ${width}x${height})`);
  }
  for (let my = 0; my < height; my += 16) {
    for (let mx = 0; mx < width; mx += 16) {
      const luma = [new Float64Array(64), new Float64Array(64), new Float64Array(64), new Float64Array(64)];
      const cb = new Float64Array(64);
      const cr = new Float64Array(64);
      for (let y = 0; y < 16; y++) {
        for (let x = 0; x < 16; x++) {
          const [r, g, b] = pixel(mx + x, my + y);
          const yy = (0.299 * r + 0.587 * g + 0.114 * b) - 128;
          const u = (-0.168736 * r - 0.331264 * g + 0.5 * b);
          const v = (0.5 * r - 0.418688 * g - 0.081312 * b);
          luma[(y >> 3) * 2 + (x >> 3)][(y & 7) * 8 + (x & 7)] = yy;
          // Every 2x2 group becomes one chroma sample.
          if ((y & 1) === 0 && (x & 1) === 0) {
            const i = (y >> 1) * 8 + (x >> 1);
            cb[i] = u;
            cr[i] = v;
          } else {
            const i = (y >> 1) * 8 + (x >> 1);
            cb[i] = (cb[i] * 3 + u) / 4;
            cr[i] = (cr[i] * 3 + v) / 4;
          }
        }
      }
      for (let i = 0; i < 4; i++) {
        block.set(luma[i]);
        encodeBlock(0, LUMA_QUANT, DC_CODES, AC_CODES);
      }
      block.set(cb);
      encodeBlock(1, CHROMA_QUANT, CHROMA_DC_CODES, CHROMA_AC_CODES);
      block.set(cr);
      encodeBlock(2, CHROMA_QUANT, CHROMA_DC_CODES, CHROMA_AC_CODES);
    }
  }
  if (bits) emit((1 << (8 - bits)) - 1, 8 - bits);
  byte(0xff);
  byte(0xd9); // EOI
  return Buffer.from(out);
}

/**
 * A deterministic photograph-ish signal: a linear ramp, a soft disc, and a
 * checkerboard of soft tiles, arranged by `seed`.
 *
 * The tiles are the load-bearing part, and they are a consequence of the design
 * rather than decoration. The real server measures variance on a 64x64 grayscale
 * and refuses to auto-merge anything below 500, because flat images collide with
 * each other rather than hashing randomly (docs/tuping-design.md §3.3). A smooth
 * gradient measures in the tens, so a pair of them is never merged — and a test
 * that expects "identical bytes are one photo" against the real server would be
 * asserting something the design explicitly forbids. Large, high-contrast blocks
 * survive the downscale to 64x64 and put the variance where a real photograph's
 * is.
 *
 * `seed` also bends the structure: with the low bit set the ramp runs the other
 * way, and the tile phase flips.
 */
function scene(width, height, seed) {
  const tilt = ((seed * 37) % 200) / 200;
  const lift = (seed * 91) % 255;
  const flip = (seed % 2 === 0 ? 1 : -1) * 0.35;
  const discX = 0.18 + ((seed * 13) % 60) / 100;
  const discY = 0.2 + ((seed * 29) % 60) / 100;
  const phase = seed % 2 === 0 ? 0 : 1;
  const TILE = 20;
  return (x, y) => {
    const u = x / Math.max(1, width - 1);
    const v = y / Math.max(1, height - 1);
    const disc = Math.hypot(u - discX, v - discY) < 0.2 ? 80 : 0;
    const tile = ((Math.floor(x / TILE) + Math.floor(y / TILE) + phase) % 2 === 0 ? 1 : -1) * 70;
    const value = 28
      + 180 * (u * (0.25 + tilt + flip) + v * (0.75 - tilt + flip))
      + lift * 0.18 + disc + tile;
    const luma = Math.max(0, Math.min(255, Math.round(value)));
    // The two chroma planes are tilted against the luma ramp and against each
    // other, so two seeds differ in colour as well as in brightness. That is what
    // gives the perceptual layer something unambiguous to work with.
    const red = Math.max(0, Math.min(255, Math.round(luma * (0.75 + 0.5 * tilt) + 24 * Math.sin(seed + u * 3))));
    const blue = Math.max(0, Math.min(255, Math.round(luma * (0.85 - 0.4 * tilt) + 24 * Math.cos(seed + v * 3))));
    const green = Math.max(0, Math.min(255, Math.round((3 * luma - 0.587 * red - 0.114 * blue) / 0.587)));
    return [red, green, blue];
  };
}

const photo = (width, height, seed) => encodeJpeg(width, height, scene(width, height, seed));

/** Appends an APP1/Exif segment, to prove the server's re-encode removes it. */
function withFakeExif(jpeg) {
  const payload = Buffer.concat([
    Buffer.from('Exif\0\0', 'latin1'),
    Buffer.from('MM\u0000*\u0000\u0000\u0000\u0008', 'latin1'),
    Buffer.from('MockReviewExifMarkerPayload', 'latin1'),
  ]);
  const header = Buffer.alloc(4);
  header.writeUInt16BE(0xffe1, 0);
  header.writeUInt16BE(payload.length + 2, 2);
  return Buffer.concat([jpeg.subarray(0, 2), header, payload, jpeg.subarray(2)]);
}

// ---------------------------------------------------------------- simple PNG

function pngInfo(buffer) {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  if (buffer.length < 8 || !buffer.subarray(0, 8).equals(signature)) return null;
  let offset = 8;
  let header = null;
  const idat = [];
  while (offset + 8 <= buffer.length) {
    const length = buffer.readUInt32BE(offset);
    const type = buffer.subarray(offset + 4, offset + 8).toString('ascii');
    const data = buffer.subarray(offset + 8, offset + 8 + length);
    if (type === 'IHDR') {
      header = {
        width: data.readUInt32BE(0),
        height: data.readUInt32BE(4),
        depth: data[8],
        colorType: data[9],
        interlace: data[12],
      };
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    offset += 12 + length;
  }
  return { header, data: Buffer.concat(idat) };
}

/**
 * Decodes the 8-bit PNGs this test sees into RGB rows, so the QR's quiet zone can
 * be measured rather than assumed. Only the filter types the encoders actually
 * emit are implemented; an unknown one is an error, not a guess.
 */
function decodePng(buffer) {
  const info = pngInfo(buffer);
  if (!info || !info.header) return null;
  const { width, height, depth, colorType, interlace } = info.header;
  if (depth !== 8 || interlace !== 0) return null;
  const channels = colorType === 0 ? 1 : colorType === 2 ? 3 : colorType === 3 ? 1 : colorType === 4 ? 2 : colorType === 6 ? 4 : 0;
  if (!channels) return null;

  const raw = zlib.inflateSync(info.data);
  const stride = width * channels;
  const pixels = Buffer.alloc(stride * height);
  let pos = 0;
  for (let y = 0; y < height; y++) {
    const filter = raw[pos++];
    const line = raw.subarray(pos, pos + stride);
    pos += stride;
    const current = pixels.subarray(y * stride, (y + 1) * stride);
    const prior = y > 0 ? pixels.subarray((y - 1) * stride, y * stride) : null;
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? current[i - channels] : 0;
      const b = prior ? prior[i] : 0;
      const c = prior && i >= channels ? prior[i - channels] : 0;
      let value = line[i];
      switch (filter) {
        case 1: value += a; break;
        case 2: value += b; break;
        case 3: value += (a + b) >> 1; break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a);
          const pb = Math.abs(p - b);
          const pc = Math.abs(p - c);
          value += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
          break;
        }
        default: break;
      }
      current[i] = value & 0xff;
    }
  }
  return { width, height, channels, pixels };
}

/**
 * Whether a pixel reads as dark, compositing it onto white first.
 *
 * The alpha is not decoration: the real server leaves the quiet zone fully transparent
 * on purpose, so that the client decides what the code sits on — a photograph with a
 * veil, a white card, anything (server/internal/qr/qr.go). Only the modules are opaque
 * black. A decoder that ignores alpha reads that transparent margin as RGB 0,0,0, calls
 * the whole plate dark, and then finds no quiet zone and no finder pattern in a QR code
 * that scans perfectly: which is exactly what this test did against the Go server until
 * the composite below was added. White is what a scanner sees for a transparent pixel in
 * print, and it is the assumption the rest of this inspection is built on.
 */
const isDark = (png, x, y) => {
  const i = (y * png.width + x) * png.channels;
  let r;
  let g;
  let b;
  if (png.channels >= 3) {
    r = png.pixels[i];
    g = png.pixels[i + 1];
    b = png.pixels[i + 2];
  } else {
    r = g = b = png.pixels[i];
  }
  const alpha = png.channels === 4 ? png.pixels[i + 3] / 255 : png.channels === 2 ? png.pixels[i + 1] / 255 : 1;
  if (alpha < 1) {
    r = r * alpha + 255 * (1 - alpha);
    g = g * alpha + 255 * (1 - alpha);
    b = b * alpha + 255 * (1 - alpha);
  }
  return (r + g + b) / 3 < 128;
};

/** The bounding box of everything that is not white — the code, without its margin. */
function darkBounds(png) {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < png.height; y++) {
    for (let x = 0; x < png.width; x++) {
      if (!isDark(png, x, y)) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  return minX === Infinity ? null : { minX, minY, maxX, maxY };
}

/**
 * A QR code is a grid of modules, and the generator picks an integer scale and
 * then resamples to the size that was asked for — so the modules are not all the
 * same number of pixels wide, and probing "the pixel at module 6" is wrong about
 * half the time.
 *
 * This reads the grid the way a scanner does: the whole code is a square of
 * `17 + 4 * version` modules inside a white margin, so the extent of the dark
 * pixels says how big a module can be, and the finder patterns then have to
 * actually line up on that grid. A candidate version that puts the grid through
 * the middle of a finder pattern is rejected rather than guessed at, which is why
 * this returns `version: 0` instead of a plausible-looking wrong answer.
 */
function inspectQr(png) {
  const result = { finders: 0, module: 0, modules: 0, version: 0, margins: null, minModules: 0, square: false };
  const bounds = darkBounds(png);
  if (!bounds) return result;

  const contentWidth = bounds.maxX - bounds.minX + 1;
  const contentHeight = bounds.maxY - bounds.minY + 1;
  result.square = Math.abs(contentWidth - contentHeight) <= 2;
  result.margins = {
    left: bounds.minX,
    right: png.width - 1 - bounds.maxX,
    top: bounds.minY,
    bottom: png.height - 1 - bounds.maxY,
  };

  let best = null;
  for (let version = 1; version <= 40; version++) {
    const modules = 17 + version * 4;
    if (modules > contentWidth || modules > contentHeight) continue;
    const moduleW = contentWidth / modules;
    const moduleH = contentHeight / modules;
    if (moduleW < 2) continue;
    const padX = (contentWidth - modules * Math.round(moduleW)) / 2;
    const padY = (contentHeight - modules * Math.round(moduleH)) / 2;
    const error = Math.abs(padX) + Math.abs(padY) + Math.abs(moduleW - moduleH) * modules;

    const candidate = {
      version, modules, moduleW, moduleH, module: (moduleW + moduleH) / 2, error,
      left: bounds.minX, top: bounds.minY,
    };
    // Read one module at its centre. The centre is at least half a module from
    // any boundary, which is what survives the resampling.
    const moduleAt = (x, y) => isDark(
      png,
      Math.min(png.width - 1, Math.max(0, Math.round(candidate.left + (x + 0.5) * moduleW))),
      Math.min(png.height - 1, Math.max(0, Math.round(candidate.top + (y + 0.5) * moduleH))),
    );
    const finderAt = (ox, oy) => {
      const counts = { outer: [0, 0], middle: [0, 0], core: [0, 0] };
      for (let y = 0; y < 7; y++) {
        for (let x = 0; x < 7; x++) {
          const edge = x === 0 || y === 0 || x === 6 || y === 6;
          const core = x >= 2 && x <= 4 && y >= 2 && y <= 4;
          const bucket = edge ? 'outer' : core ? 'core' : 'middle';
          counts[bucket][1]++;
          if (moduleAt(ox + x, oy + y)) counts[bucket][0]++;
        }
      }
      return counts.outer[0] / counts.outer[1] > 0.9
        && counts.middle[0] / counts.middle[1] < 0.1
        && counts.core[0] / counts.core[1] > 0.9;
    };
    candidate.finders = 0;
    for (const [ox, oy] of [[0, 0], [modules - 7, 0], [0, modules - 7]]) {
      if (finderAt(ox, oy)) candidate.finders++;
    }
    // A grid that does not explain all three finder patterns is the wrong grid,
    // however well its arithmetic fits. Only a real one is allowed to win.
    if (candidate.finders !== 3) continue;
    if (!best || candidate.error < best.error) best = candidate;
  }
  if (!best) return result;

  result.version = best.version;
  result.modules = best.modules;
  result.module = best.module;
  result.finders = best.finders;
  result.minModules = Math.min(
    result.margins.left, result.margins.right, result.margins.top, result.margins.bottom,
  ) / best.module;
  return result;
}

/** The page with its HTML comments removed, so "no <script>" cannot be defeated by prose. */
const stripComments = (html) => html.replace(/<!--[\s\S]*?-->/g, '');

// ---------------------------------------------------------------- API helpers

async function issueDevice(name) {
  return json('POST', '/v1/devices', {
    headers: jsonHeaders,
    body: JSON.stringify({ name }),
  });
}

async function issueDeviceWith(deviceId, token) {
  const payload = { deviceId };
  if (token !== undefined) payload.token = token;
  return json('POST', '/v1/devices', { headers: jsonHeaders, body: JSON.stringify(payload) });
}

/** Raw multipart, so the exact part names and the absence of extras are ours. */
async function registerPhoto(device, { thumb, meta }) {
  const boundary = `----verify${crypto.randomBytes(8).toString('hex')}`;
  const chunks = [
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="meta"\r\n\r\n${JSON.stringify(meta)}\r\n`),
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="thumb"; filename="thumb.jpg"\r\nContent-Type: image/jpeg\r\n\r\n`),
    thumb,
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ];
  return json('POST', '/v1/photos', {
    headers: {
      'Content-Type': `multipart/form-data; boundary=${boundary}`,
      'X-Device-Id': device.id,
      'X-Device-Token': device.token,
      'X-Owner-Token': device.ownerToken,
    },
    rawBody: Buffer.concat(chunks),
  });
}

async function postComment(payload) {
  return json('POST', '/v1/comments', { headers: jsonHeaders, body: JSON.stringify(payload) });
}

// ---------------------------------------------------------------- WebSocket

function openLive(imageId, device, { timeoutMs = 5000 } = {}) {
  const url = `${BASE.replace(/^http/, 'ws')}/v1/live/${imageId}`;
  return new Promise((resolve, reject) => {
    const headers = device ? { 'X-Device-Id': device.id, 'X-Device-Token': device.token } : {};
    const socket = new WebSocket(url, { headers });
    const timer = setTimeout(() => {
      socket.terminate();
      reject(new Error('live socket timed out'));
    }, timeoutMs);
    socket.on('open', () => {
      clearTimeout(timer);
      resolve(socket);
    });
    socket.on('unexpected-response', (_req, res) => {
      clearTimeout(timer);
      socket.terminate();
      reject(new Error(`upgrade refused with ${res.statusCode}`));
    });
    socket.on('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

/** Resolves with the first message matching `predicate`, or rejects on timeout. */
function awaitMessage(socket, predicate, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off('message', onMessage);
      reject(new Error('no matching live message arrived'));
    }, timeoutMs);
    function onMessage(raw) {
      let message = null;
      try {
        message = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (!predicate(message)) return;
      clearTimeout(timer);
      socket.off('message', onMessage);
      resolve(message);
    }
    socket.on('message', onMessage);
  });
}

// ---------------------------------------------------------------- the run

const MARK = crypto.randomBytes(3).toString('hex');

if (dumpFlag >= 0) {
  const fs = require('fs');
  const path = require('path');
  const dir = argv[dumpFlag + 1] || '.';
  fs.mkdirSync(dir, { recursive: true });
  const images = {
    'a-320x240.jpg': photo(320, 240, RUN_SEED + 11),
    'b-320x208.jpg': photo(320, 208, RUN_SEED + 29),
    'c-240x320.jpg': photo(240, 320, RUN_SEED + 47),
    'exif-320x240.jpg': withFakeExif(photo(320, 240, RUN_SEED + 63)),
    // A second seed of the same shape, so the checks that need two genuinely
    // different photos can be measured as well as asserted.
    'alt-a-320x240.jpg': photo(320, 240, RUN_SEED + 999),
    'alt-b-320x208.jpg': photo(320, 208, RUN_SEED + 1234),
  };
  for (const [name, bytes] of Object.entries(images)) {
    fs.writeFileSync(path.join(dir, name), bytes);
    console.log(`${name}: ${bytes.length} bytes (seed ${RUN_SEED})`);
  }
  process.exit(0);
}

(async () => {
  console.log(`\n== 图评 (tuping) contract test against ${BASE} ==`);
  console.log(`   ${IS_MOCK ? 'this looks like the Node mock' : 'this is NOT the mock: mock-only checks will be reported as skipped'}`);
  console.log(`   run mark: ${MARK}, image seed: ${RUN_SEED}\n`);

  // A control reset, when there is one. Without it the mock's counters carry over
  // between runs and the earlier state makes a later failure hard to read. The
  // data goes too: this run's photographs are new bytes, but a previous run's
  // device ids and codes would still be lying around, and "an unknown code 404s"
  // is only meaningful in a world this test can describe.
  if (IS_MOCK) {
    try {
      await call('GET', '/__control/reset?data=1');
    } catch {
      /* not fatal: the protocol checks below do not need it */
    }
  }

  // ---- 1. liveness -----------------------------------------------------
  console.log('[1] /healthz is live and reports a version');
  const health = await json('GET', '/healthz');
  expect(health.status === 200, `GET /healthz is 200 (version "${health.json && health.json.version}")`,
    `status ${health.status}`);
  expect(health.json && health.json.ok === true, '/healthz says ok:true', JSON.stringify(health.json));
  expect(health.json && typeof health.json.version === 'string' && health.json.version.length > 0,
    '/healthz carries a non-empty version string');
  expect(health.json && Number.isInteger(health.json.uptimeSec) && health.json.uptimeSec >= 0,
    `/healthz reports uptimeSec (${health.json && health.json.uptimeSec}s)`);
  expect(health.json && typeof health.json.baseUrl === 'string' && health.json.baseUrl.startsWith('http'),
    `/healthz reports the baseUrl a phone should use (${health.json && health.json.baseUrl})`);

  // ---- 2. devices ------------------------------------------------------
  console.log('\n[2] device issuance, and re-issuance without the token');
  const deviceId = ulid();
  const first = await issueDevice('verify.js');
  expect(first.status === 200 && isUlid(first.json && first.json.deviceId),
    `a device with no deviceId is minted one (${first.json && first.json.deviceId})`, `status ${first.status}`);
  expect(first.json && typeof first.json.deviceToken === 'string' && first.json.deviceToken.length >= 16,
    'deviceToken is an opaque string of at least 16 chars');
  expect(first.json && typeof first.json.ownerToken === 'string' && first.json.ownerToken.length >= 16,
    'ownerToken is an opaque string of at least 16 chars');
  expect(first.json && isMillisecond(first.json.serverTime), 'serverTime is milliseconds since the epoch');

  // Re-issuing a device id the server has already issued, without the token, is a
  // 401. This is the rule that stops anyone claiming someone else's device.
  const stolen = await issueDeviceWith(first.json.deviceId);
  expect(stolen.status === 401 && errorCode(stolen.json) === 'unauthorized',
    're-issuing a known deviceId with no token is 401 unauthorized',
    `status ${stolen.status} body ${JSON.stringify(stolen.json)}`);

  const wrong = await issueDeviceWith(first.json.deviceId, 'not-the-token');
  expect(wrong.status === 401, 're-issuing a known deviceId with a wrong token is 401', `status ${wrong.status}`);

  // The same device id with the right token is how a device rotates: it must work,
  // and it must hand back a working token.
  const rotated = await issueDeviceWith(first.json.deviceId, first.json.deviceToken);
  expect(rotated.status === 200 && rotated.json && isUlid(rotated.json.deviceId),
    're-issuing with the correct token succeeds (token rotation)', `status ${rotated.status}`);
  const device = {
    id: rotated.json.deviceId,
    token: rotated.json.deviceToken,
    ownerToken: rotated.json.ownerToken,
  };
  expect(typeof device.token === 'string' && device.token.length >= 8, 'the rotated token is usable');

  // A second device, for "the same bytes from a different device".
  const otherFirst = await issueDevice('verify.js (second TV)');
  const other = {
    id: otherFirst.json.deviceId,
    token: otherFirst.json.deviceToken,
    ownerToken: otherFirst.json.ownerToken,
  };
  expect(otherFirst.status === 200 && other.id !== device.id, `a second device was issued (${other.id})`);

  // ---- 3. register a photo --------------------------------------------
  console.log('\n[3] registering a photo yields an identity, a code and two URLs');
  const imageA = photo(320, 240, RUN_SEED + 11);
  const imageB = photo(320, 208, RUN_SEED + 29);
  const imageC = photo(240, 320, RUN_SEED + 47);
  const exifImage = withFakeExif(photo(320, 240, RUN_SEED + 63));

  const regA = await registerPhoto(device, {
    thumb: imageA,
    meta: { localId: `${MARK}-A`, source: 'fnos', sha256Original: null, width: 4032, height: 3024 },
  });
  expect(regA.status === 200, 'POST /v1/photos is 200 for a valid registration', `status ${regA.status} ${regA.body.toString('utf8').slice(0, 160)}`);
  const photoA = regA.json || {};
  expect(isUlid(photoA.imageId), `imageId is a 26-char ULID (${photoA.imageId})`);
  expect(isCode(photoA.code), `code is 6 base62 chars (${photoA.code})`);
  expect(['sha256', 'phash', 'new'].includes(photoA.matched), `matched is one of sha256|phash|new (${photoA.matched})`);
  expect(photoA.matched === 'new', 'a brand-new photo is matched:"new"');
  expect(typeof photoA.qrUrl === 'string' && photoA.qrUrl.includes(`/v1/qr/${photoA.code}.png`),
    `qrUrl points at the code's PNG (${photoA.qrUrl})`);
  expect(typeof photoA.thumbUrl === 'string' && photoA.thumbUrl.includes('/t/') && photoA.thumbUrl.includes('sig='),
    'thumbUrl is present and carries a signature');
  expect(photoA.scope === 'image', `scope is "image" (${photoA.scope})`);
  expect(Number.isInteger(photoA.commentCount), `commentCount is a number (${photoA.commentCount})`);

  // The protocol's hex rule, refused loudly rather than silently ignored.
  const badHex = await registerPhoto(device, {
    thumb: imageA,
    meta: { localId: `${MARK}-badhex`, source: 'fnos', sha256Original: 'ZZ'.repeat(32), width: 100, height: 100 },
  });
  expect(badHex.status === 400 && errorCode(badHex.json) === 'bad_request',
    'a sha256Original that is not 64 hex chars is 400 bad_request',
    `status ${badHex.status} body ${JSON.stringify(badHex.json)}`);

  // ---- 4. the QR PNG ---------------------------------------------------
  console.log('\n[4] the QR PNG is a real, square, quiet-zoned code');
  const qr = await call('GET', `/v1/qr/${photoA.code}.png?size=300`);
  expect(qr.status === 200 && String(qr.headers.get('content-type')).startsWith('image/png'),
    `GET /v1/qr/{code}.png?size=300 is image/png`, `status ${qr.status} type ${qr.headers.get('content-type')}`);
  const qrPng = decodePng(qr.body);
  expect(Boolean(qrPng), 'the QR body decodes as an 8-bit PNG');
  if (qrPng) {
    expect(qrPng.width === qrPng.height, `the QR is square (${qrPng.width}x${qrPng.height})`);
    expect(qrPng.width >= 300 && qrPng.height >= 300,
      `the QR is at least the requested 300px on both axes (${qrPng.width}x${qrPng.height})`);
    const inspected = inspectQr(qrPng);
    expect(inspected.finders === 3,
      `three 7x7 finder patterns decode, so this is a real QR code (version ${inspected.version}, module ~${inspected.module.toFixed(2)}px)`,
      `found ${inspected.finders}/3 at ${qrPng.width}x${qrPng.height}: ${JSON.stringify(inspected)}`);
    expect(inspected.version >= 1 && inspected.version <= 40,
      `the module count implies a valid QR version (${inspected.modules} modules)`,
      JSON.stringify(inspected));
    expect(inspected.minModules >= 3.5,
      `the white quiet zone is at least four modules on every side (measured ${inspected.minModules.toFixed(1)} modules: ${JSON.stringify(inspected.margins)})`);
  }
  const qrSmall = await call('GET', `/v1/qr/${photoA.code}.png`);
  const qrSmallPng = decodePng(qrSmall.body);
  expect(qrSmallPng && qrSmallPng.width >= 440 && qrSmallPng.height >= 440,
    `size defaults to 440 (${qrSmallPng && qrSmallPng.width}x${qrSmallPng && qrSmallPng.height})`);
  const qrClamped = await call('GET', `/v1/qr/${photoA.code}.png?size=10`);
  const qrClampedPng = decodePng(qrClamped.body);
  expect(qrClamped.status === 200 && qrClampedPng && qrClampedPng.width >= 96,
    `a size below the floor is clamped up to 96 (${qrClampedPng && qrClampedPng.width}px)`);

  // ---- 5. the signed thumbnail ----------------------------------------
  console.log('\n[5] the signed thumbnail URL is the only way to the bytes');
  const thumbPath = asLocal(photoA.thumbUrl);
  const thumb = await call('GET', thumbPath);
  expect(thumb.status === 200 && String(thumb.headers.get('content-type')).startsWith('image/jpeg'),
    `GET /t/{code}?w=&exp=&sig= serves JPEG (${thumb.body.length} bytes)`,
    `status ${thumb.status} type ${thumb.headers.get('content-type')}`);
  expect(thumb.body.length > 1000, 'the served thumbnail is a real image, not a placeholder');
  expect(String(thumb.headers.get('cache-control') || '').includes('max-age=600'),
    `Cache-Control is private, max-age=600 (${thumb.headers.get('cache-control')})`);

  const sigBroken = thumbPath.replace(/sig=([^&]*)/, (m, s) => `sig=${s}${s.endsWith('A') ? 'B' : 'A'}`);
  const tampered = await call('GET', sigBroken);
  expect(tampered.status === 401 && errorCode(JSON.parse(tampered.body.toString('utf8') || '{}')) === 'unauthorized',
    'appending a character to sig is refused with 401 unauthorized',
    `status ${tampered.status}`);

  const unsigned = await call('GET', `/t/${photoA.code}?w=320&exp=${Date.now() + 600000}`);
  expect(unsigned.status === 401, 'a thumbnail URL with no sig is refused with 401', `status ${unsigned.status}`);

  // An expiry in the past, with the rest of the URL left alone. On the mock the
  // signature is still valid, so this isolates the expiry check; on the real
  // server the signature also fails, so what is checked is that the answer is 401
  // either way and never a 200.
  const expiredSig = new URL(`${BASE}${thumbPath}`).searchParams.get('sig');
  const expired = await call('GET', `/t/${photoA.code}?w=320&exp=1000&sig=${expiredSig}`);
  expect(expired.status === 401, 'a thumbnail URL whose exp is in the past is refused with 401',
    `status ${expired.status}`);

  // ---- 6. no metadata survives the re-encode --------------------------
  console.log('\n[6] the server re-encodes, so no Exif reaches the phone');
  const exifReg = await registerPhoto(device, {
    thumb: exifImage,
    meta: { localId: `${MARK}-exif`, source: 'fnos', sha256Original: null, width: 320, height: 240 },
  });
  expect(exifReg.status === 200, 'a thumbnail carrying an APP1/Exif segment is accepted', `status ${exifReg.status}`);
  const exifThumbPath = asLocal(exifReg.json && exifReg.json.thumbUrl);
  const exifThumb = await call('GET', exifThumbPath);
  const exifBody = exifThumb.body;
  expect(exifThumb.status === 200, `its signed thumbnail serves (${exifBody.length} bytes)`, `status ${exifThumb.status}`);
  expect(!exifBody.includes('Exif'), 'the served bytes contain no "Exif" substring anywhere');
  // The upload really did carry the marker, or the check above would pass for the
  // wrong reason. Confirm against the bytes we sent.
  expect(exifImage.includes('Exif'), ' (the bytes uploaded did contain an Exif marker, so the check has teeth)');

  // ---- 7. the pre-registration cost control ---------------------------
  console.log('\n[7] the same device + source + localId is answered from the stored row');
  const repeat = await registerPhoto(device, {
    thumb: imageA,
    meta: { localId: `${MARK}-A`, source: 'fnos', sha256Original: null, width: 4032, height: 3024 },
  });
  expect(repeat.status === 200 && repeat.json && repeat.json.imageId === photoA.imageId,
    're-registering the same localId returns the same imageId',
    `${repeat.json && repeat.json.imageId} vs ${photoA.imageId}`);
  expect(repeat.json && repeat.json.code === photoA.code, 'and the same code', `${repeat.json && repeat.json.code}`);

  // ---- 8. identical bytes are one identity ----------------------------
  console.log('\n[8] identical bytes from a different device are the same photo');
  // Print the digest of the bytes actually being sent, twice. If this check ever
  // fails, the first question is whether it was sent the same bytes at all — and
  // the answer has to be in the output, not in someone's head.
  console.log(`   thumb bytes digest: ${crypto.createHash('sha256').update(imageA).digest('hex').slice(0, 16)} (${imageA.length} bytes)`);
  const twin = await registerPhoto(other, {
    thumb: imageA,
    meta: { localId: `${MARK}-twin`, source: 'fnos', sha256Original: null, width: 4032, height: 3024 },
  });
  console.log(`   twin re-sent digest: ${crypto.createHash('sha256').update(imageA).digest('hex').slice(0, 16)} (matched=${twin.json && twin.json.matched})`);
  expect(twin.status === 200 && twin.json && twin.json.imageId === photoA.imageId,
    'the same thumbnail bytes sent by a different device resolve to the same imageId',
    `${twin.json && twin.json.imageId} vs ${photoA.imageId} matched=${twin.json && twin.json.matched}`);
  expect(twin.json && twin.json.code === photoA.code, 'and answer with the same code');

  // ---- 9. sha256 outranks the pixels ----------------------------------
  console.log('\n[9] a sha256 the server has seen wins, even with different pixels');
  const shaSeed = crypto.randomBytes(32).toString('hex');
  const withSha = await registerPhoto(device, {
    thumb: imageB,
    meta: { localId: `${MARK}-sha1`, source: 'fnos', sha256Original: shaSeed, width: 320, height: 200 },
  });
  const differentPixels = await registerPhoto(other, {
    // Deliberately different bytes: identity here has to come from the hash.
    thumb: photo(320, 208, RUN_SEED + 77),
    meta: { localId: `${MARK}-sha2`, source: 'wechat', sha256Original: shaSeed, width: 320, height: 200 },
  });
  expect(withSha.status === 200 && differentPixels.status === 200,
    'both registrations were accepted');
  if (withSha.json && differentPixels.json) {
    expect(differentPixels.json.imageId === withSha.json.imageId,
      'a seen sha256Original resolves to the existing imageId despite different thumbnails',
      `${differentPixels.json.imageId} vs ${withSha.json.imageId}`);
    expect(differentPixels.json.matched === 'sha256', `and reports matched:"sha256" (${differentPixels.json.matched})`);
    expect(differentPixels.json.code === withSha.json.code, 'and answers with the same code');
  }

  // ---- 10. refusals ----------------------------------------------------
  console.log('\n[10] the two refusals a phone can trigger by accident or on purpose');
  // Refusals come from their own photo and their own anonId. Two reasons, both of
  // them about the rate limits rather than about the checks: a refused attempt
  // still consumes the per-minute budget the real server counts by IP, and the
  // form checks below need that budget to still have room in it.
  const adTarget = await registerPhoto(device, {
    thumb: imageB,
    meta: { localId: `${MARK}-ads`, source: 'fnos', sha256Original: null, width: 320, height: 200 },
  });
  expect(adTarget.status === 200 && adTarget.json, 'a photo for the refusal checks was registered', `status ${adTarget.status}`);
  const adCode = (adTarget.json && adTarget.json.code) || photoA.code;

  const longContent = '\u957f'.repeat(120);
  const tooLong = await postComment({
    code: adCode, content: longContent, nickname: 'verify', idemKey: crypto.randomUUID(),
    anonId: `anon-${MARK}-long`, client: 'h5/1',
  });
  expect(tooLong.status === 400 && errorCode(tooLong.json) === 'too_long',
    `a comment of ${[...longContent].length} runes is refused with too_long`,
    `status ${tooLong.status} body ${JSON.stringify(tooLong.json)}`);
  expect(typeof errorMessage(tooLong.json) === 'string' && errorMessage(tooLong.json).length > 0,
    'the refusal carries a human-readable Chinese message');

  // These have to fit inside MAX_CONTENT_RUNES, or they answer too_long and the ad
  // patterns they exist to probe are never reached: the ceiling is checked first, on both
  // servers (§9.5). The run mark stays on the front so a failure is still traceable to
  // this run, and the two strings below are 18 runes each.
  const ads = [
    { label: 'a URL', content: `${MARK} http://a.cn` },
    { label: 'an 11-digit phone number starting with 1', content: `${MARK} 13800138000` },
    { label: 'the phrase \u52a0\u5fae\u4fe1', content: `${MARK} \u52a0\u5fae\u4fe1\u53f7` },
  ];
  // Asserted rather than assumed, for the reason §17.14 records: when the ceiling moved
  // to 20 runes, fixture text that was longer than that stopped testing the ad patterns
  // and started testing the length, which is a check that passes while measuring nothing.
  expect(ads.every((ad) => [...ad.content].length <= 20),
    'every ad fixture fits inside the 20-rune ceiling, so the refusals below are about the patterns',
    JSON.stringify(ads.map((ad) => [...ad.content].length)));
  for (const ad of ads) {
    const refused = await postComment({
      code: adCode,
      content: ad.content,
      nickname: 'verify',
      idemKey: crypto.randomUUID(),
      anonId: `anon-${MARK}-ad`,
      client: 'h5/1',
    });
    expect(refused.status === 400 && errorCode(refused.json) === 'content_rejected',
      `a comment containing ${ad.label} is refused with content_rejected`,
      `status ${refused.status} body ${JSON.stringify(refused.json)}`);
  }

  const commentsWithoutAuth = await json('GET', `/v1/comments?imageId=${photoA.imageId}`);
  expect(commentsWithoutAuth.status === 401,
    'GET /v1/comments without device headers is 401 (album-end authentication)',
    `status ${commentsWithoutAuth.status}`);

  // ---- 11. a clean comment is published -------------------------------
  console.log('\n[11] a clean comment is published, listed and counted');
  const cleanContent = `${MARK} \u8fd9\u5f20\u597d\u770b`;
  const before = await json('GET', `/v1/photos/${photoA.code}`);
  const published = await postComment({
    code: photoA.code,
    content: cleanContent,
    nickname: '\u5c0f\u738b',
    idemKey: crypto.randomUUID(),
    anonId: `anon-${MARK}-1`,
    client: 'h5/1',
  });
  expect(published.status === 200, 'POST /v1/comments accepts a clean comment', `status ${published.status} ${published.body.toString('utf8').slice(0, 160)}`);
  const comment = published.json || {};
  expect(isUlid(comment.id), `the new comment id is a ULID (${comment.id})`);
  expect(comment.status === 1, `a clean comment is published with status 1 (${comment.status})`);
  expect(isMillisecond(comment.createdAt), `createdAt is milliseconds (${comment.createdAt})`);

  const listed = await json('GET', `/v1/comments?imageId=${photoA.imageId}`, {
    headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token },
  });
  expect(listed.status === 200 && Array.isArray(listed.json && listed.json.items),
    'GET /v1/comments returns items', `status ${listed.status} body ${JSON.stringify(listed.json).slice(0, 160)}`);
  const items = (listed.json && listed.json.items) || [];
  const found = items.filter((c) => c.content === cleanContent);
  expect(found.length === 1 && found[0].id === comment.id,
    'the comment appears in the history exactly once', `${found.length} match(es)`);
  expect(found.length === 1 && found[0].nickname === '\u5c0f\u738b',
    `its nickname survives (${found.length && found[0].nickname})`);
  expect(items.every((c) => c.status === 1), 'only status-1 comments are listed');
  expect(items.every((c, i) => i === 0 || items[i - 1].createdAt <= c.createdAt),
    'comments come back oldest first');
  expect(isMillisecond(listed.json && listed.json.serverTime), 'the response carries serverTime in ms');

  const after = await json('GET', `/v1/photos/${photoA.code}`);
  expect(after.json && after.json.commentCount === before.json.commentCount + 1,
    `GET /v1/photos/{code} reports commentCount incremented (${before.json && before.json.commentCount} -> ${after.json && after.json.commentCount})`);
  expect(after.json && after.json.imageId === photoA.imageId && after.json.code === photoA.code,
    'the detail response names the same image and code');
  expect(after.json && after.json.scope === 'image' && Number.isInteger(after.json.width) && Number.isInteger(after.json.visibility),
    'the detail response carries scope, width/height and visibility');

  // `since` is exclusive: exactly the boundary the danmaku poll sits on.
  const sinceExclusive = await json('GET', `/v1/comments?imageId=${photoA.imageId}&since=${comment.createdAt}`, {
    headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token },
  });
  expect(sinceExclusive.json && !sinceExclusive.json.items.some((c) => c.id === comment.id),
    'since is exclusive: the comment is absent when since equals its createdAt');
  const sinceInclusive = await json('GET', `/v1/comments?imageId=${photoA.imageId}&since=${comment.createdAt - 1}`, {
    headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token },
  });
  expect(sinceInclusive.json && sinceInclusive.json.items.some((c) => c.id === comment.id),
    'and present when since is one millisecond earlier');
  const limited = await json('GET', `/v1/comments?imageId=${photoA.imageId}&limit=1`, {
    headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token },
  });
  expect(limited.json && limited.json.items.length <= 1, 'limit is honoured (limit=1 returns at most one)');

  // ---- 12. idempotency and the duplicate window -----------------------
  console.log('\n[12] the same submission twice is still one comment');
  const idemKey = crypto.randomUUID();
  const idemContent = `${MARK} \u5e42\u7b49\u6d4b\u8bd5`;
  const firstPost = await postComment({
    code: photoA.code, content: idemContent, nickname: '\u5c0f\u738b', idemKey, anonId: `anon-${MARK}-2`, client: 'h5/1',
  });
  const secondPost = await postComment({
    code: photoA.code, content: idemContent, nickname: '\u5c0f\u738b', idemKey, anonId: `anon-${MARK}-2`, client: 'h5/1',
  });
  expect(firstPost.status === 200 && secondPost.status === 200, 'both submissions answered 200');
  expect(firstPost.json && secondPost.json && firstPost.json.id === secondPost.json.id,
    `the same idemKey yields the same comment id (${firstPost.json && firstPost.json.id})`,
    `${firstPost.json && firstPost.json.id} vs ${secondPost.json && secondPost.json.id}`);
  const afterIdem = await json('GET', `/v1/comments?imageId=${photoA.imageId}`, {
    headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token },
  });
  expect(((afterIdem.json && afterIdem.json.items) || []).filter((c) => c.content === idemContent).length === 1,
    'and only one comment exists for it');

  // The duplicate window is a content rule, not a transport one: the same words,
  // a different idemKey, inside five minutes, is still one comment.
  const dupContent = `${MARK} \u91cd\u590d\u5185\u5bb9`;
  const dup1 = await postComment({
    code: photoA.code, content: dupContent, nickname: 'a', idemKey: crypto.randomUUID(), anonId: `anon-${MARK}-3`, client: 'h5/1',
  });
  const dup2 = await postComment({
    code: photoA.code, content: dupContent, nickname: 'b', idemKey: crypto.randomUUID(), anonId: `anon-${MARK}-3`, client: 'h5/1',
  });
  expect(dup1.status === 200 && dup2.status === 200, 'both duplicate-window submissions answered 200');
  expect(dup1.json && dup2.json && dup1.json.id === dup2.json.id,
    'the same content within the window returns the FIRST comment',
    `${dup1.json && dup1.json.id} vs ${dup2.json && dup2.json.id}`);
  expect(dup2.json && dup2.json.createdAt === dup1.json.createdAt, 'and the first comment\'s createdAt');

  // ---- 13. the live socket --------------------------------------------
  console.log('\n[13] a WebSocket subscriber is pushed every new comment');
  let noAuthSocket = null;
  try {
    noAuthSocket = await openLive(photoA.imageId, null, { timeoutMs: 5000 });
    expect(false, 'the live socket refuses an unauthenticated upgrade', 'the upgrade was accepted');
    noAuthSocket.close();
  } catch (error) {
    expect(/401/.test(error.message) || /unexpected server response/i.test(error.message),
      'the live socket refuses an unauthenticated upgrade (401)',
      error.message);
  }

  let live = null;
  try {
    live = await openLive(photoA.imageId, device, { timeoutMs: 5000 });
    expect(true, `GET /v1/live/{imageId} upgrades with device auth (${photoA.imageId})`);
  } catch (error) {
    expect(false, 'GET /v1/live/{imageId} upgrades with device auth', error.message);
  }
  if (live) {
    const pushed = await new Promise(async (resolve) => {
      const waiter = awaitMessage(live, (m) => m.t === 'comment' && m.content === `${MARK} \u76f4\u64ad`, 10000)
        .then((m) => resolve({ message: m }))
        .catch((e) => resolve({ error: e.message }));
      void waiter;
      await postComment({
        code: photoA.code,
        content: `${MARK} \u76f4\u64ad`,
        nickname: '\u76f4\u64ad\u5ba2',
        idemKey: crypto.randomUUID(),
        anonId: `anon-${MARK}-4`,
        client: 'h5/1',
      });
    });
    if (pushed.message) {
      const m = pushed.message;
      expect(m.id && m.nickname === '\u76f4\u64ad\u5ba2' && m.content === `${MARK} \u76f4\u64ad` && m.status === 1,
        `a comment posted after connecting arrives as {"t":"comment",...} with the right content (id ${m.id})`,
        JSON.stringify(m));
      expect(isMillisecond(m.createdAt), 'the push carries createdAt in milliseconds');
    } else {
      expect(false, 'a comment posted after connecting is pushed to the live socket', pushed.error);
    }
    live.close();
  }

  // ---- 13b. the keepalive ----------------------------------------------
  // The real server's interval is 30 seconds and the protocol only says "at least
  // every 30s", so waiting for one is not something a contract test should do.
  // The mock's interval is settable, so the check runs there and is reported as a
  // skip against anything else — a skipped check that nobody sees is a check that
  // was quietly deleted.
  if (IS_MOCK) {
    console.log('\n[13b] the live keepalive (mock only, shortened interval)');
    await call('GET', '/__control/ping-ms?ms=150');
    try {
      const quick = await openLive(photoA.imageId, device, { timeoutMs: 5000 });
      try {
        const message = await awaitMessage(quick, (m) => m.t === 'ping', 5000);
        expect(message.t === 'ping', `a {"t":"ping"} arrives on the keepalive timer (every 150ms here)`);
      } catch (error) {
        expect(false, 'a {"t":"ping"} arrives on the keepalive timer', error.message);
      }
      quick.terminate();
    } catch (error) {
      expect(false, 'the socket could be opened for the keepalive check', error.message);
    }
    await call('GET', '/__control/ping-ms?ms=0');
  } else {
    note('skip (real server): the keepalive interval is 30s and is not configurable there, so waiting for a ping would make this test slower than the thing it checks');
  }

  // ---- 14. reports -----------------------------------------------------
  console.log('\n[14] the third report takes a visible comment down');
  const reportTarget = await postComment({
    code: photoA.code,
    content: `${MARK} \u4e3e\u62a5\u76ee\u6807`,
    nickname: '\u4e3e\u62a5\u6d4b\u8bd5',
    idemKey: crypto.randomUUID(),
    anonId: `anon-${MARK}-5`,
    client: 'h5/1',
  });
  expect(reportTarget.status === 200 && reportTarget.json && reportTarget.json.status === 1,
    'the comment to be reported starts visible');
  const reportId = reportTarget.json && reportTarget.json.id;
  const reportStatuses = [];
  for (let i = 1; i <= 3; i++) {
    const r = await json('POST', `/v1/comments/${reportId}/report`, { headers: jsonHeaders, body: '{}' });
    reportStatuses.push({ i, status: r.status, count: r.json && r.json.reportCount, commentStatus: r.json && r.json.status });
  }
  expect(reportStatuses.every((r) => r.status === 200), 'every report answers 200',
    JSON.stringify(reportStatuses));
  expect(reportStatuses[2] && reportStatuses[2].count === 3 && reportStatuses[2].commentStatus === 0,
    'the third report returns reportCount 3 and status 0',
    JSON.stringify(reportStatuses[2]));
  expect(reportStatuses[0] && reportStatuses[1] && reportStatuses[0].commentStatus === 1 && reportStatuses[1].commentStatus === 1,
    'the first two reports leave the comment visible',
    JSON.stringify(reportStatuses.slice(0, 2)));
  const afterReport = await json('GET', `/v1/comments?imageId=${photoA.imageId}`, {
    headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token },
  });
  expect(!((afterReport.json && afterReport.json.items) || []).some((c) => c.id === reportId),
    'the reported comment has disappeared from the public list');
  const missingReport = await json('POST', '/v1/comments/01JZZZZZZZZZZZZZZZZZZZZZZZ/report', { headers: jsonHeaders, body: '{}' });
  expect(missingReport.status === 404 && errorCode(missingReport.json) === 'not_found',
    'reporting an unknown comment id is 404 not_found', `status ${missingReport.status}`);

  // ---- 15. the H5 page -------------------------------------------------
  console.log('\n[15] the H5 page is one server-rendered file with nothing external');
  const page = await call('GET', `/c/${photoA.code}`);
  const html = page.body.toString('utf8');
  const markup = stripComments(html);
  expect(page.status === 200 && String(page.headers.get('content-type')).startsWith('text/html'),
    'GET /c/{code} is text/html', `status ${page.status} type ${page.headers.get('content-type')}`);
  expect(html.includes(cleanContent), 'the published comment is on the page');
  expect(html.includes('\u5c0f\u738b'), 'the commenter\'s nickname is on the page');
  expect(html.includes('\u521a\u521a') || /分钟前|小时前|\u6708\d+\u65e5/.test(html),
    'a server-computed relative time is on the page');
  expect(!html.includes(`/c/${photoA.code}/report/`),
    'the page no longer offers a report link (§17.11: the endpoint stays, the button was asked to go)');
  expect(html.includes('\u63d0\u4ea4'), 'the form has a 提交 button');
  expect(html.indexOf('name="nickname"') < html.indexOf('<ul class="comments"') ||
    !html.includes('<ul class="comments"'),
    'the form comes before the comment list, so the box to type in is not below the fold');
  expect(/name="nickname"[^>]*maxlength="12"/.test(html) || /maxlength="12"[^>]*name="nickname"/.test(html),
    'the nickname field is capped at 12 characters');
  expect(/<textarea[^>]*name="content"[^>]*maxlength="20"/.test(html) || /<textarea[^>]*maxlength="20"[^>]*name="content"/.test(html),
    'the content field is a textarea capped at the 20 characters a television can show');
  expect(/<input[^>]*type="hidden"[^>]*name="idem"/.test(html) || /name="idem"[^>]*type="hidden"/.test(html),
    'the form carries a hidden idem field');
  expect(!/<script/i.test(markup),
    'the page contains no <script> tag (HTML comments are ignored, since the page documents that rule in a comment)');
  expect(!markup.includes('https://'), 'the page references no https:// resource (mixed content on a LAN)');
  expect(html.includes('/t/'), 'the thumbnail <img> points at a signed /t/ URL');
  const setCookie = String(page.headers.get('set-cookie') || '');
  expect(setCookie.includes('tuping_anon='), `a first visit sets the tuping_anon cookie (${setCookie.split(';')[0]})`);
  expect(/HttpOnly/i.test(setCookie), 'the cookie is HttpOnly');
  expect(/Path=\//i.test(setCookie), 'the cookie is scoped to /');
  expect(/Max-Age=31536000/i.test(setCookie), 'the cookie lasts a year');

  // ---- 16. the form, and the PRG redirect ------------------------------
  console.log('\n[16] a form POST answers 303 and the comment is on the page it points at');
  const formContent = `${MARK} \u8868\u5355\u63d0\u4ea4`;
  const form = await call('POST', `/c/${photoA.code}`, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    rawBody: Buffer.from(new URLSearchParams({
      nickname: '\u8868\u5355\u5ba2',
      content: formContent,
      idem: crypto.randomUUID(),
    }).toString(), 'utf8'),
  });
  expect(form.status === 303, `POST /c/{code} answers 303 (got ${form.status})`);
  const location = String(form.headers.get('location') || '');
  expect(location.includes('ok=1') && location.includes('mine='),
    `the redirect carries ok=1 and mine=<id> (${location})`);
  const followed = await call('GET', location.startsWith('http') ? asLocal(location) : location);
  const followedHtml = followed.body.toString('utf8');
  expect(followed.status === 200 && followedHtml.includes(formContent),
    'following the redirect shows the comment that was just written');
  expect(followedHtml.includes('mine'), 'and marks it as the visitor\'s own');

  // ---- 17. a refused form keeps the user's words ----------------------
  console.log('\n[17] a refused submission still contains what the user typed');
  const rejectedText = `\u88ab\u62d2\u7edd\u7684\u5185\u5bb9-${MARK}-http://spam.example/kept`;
  const rejectedForm = await call('POST', `/c/${photoA.code}`, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    rawBody: Buffer.from(new URLSearchParams({
      nickname: '\u4e0d\u653e\u5f03',
      content: rejectedText,
      idem: crypto.randomUUID(),
    }).toString(), 'utf8'),
  });
  const rejectedHtml = rejectedForm.body.toString('utf8');
  expect(rejectedForm.status === 400, `a rejected form submission answers 400 (got ${rejectedForm.status})`);
  expect(rejectedHtml.includes(rejectedText),
    'the page it returns still contains the text the user typed (the §10.1 "keep the user\'s words" rule)');
  expect(rejectedHtml.includes('\u4e0d\u653e\u5f03'), 'and the nickname they typed');
  expect(/class="notice bad"/.test(rejectedHtml) || rejectedHtml.includes('不能发广告'),
    'with the reason shown above the input');
  expect(rejectedHtml.includes('\u63d0\u4ea4'), 'and the form is still there to try again');

  const heldForm = await call('POST', `/c/${photoA.code}`, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    rawBody: Buffer.from(new URLSearchParams({
      nickname: '\u5f85\u5ba1',
      content: '\u957f'.repeat(120),
      idem: crypto.randomUUID(),
    }).toString(), 'utf8'),
  });
  expect(heldForm.status === 400, 'an over-long form submission is refused rather than silently truncated');

  // A thumbnail that is not a JPEG at all, and one that is a JPEG the decoder the
  // real server uses cannot read. Both have to be refused: a mock that accepted
  // them would let a client ship a bug that only shows up on a TV.
  const notJpeg = await registerPhoto(device, {
    thumb: Buffer.from('this is not an image at all, it is a sentence'),
    meta: { localId: `${MARK}-notjpeg`, source: 'fnos', sha256Original: null, width: 320, height: 240 },
  });
  expect(notJpeg.status === 400 && errorCode(notJpeg.json) === 'bad_request',
    'a thumbnail that is not a JPEG is 400 bad_request', `status ${notJpeg.status}`);

  if (IS_MOCK) {
    // Grayscale is legal JPEG and unreadable by Go's decoder, so the real server
    // answers 400 and verify.js never sends one. The mock has to agree, or it
    // would hide exactly that failure — which it did until this check was added.
    const grayscale = Buffer.from([0xff, 0xd8, 0xff, 0xc0, 0x00, 0x0b, 8, 0, 16, 0, 16, 1, 1, 0x11, 0, 0xff, 0xd9]);
    const grayResult = await registerPhoto(device, {
      thumb: grayscale,
      meta: { localId: `${MARK}-gray`, source: 'fnos', sha256Original: null, width: 16, height: 16 },
    });
    expect(grayResult.status === 400 && errorCode(grayResult.json) === 'bad_request',
      'the mock refuses a one-component JPEG, as the real decoder does', `status ${grayResult.status}`);

    // The rate-limit control, and the 429 path it exists to make reachable.
    await call('GET', `/__control/rate-limit?key=${encodeURIComponent(`cmt:anon:shrink-${MARK}:min`)}&limit=1&windowMs=60000`);
    const firstAllowed = await postComment({
      code: photoA.code, content: `${MARK} \u9650\u6d41\u4e00`, nickname: 'lim', idemKey: crypto.randomUUID(),
      anonId: `shrink-${MARK}`, client: 'h5/1',
    });
    const secondRefused = await postComment({
      code: photoA.code, content: `${MARK} \u9650\u6d41\u4e8c`, nickname: 'lim', idemKey: crypto.randomUUID(),
      anonId: `shrink-${MARK}`, client: 'h5/1',
    });
    expect(firstAllowed.status === 200 && secondRefused.status === 429 && errorCode(secondRefused.json) === 'rate_limited',
      'a shrunk budget makes the next submission a 429 rate_limited',
      `first ${firstAllowed.status}, second ${secondRefused.status}`);
    const statsAfter = await json('GET', '/__control/stats');
    expect(statsAfter.json && statsAfter.json.rateLimited >= 1 && typeof statsAfter.json.lastBadReason === 'string',
      'the counters report the refusal, and lastBadReason names a reason',
      JSON.stringify(statsAfter.json && { rateLimited: statsAfter.json.rateLimited, lastBadReason: statsAfter.json.lastBadReason }));
    await call('GET', '/__control/reset');
  } else {
    note('skip (real server): the two checks above use /__control, which only the mock has');
  }

  // ---- 17b. the admin door on the page (§17.15) ------------------------
  //
  // The door is the same on both servers and the checks say so: a guest's page has no
  // controls, an admin URL without a session is the login form, the token is the
  // password, and a session is what lets one comment be rewritten or taken down. The
  // session is exercised as a browser would use it — the cookie the login handed back,
  // sent on the next request — because a header this test invented would prove nothing.
  console.log('\n[17b] the admin door: a login, an edit and a delete');
  const formHeaders = { 'Content-Type': 'application/x-www-form-urlencoded' };
  const adminComment = await postComment({
    code: photoA.code,
    content: `${MARK} \u539f\u8bdd`,
    nickname: '\u5c0f\u738b',
    idemKey: crypto.randomUUID(),
    anonId: `anon-${MARK}-admin`,
    client: 'h5/1',
  });
  expect(adminComment.status === 200 && adminComment.json && adminComment.json.status === 1,
    'a comment to administer exists', `status ${adminComment.status}`);
  const adminCommentId = adminComment.json && adminComment.json.id;

  const guestPage = await call('GET', `/c/${photoA.code}`);
  const guestHtml = guestPage.body.toString('utf8');
  expect(!guestHtml.includes(`/c/${photoA.code}/edit/`) && !guestHtml.includes(`/c/${photoA.code}/delete/`),
    'a guest page carries no 编辑 / 删除 controls');
  expect(guestHtml.includes('/admin?next='), 'and it does offer the way in (管理员登录)');

  // Without a session an admin URL is the login form with its way back, not a 401: a
  // 401 in a browser is a dead end with nothing to press.
  const noSessionEdit = await call('GET', `/c/${photoA.code}/edit/${adminCommentId}`);
  expect(noSessionEdit.status === 303 && String(noSessionEdit.headers.get('location')).startsWith('/admin?next='),
    `an admin URL without a session answers 303 to the login (got ${noSessionEdit.status} ${noSessionEdit.headers.get('location')})`);
  const noSessionDelete = await call('POST', `/c/${photoA.code}/delete/${adminCommentId}`, {
    headers: formHeaders, rawBody: Buffer.from(''),
  });
  expect(noSessionDelete.status === 303, `and so does a delete (got ${noSessionDelete.status})`);

  const wrongLogin = await call('POST', '/admin', {
    headers: formHeaders,
    rawBody: Buffer.from(new URLSearchParams({ token: `${ADMIN_TOKEN}-nope` }).toString(), 'utf8'),
  });
  expect(wrongLogin.status === 401, `a wrong token answers 401 (got ${wrongLogin.status})`);
  expect(!String(wrongLogin.headers.get('set-cookie') || '').includes('tuping_admin='),
    'and hands out no session cookie with it');
  expect(wrongLogin.body.toString('utf8').includes('\u4ee4\u724c\u4e0d\u5bf9'), 'and the page it returns says 令牌不对');

  const login = await call('POST', '/admin', {
    headers: formHeaders,
    rawBody: Buffer.from(new URLSearchParams({ token: ADMIN_TOKEN, next: `/c/${photoA.code}` }).toString(), 'utf8'),
  });
  const adminSetCookie = String(login.headers.get('set-cookie') || '');
  expect(login.status === 303 && String(login.headers.get('location')) === `/c/${photoA.code}`,
    `a good login answers 303 back where it was aimed (got ${login.status} ${login.headers.get('location')})`);
  expect(/tuping_admin=[^;]+/.test(adminSetCookie), `and sets the admin cookie (${adminSetCookie.split(';')[0].slice(0, 30)}…)`);
  expect(/HttpOnly/i.test(adminSetCookie) && /SameSite=Lax/i.test(adminSetCookie),
    'the session cookie is HttpOnly and SameSite=Lax (the second is what makes a cross-site POST unable to delete anything)');
  const adminCookie = adminSetCookie.split(';')[0];
  const asAdmin = { Cookie: adminCookie };

  const adminHome = await call('GET', '/admin', { headers: asAdmin });
  expect(adminHome.status === 200 && adminHome.body.toString('utf8').includes('\u5df2\u7ecf\u767b\u5f55'),
    'GET /admin with the session says 已经登录 instead of asking again');

  const adminPage = await call('GET', `/c/${photoA.code}`, { headers: asAdmin });
  const adminHtml = adminPage.body.toString('utf8');
  expect(adminHtml.includes('\u7ba1\u7406\u5458\u6a21\u5f0f'), 'the admin\'s page says 管理员模式');
  expect(adminHtml.includes(`/c/${photoA.code}/edit/${adminCommentId}`), 'and offers 编辑 for the comment');
  expect(adminHtml.includes(`/c/${photoA.code}/delete/${adminCommentId}`), 'and 删除 for it');
  expect(!adminHtml.includes('/admin?next='), 'and no longer offers the login link');

  const editForm = await call('GET', `/c/${photoA.code}/edit/${adminCommentId}`, { headers: asAdmin });
  const editHtml = editForm.body.toString('utf8');
  expect(editForm.status === 200 && editHtml.includes(`${MARK} \u539f\u8bdd`),
    'the edit form carries the comment as it stands', `status ${editForm.status}`);

  // A television already showing this photo, subscribed the way the album subscribes. What
  // is checked next is the report §17.16 answers: the socket has to carry a comment's
  // *state*, or an edit leaves the old words on screen and a delete leaves the comment
  // being replayed for as long as the photo is up.
  let stateSocket = null;
  try {
    stateSocket = await openLive(photoA.imageId, device, { timeoutMs: 5000 });
    expect(true, 'a live socket is open for the state checks');
  } catch (error) {
    expect(false, 'a live socket is open for the state checks', error.message);
  }

  const editPush = stateSocket
    ? awaitMessage(stateSocket, (m) => m.t === 'comment' && m.id === adminCommentId, 8000)
      .then((m) => ({ message: m }))
      .catch((e) => ({ error: e.message }))
    : Promise.resolve({ error: 'no socket' });

  const editedContent = `${MARK} \u6539\u8fc7\u7684\u8bdd`;
  const save = await call('POST', `/c/${photoA.code}/edit/${adminCommentId}`, {
    headers: { ...formHeaders, ...asAdmin },
    rawBody: Buffer.from(new URLSearchParams({ nickname: '\u5c0f\u738b', content: editedContent }).toString(), 'utf8'),
  });
  expect(save.status === 303 && String(save.headers.get('location')) === `/c/${photoA.code}?ok=edited&mine=${adminCommentId}`,
    `saving answers 303 with ok=edited (got ${save.status} ${save.headers.get('location')})`);
  const savedPage = await call('GET', `/c/${photoA.code}?ok=edited&mine=${adminCommentId}`, { headers: asAdmin });
  const savedHtml = savedPage.body.toString('utf8');
  expect(savedHtml.includes(editedContent) && !savedHtml.includes(`${MARK} \u539f\u8bdd`),
    'the page shows the new text and not the old one');
  expect(savedHtml.includes('\u5df2\u4fdd\u5b58'), 'and says 已保存');

  const tvAfterEdit = await json('GET', `/v1/comments?imageId=${photoA.imageId}`, {
    headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token },
  });
  const editedRows = ((tvAfterEdit.json && tvAfterEdit.json.items) || []).filter((c) => c.id === adminCommentId);
  expect(editedRows.length === 1 && editedRows[0].content === editedContent,
    'and the danmaku history carries the edited text, not a second copy of the comment',
    JSON.stringify(editedRows));

  const pushedEdit = await editPush;
  expect(pushedEdit.message && pushedEdit.message.content === editedContent && pushedEdit.message.status === 1,
    'an edit is pushed down the live socket, so a television showing this photo stops replaying the old words',
    pushedEdit.error || JSON.stringify(pushedEdit.message));

  // Screened or not is the admin's business — the human queue escalates to them — but the
  // 20-rune ceiling is what a television can draw, so it still applies (§9.5).
  const tooLongEdit = await call('POST', `/c/${photoA.code}/edit/${adminCommentId}`, {
    headers: { ...formHeaders, ...asAdmin },
    rawBody: Buffer.from(new URLSearchParams({ content: '\u5b57'.repeat(25) }).toString(), 'utf8'),
  });
  expect(tooLongEdit.status === 400, `an over-long edit is refused (got ${tooLongEdit.status})`);
  expect(tooLongEdit.body.toString('utf8').includes('\u5b57'.repeat(25)),
    'and the page keeps the text that was typed, as the guest form does');

  const deletePush = stateSocket
    ? awaitMessage(stateSocket, (m) => m.t === 'comment' && m.id === adminCommentId && m.status === 3, 8000)
      .then((m) => ({ message: m }))
      .catch((e) => ({ error: e.message }))
    : Promise.resolve({ error: 'no socket' });

  const deleteReply = await call('POST', `/c/${photoA.code}/delete/${adminCommentId}`, {
    headers: { ...formHeaders, ...asAdmin }, rawBody: Buffer.from(''),
  });
  expect(deleteReply.status === 303 && String(deleteReply.headers.get('location')) === `/c/${photoA.code}?ok=deleted`,
    `deleting answers 303 with ok=deleted (got ${deleteReply.status} ${deleteReply.headers.get('location')})`);
  const afterDelete = await call('GET', `/c/${photoA.code}?ok=deleted`, { headers: asAdmin });
  const afterDeleteHtml = afterDelete.body.toString('utf8');
  expect(!afterDeleteHtml.includes(editedContent), 'the deleted comment is off the page');
  expect(afterDeleteHtml.includes('\u5df2\u5220\u9664'), 'and the page says 已删除');
  const tvAfterDelete = await json('GET', `/v1/comments?imageId=${photoA.imageId}`, {
    headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token },
  });
  expect(!((tvAfterDelete.json && tvAfterDelete.json.items) || []).some((c) => c.id === adminCommentId),
    'and it is gone from the danmaku history the television reads');

  // **The check the reported symptom is about.** A television that is already showing this
  // photo does not re-read the history — `since` is the newest comment it holds — so the
  // only way a deleted comment leaves the screen is this push.
  const pushedDelete = await deletePush;
  expect(pushedDelete.message && pushedDelete.message.status === 3,
    'deleting pushes the comment\'s new state (status 3), which is what takes it off a television already showing the photo',
    pushedDelete.error || JSON.stringify(pushedDelete.message));
  if (stateSocket) stateSocket.close();

  // The expiry travels inside the cookie, so the signature has to cover it. This is what
  // says a caller cannot mint a longer session by editing the number.
  const [sessionExp, sessionMac] = adminCookie.replace('tuping_admin=', '').split('.');
  const forgedPage = await call('GET', `/c/${photoA.code}`, {
    headers: { Cookie: `tuping_admin=${Number(sessionExp) + 86400000}.${sessionMac}` },
  });
  expect(!forgedPage.body.toString('utf8').includes('\u7ba1\u7406\u5458\u6a21\u5f0f'),
    'a session cookie whose expiry was edited is not a session');

  const logout = await call('POST', '/admin/logout', { headers: asAdmin });
  expect(logout.status === 303 && /tuping_admin=;/.test(String(logout.headers.get('set-cookie') || '')),
    `logging out answers 303 and clears the cookie (got ${logout.status} ${logout.headers.get('set-cookie')})`);

  // ---- 18. delete ------------------------------------------------------
  console.log('\n[18] delete needs the owner token and takes everything with it');
  const doomed = await registerPhoto(device, {
    thumb: imageC,
    meta: { localId: `${MARK}-doomed`, source: 'fnos', sha256Original: null, width: 240, height: 320 },
  });
  expect(doomed.status === 200 && doomed.json, 'a photo to delete was registered', `status ${doomed.status}`);
  const doomedId = doomed.json && doomed.json.imageId;
  const doomedCode = doomed.json && doomed.json.code;
  const doomedThumbPath = asLocal(doomed.json && doomed.json.thumbUrl);
  await postComment({
    code: doomedCode, content: `${MARK} \u5f85\u5220\u9664`, nickname: 'x',
    idemKey: crypto.randomUUID(), anonId: `anon-${MARK}-6`, client: 'h5/1',
  });

  const wrongOwner = await call('DELETE', `/v1/photos/${doomedId}`, {
    headers: {
      'X-Device-Id': device.id,
      'X-Device-Token': device.token,
      'X-Owner-Token': 'not-the-owner-token',
    },
  });
  expect(wrongOwner.status === 401 && errorCode(JSON.parse(wrongOwner.body.toString('utf8') || '{}')) === 'unauthorized',
    'DELETE with the wrong owner token is 401 unauthorized', `status ${wrongOwner.status}`);

  const wrongDevice = await call('DELETE', `/v1/photos/${doomedId}`, {
    headers: { 'X-Owner-Token': device.ownerToken },
  });
  expect(wrongDevice.status === 401, 'DELETE without device headers is 401 as well', `status ${wrongDevice.status}`);

  const deleted = await call('DELETE', `/v1/photos/${doomedId}`, {
    headers: {
      'X-Device-Id': device.id,
      'X-Device-Token': device.token,
      'X-Owner-Token': device.ownerToken,
    },
  });
  const deletedJson = JSON.parse(deleted.body.toString('utf8') || '{}');
  expect(deleted.status === 200 && deletedJson.deleted === doomedId,
    'DELETE with the right owner token is 200 and names what went', `status ${deleted.status} body ${JSON.stringify(deletedJson)}`);
  expect(Array.isArray(deletedJson.group) && deletedJson.group.includes(doomedId),
    `the response carries the group it removed (${JSON.stringify(deletedJson.group)})`);

  const gonePhoto = await json('GET', `/v1/photos/${doomedCode}`);
  expect(gonePhoto.status === 404 && errorCode(gonePhoto.json) === 'not_found',
    'after the delete, GET /v1/photos/{code} is 404', `status ${gonePhoto.status}`);
  const goneQr = await call('GET', `/v1/qr/${doomedCode}.png`);
  expect(goneQr.status === 404, 'and the QR URL is 404 too', `status ${goneQr.status}`);
  const goneThumb = await call('GET', doomedThumbPath);
  // 401 or 404 are both honest answers here: the code no longer resolves, and the
  // signature's own validity is a separate question the server may answer first.
  expect(goneThumb.status === 404 || goneThumb.status === 401,
    `and the signed thumbnail URL is gone (got ${goneThumb.status}; 404 and 401 are both acceptable, because either "this code is gone" or "this signature is no longer honoured" is a true answer)`,
    `status ${goneThumb.status}`);
  expect(goneThumb.status !== 200, 'and it certainly no longer serves image bytes');

  // ---- 19. unknown codes ----------------------------------------------
  console.log('\n[19] an unknown code is a 404 on every public door');
  const unknown = 'zzz000';
  const unknownPhoto = await json('GET', `/v1/photos/${unknown}`);
  expect(unknownPhoto.status === 404 && errorCode(unknownPhoto.json) === 'not_found',
    'GET /v1/photos/{unknown} is 404 not_found', `status ${unknownPhoto.status}`);
  const unknownQr = await call('GET', `/v1/qr/${unknown}.png`);
  expect(unknownQr.status === 404, 'GET /v1/qr/{unknown}.png is 404', `status ${unknownQr.status}`);
  const unknownPage = await call('GET', `/c/${unknown}`);
  expect(unknownPage.status === 404, 'GET /c/{unknown} is 404', `status ${unknownPage.status}`);
  const malformed = await json('GET', '/v1/photos/abc');
  expect(malformed.status === 404, 'a malformed code (too short) is 404 as well', `status ${malformed.status}`);

  // ---- 20. by-local: the read that lets the album end ask before uploading ---
  //
  // §17.28: a television shows a photo's code and its comments without uploading anything,
  // and the question it asks is this one. Two properties matter and both are asserted: the
  // answer is the identity *this device* registered, and a miss creates nothing — a lookup
  // that quietly made a row would undo the whole point of the change.
  console.log('\n[20] /v1/photos/by-local answers what this device registered, and creates nothing');
  const byLocalOptions = { headers: { 'X-Device-Id': device.id, 'X-Device-Token': device.token } };
  const photosBefore = IS_MOCK ? (await json('GET', '/__control/stats')).json.photos : null;

  const byLocal = await json('GET', `/v1/photos/by-local?source=fnos&localId=${encodeURIComponent(`${MARK}-A`)}`, byLocalOptions);
  expect(byLocal.status === 200 && byLocal.json && byLocal.json.imageId === photoA.imageId,
    'the photo this device registered comes back with the same imageId',
    `status ${byLocal.status} ${JSON.stringify(byLocal.json)}`);
  expect(byLocal.json.code === photoA.code,
    'and with the same code, so the picture behind the QR URL is the one on the wall');
  expect(typeof byLocal.json.qrUrl === 'string' && byLocal.json.qrUrl.includes(`/v1/qr/${photoA.code}.png`),
    'and with a qrUrl pointing at that code');

  const neverOpened = await json('GET', `/v1/photos/by-local?source=fnos&localId=${MARK}-never-opened`, byLocalOptions);
  expect(neverOpened.status === 404 && errorCode(neverOpened.json) === 'not_found',
    'a localId this device never registered is 404 not_found — the answer most of a library gives',
    `status ${neverOpened.status} ${JSON.stringify(neverOpened.json)}`);
  const otherSource = await json('GET', `/v1/photos/by-local?source=fnos-album&localId=${encodeURIComponent(`${MARK}-A`)}`, byLocalOptions);
  expect(otherSource.status === 404,
    'and the source is part of the key: the same localId under another source is not this photo',
    `status ${otherSource.status}`);
  const noToken = await json('GET', `/v1/photos/by-local?source=fnos&localId=${encodeURIComponent(`${MARK}-A`)}`);
  expect(noToken.status === 401, 'the question needs a device token at all', `status ${noToken.status}`);
  const halfKey = await json('GET', '/v1/photos/by-local?source=fnos', byLocalOptions);
  expect(halfKey.status === 400, 'a source without a localId is 400, not a 404', `status ${halfKey.status}`);

  if (IS_MOCK) {
    const photosAfter = (await json('GET', '/__control/stats')).json.photos;
    expect(photosAfter === photosBefore,
      `and none of those questions created a photo (${photosBefore} -> ${photosAfter})`);
  }

  // ---- mock-only state -------------------------------------------------
  if (IS_MOCK) {
    console.log('\n[21] the mock\'s own counters, for the record');
    const stats = await json('GET', '/__control/stats');
    expect(stats.status === 200 && stats.json && stats.json.requests > 0,
      `counters are readable (requests=${stats.json && stats.json.requests}, comments=${stats.json && stats.json.comments}, photos=${stats.json && stats.json.photos}, signBad=${stats.json && stats.json.signBad})`);
    const newest = await json('GET', `/__control/comment?imageId=${photoA.imageId}&content=${encodeURIComponent(cleanContent)}`);
    expect(newest.json && newest.json.found && newest.json.item.content === cleanContent,
      'the server side confirms the published comment, not just the client\'s view');
  } else {
    note('skip (real server): /__control counters do not exist there');
  }

  console.log(`\n  ${checks} checks run against ${BASE}`);
  console.log(failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((error) => {
  console.error(`\ncontract test crashed: ${error.stack || error.message}`);
  process.exit(1);
});
