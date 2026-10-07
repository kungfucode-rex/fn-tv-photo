'use strict';

/*
 * Downloads the mock NAS's test photos.
 *
 * The mock used to paint a gradient per photo id, which is fine for asserting that a
 * thumbnail arrived and useless the moment a screenshot is meant to be shown to
 * anyone. These are real photographs instead, so the emulator screenshots used in
 * README.md and in the forum post look like a photo library.
 *
 *   node fetch-photos.js                    # fill in whatever is missing
 *   node fetch-photos.js --count 60 --force # re-download everything
 *   node fetch-photos.js --list-only        # just print what the catalogue holds
 *
 * Source: Lorem Picsum (https://picsum.photos), which serves Unsplash photographs.
 * Licence: the Unsplash Licence — free to use, commercial or not, no permission and
 * no attribution required (crediting the photographer is still good manners, so
 * `CREDITS.md` is written next to the files). See CREDITS.md for per-photo sources.
 *
 * Every rendition is fetched from Picsum at the exact pixel size the mock advertises
 * in SIZE_MAP, so nothing here needs an image library: Picsum does the cropping and
 * the scaling, and we keep the bytes a real fnOS server would send for that size.
 */

const https = require('https');
const fs = require('fs');
const path = require('path');

const PHOTO_DIR = path.join(__dirname, 'assets', 'photos');
const MANIFEST = path.join(PHOTO_DIR, 'manifest.json');
const CREDITS = path.join(PHOTO_DIR, 'CREDITS.md');

// Mirror of SIZE_MAP in server.js. `o` is the original the viewer opens; the rest are
// the server-side thumbnails a grid asks for.
const SIZES = {
  xxs: [107, 60],
  xs: [320, 180],
  s: [427, 240],
  m: [960, 540],
  o: [1280, 720],
};

// Square crop for the AI face-cluster avatars in the 人物 section.
const FACE = [240, 240];

// Landscape-or-better originals only: these land in a 16:9 grid, and a portrait
// photo cropped to 16:9 throws away most of what the photographer framed.
const MIN_ASPECT = 1.3;
const MIN_WIDTH = 1600;

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[i + 1] : fallback;
}
const flag = (name) => process.argv.includes(`--${name}`);

const COUNT = Number(arg('count', 60));
const CONCURRENCY = Number(arg('concurrency', 6));
const FORCE = flag('force');

function get(url, { binary = false, redirects = 5 } = {}) {
  return new Promise((resolve, reject) => {
    const req = https.get(
      url,
      { headers: { 'User-Agent': 'tvphoto-mock-testdata/1.0 (+https://picsum.photos)' } },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
          res.resume();
          return resolve(get(new URL(res.headers.location, url).toString(), { binary, redirects: redirects - 1 }));
        }
        if (res.statusCode !== 200) {
          res.resume();
          return reject(new Error(`HTTP ${res.statusCode} for ${url}`));
        }
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          const buf = Buffer.concat(chunks);
          resolve(binary ? buf : buf.toString('utf8'));
        });
      },
    );
    req.on('error', reject);
    req.setTimeout(45000, () => req.destroy(new Error(`timeout for ${url}`)));
  });
}

async function retry(label, fn, attempts = 4) {
  let last;
  for (let i = 1; i <= attempts; i++) {
    try {
      return await fn();
    } catch (e) {
      last = e;
      if (i < attempts) await new Promise((r) => setTimeout(r, 400 * i));
    }
  }
  throw new Error(`${label}: ${last.message}`);
}

/** The whole Picsum catalogue, in catalogue order, so the choice is reproducible. */
async function catalogue() {
  const all = [];
  for (let page = 1; page <= 12; page++) {
    const body = await retry(`list page ${page}`, () =>
      get(`https://picsum.photos/v2/list?page=${page}&limit=100`));
    const page_ = JSON.parse(body);
    if (!page_.length) break;
    all.push(...page_);
  }
  return all;
}

function pick(list, count) {
  const usable = list.filter((p) => p.width / p.height >= MIN_ASPECT && p.width >= MIN_WIDTH);
  if (usable.length <= count) return usable;
  // Even stride rather than the first N: Picsum's catalogue is ordered by upload, and
  // the first sixty entries are one photographer's afternoon.
  const stride = usable.length / count;
  const picked = [];
  for (let i = 0; i < count; i++) picked.push(usable[Math.floor(i * stride)]);
  return picked;
}

async function downloadOne(entry, dir) {
  const jobs = [];
  for (const [size, [w, h]] of Object.entries(SIZES)) {
    const file = path.join(dir, `${size}.jpg`);
    if (!FORCE && fs.existsSync(file) && fs.statSync(file).size > 1024) continue;
    jobs.push(
      retry(`${entry.id}/${size}`, async () => {
        const buf = await get(`https://picsum.photos/id/${entry.id}/${w}/${h}.jpg`, { binary: true });
        if (buf.length < 1024) throw new Error(`suspiciously small (${buf.length} bytes)`);
        fs.writeFileSync(file, buf);
        return buf.length;
      }),
    );
  }
  const faceFile = path.join(dir, 'face.jpg');
  if (FORCE || !fs.existsSync(faceFile) || fs.statSync(faceFile).size < 1024) {
    jobs.push(
      retry(`${entry.id}/face`, async () => {
        const buf = await get(`https://picsum.photos/id/${entry.id}/${FACE[0]}/${FACE[1]}.jpg`, { binary: true });
        if (buf.length < 1024) throw new Error(`suspiciously small (${buf.length} bytes)`);
        fs.writeFileSync(faceFile, buf);
        return buf.length;
      }),
    );
  }
  await Promise.all(jobs);
  // A directory that claims to be complete must actually be complete: a half-filled
  // one is served happily by server.js and shows up as a broken tile in a screenshot.
  const missing = Object.keys(SIZES).filter((s) => !fs.existsSync(path.join(dir, `${s}.jpg`)));
  if (missing.length) throw new Error(`${entry.id}: missing ${missing.join(', ')}`);
  if (!fs.existsSync(faceFile)) throw new Error(`${entry.id}: missing face.jpg`);
}

function writeCredits(entries) {
  const lines = [
    '# Test photos',
    '',
    'Real photographs served by `tools/mock-nas` in place of the old per-id gradient, so the',
    'emulator screenshots in `README.md` and `artifacts/forum/` look like a photo library.',
    '',
    '**Source:** [Lorem Picsum](https://picsum.photos) (photographs from [Unsplash](https://unsplash.com)).',
    '**Licence:** the [Unsplash Licence](https://unsplash.com/license) — free to use for commercial and',
    'non-commercial purposes, no permission or attribution required. Credit is given below anyway.',
    '',
    'Downloaded at the exact pixel sizes the mock advertises (`xxs` 107×60, `xs` 320×180,',
    '`s` 427×240, `m` 960×540, `o` 1280×720, plus a 240×240 square for the face-cluster',
    'avatars), so the bytes on the wire are the ones a real server would send for that size.',
    '',
    '| # | Picsum id | Photographer | Source |',
    '| --- | --- | --- | --- |',
  ];
  entries.forEach((e, i) => lines.push(`| ${i + 1} | ${e.id} | ${e.author} | ${e.url} |`));
  lines.push('', `Regenerate with \`node fetch-photos.js\` (see the header of that script).`, '');
  fs.writeFileSync(CREDITS, lines.join('\n'), 'utf8');
}

(async () => {
  fs.mkdirSync(PHOTO_DIR, { recursive: true });
  const all = await catalogue();
  const chosen = pick(all, COUNT);
  console.log(`catalogue ${all.length}, landscape ${all.filter((p) => p.width / p.height >= MIN_ASPECT && p.width >= MIN_WIDTH).length}, chosen ${chosen.length}`);

  if (flag('list-only')) {
    chosen.forEach((e) => console.log(`${e.id}\t${e.author}\t${e.url}`));
    return;
  }

  let done = 0;
  let failed = 0;
  const queue = chosen.slice();
  const workers = Array.from({ length: CONCURRENCY }, async () => {
    for (;;) {
      const entry = queue.shift();
      if (!entry) return;
      const dir = path.join(PHOTO_DIR, String(entry.id));
      try {
        fs.mkdirSync(dir, { recursive: true });
        await downloadOne(entry, dir);
        done++;
        process.stdout.write(`\r  ${done + failed}/${chosen.length}  id ${entry.id} by ${entry.author}          `);
      } catch (e) {
        failed++;
        fs.rmSync(dir, { recursive: true, force: true });
        console.log(`\n  FAILED id ${entry.id}: ${e.message}`);
      }
    }
  });
  await Promise.all(workers);
  process.stdout.write('\n');

  const kept = chosen.filter((e) => fs.existsSync(path.join(PHOTO_DIR, String(e.id), 'o.jpg')));
  fs.writeFileSync(
    MANIFEST,
    JSON.stringify({ source: 'https://picsum.photos', licence: 'Unsplash Licence', count: kept.length, photos: kept }, null, 2) + '\n',
    'utf8',
  );
  writeCredits(kept);

  const bytes = kept.reduce((sum, e) => {
    const dir = path.join(PHOTO_DIR, String(e.id));
    return sum + fs.readdirSync(dir).reduce((s, f) => s + fs.statSync(path.join(dir, f)).size, 0);
  }, 0);
  console.log(`  ${kept.length} usable photos, ${(bytes / 1048576).toFixed(1)} MB, manifest + CREDITS.md written`);
  if (failed) {
    console.log(`  ${failed} failed — rerun to fill the gaps`);
    process.exitCode = 1;
  }
})();
