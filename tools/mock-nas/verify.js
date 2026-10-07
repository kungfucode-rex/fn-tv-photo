'use strict';

/*
 * Independent reference client for the mock fnOS server.
 *
 * It implements the documented protocol from scratch so the mock's signature rule
 * can be shown to be satisfiable, and so the Kotlin client has something to be
 * compared against. Run it before trusting any conclusion drawn from the mock:
 *
 *   node verify.js
 *   node verify.js --mode framed     # login with a numeric `si` to prove the trap
 */

const crypto = require('crypto');
const http = require('http');
const WebSocket = require('ws');

const HOST = process.env.MOCK_HOST || '127.0.0.1';
const PORT = Number(process.env.MOCK_PORT || 5666);
const TLS_PORT = Number(process.env.MOCK_TLS_PORT || 5667);
const SALT = 'NDzZTVxnRKP8Z0jXg1VAMonaG8akvh';
const SECRET = 'EAECCF25-80A6-4666-A7C2-A76904A74AB6';

const md5 = (s) => crypto.createHash('md5').update(s, 'utf8').digest('hex');
const ok = (m) => console.log(`  \u2713 ${m}`);
const bad = (m) => console.log(`  \u2717 ${m}`);

function login({ numericSi = false, backId = '0000000000000000', tls = false } = {}) {
  return new Promise((resolve, reject) => {
    // The app is HTTPS only, so the login handshake has to work over the same TLS
    // listener the app uses — a mock that only upgrades cleartext would pass here and
    // fail on a real TV. The certificate is self-signed, exactly like a real NAS.
    const ws = new WebSocket(
      `${tls ? 'wss' : 'ws'}://${HOST}:${tls ? TLS_PORT : PORT}/websocket?type=main`,
      tls ? { rejectUnauthorized: false } : undefined,
    );
    let stage = 'pub';
    const timer = setTimeout(() => reject(new Error('login timed out')), 15000);

    ws.on('open', () => ws.send(JSON.stringify({
      req: 'util.crypto.getRSAPub',
      reqid: `6ac3936e${backId}0001`,
    })));

    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.res === 'pong') return;

      if (msg.pub) {
        const aesKey = crypto.randomBytes(48).toString('base64url').slice(0, 32);
        const iv = crypto.randomBytes(16);
        const payload = JSON.stringify({
          req: 'user.login',
          reqid: 'r2',
          // Echoing `si` verbatim is the whole point of the string check.
          si: numericSi ? Number(msg.si) : msg.si,
          user: 'verifier',
          password: 'secret',
          stay: true,
          deviceType: 'AndroidTV',
          deviceName: 'verify.js',
          did: crypto.randomBytes(16).toString('hex').slice(0, 24),
        });

        const cipher = crypto.createCipheriv('aes-256-cbc', Buffer.from(aesKey, 'utf8'), iv);
        const aes = Buffer.concat([cipher.update(payload, 'utf8'), cipher.final()]).toString('base64');
        const rsa = crypto
          .publicEncrypt({ key: msg.pub, padding: crypto.constants.RSA_PKCS1_PADDING }, Buffer.from(aesKey, 'utf8'))
          .toString('base64');

        stage = 'login';
        return ws.send(JSON.stringify({ req: 'encrypted', iv: iv.toString('base64'), rsa, aes }));
      }

      if (msg.result === 'succ' && msg.token) {
        clearTimeout(timer);
        resolve({ token: msg.token, socket: ws, stage });
      } else if (msg.errno) {
        clearTimeout(timer);
        reject(new Error(`errno ${msg.errno}: ${msg.result}`));
      }
    });

    ws.on('error', (e) => {
      clearTimeout(timer);
      reject(e);
    });
  });
}

function signedGet(token, pathname, params, mode) {
  const entries = Object.entries(params).sort((a, b) => (a[0] < b[0] ? -1 : 1));
  const raw = entries.map(([k, v]) => `${k}=${v}`).join('&');
  const search = entries.map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v).replace(/%20/g, '+')}`).join('&');
  const payload = mode === 'encoded' ? search : raw;

  const nonce = String(100000 + Math.floor(Math.random() * 900000));
  const timestamp = String(Date.now());
  const sign = md5([SALT, pathname, nonce, timestamp, md5(payload), SECRET].join('_'));

  return new Promise((resolve, reject) => {
    const url = `http://${HOST}:${PORT}${pathname}${search ? `?${search}` : ''}`;
    const req = http.get(
      url,
      { headers: { AccessToken: token, authx: `nonce=${nonce}&timestamp=${timestamp}&sign=${sign}` } },
      (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () => {
          try {
            resolve(JSON.parse(body));
          } catch {
            reject(new Error(`unparsable response: ${body.slice(0, 120)}`));
          }
        });
      },
    );
    req.on('error', reject);
  });
}

function control(path) {
  return new Promise((resolve, reject) => {
    http.get(`http://${HOST}:${PORT}${path}`, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => resolve(JSON.parse(body)));
    }).on('error', reject);
  });
}

(async () => {
  const framed = process.argv.includes('--mode');
  let failures = 0;

  console.log(`\n== mock fnOS verification (${HOST}:${PORT}) ==\n`);

  // 1. The documented `si` trap: a numeric si must be rejected.
  if (!framed) {
    try {
      await login({ numericSi: true });
      bad('numeric si was ACCEPTED (the mock is not enforcing the string type)');
      failures++;
    } catch (e) {
      ok(`numeric si rejected as expected -> ${e.message}`);
    }
  }

  // 2. A handshake that still carries a previous session's id must be rejected.
  //    This models the suspected server rule behind "errno 401 on re-login", so the
  //    client's FnReqId.reset() has something to fail against.
  try {
    await login({ backId: '00000000000000ab' });
    bad('stale backId was ACCEPTED (the mock is not enforcing session freshness)');
    failures++;
  } catch (e) {
    ok(`stale backId rejected as expected -> ${e.message}`);
  }

  // 3. Happy path.
  let session;
  try {
    session = await login();
    ok(`login succeeded, token=${session.token}`);
  } catch (e) {
    bad(`login failed: ${e.message}`);
    process.exit(1);
  }

  // 3. Signature rules. A parameterless request hashes the empty string under both
  //    rules, so strictness can only be observed on a request that carries values
  //    needing escaping.
  const probe = {
    start_time: '2026:10:05 00:00:00',
    end_time: '2026:10:05 23:59:59',
    limit: '60',
    offset: '0',
    mode: 'index',
  };

  for (const mode of ['raw', 'encoded']) {
    await control(`/__control/mode?value=${mode}`);
    const good = await signedGet(session.token, '/p/api/v1/gallery/getList', probe, mode);
    if (good.code === 0) {
      ok(`mode=${mode}: matching rule accepted (${good.data.list.length} items)`);
    } else {
      bad(`mode=${mode}: matching rule REJECTED -> ${JSON.stringify(good).slice(0, 160)}`);
      failures++;
    }

    const wrong = mode === 'raw' ? 'encoded' : 'raw';
    const mismatched = await signedGet(session.token, '/p/api/v1/gallery/getList', probe, wrong);
    if (mismatched.code === 5000) {
      ok(`mode=${mode}: non-matching rule rejected with 5000 (server is strict)`);
    } else {
      bad(`mode=${mode}: non-matching rule was accepted -> ${JSON.stringify(mismatched).slice(0, 160)}`);
      failures++;
    }
  }

  // 4. Same request again in the default rule, to leave the server in a known state.
  await control('/__control/mode?value=raw');
  const day = await signedGet(session.token, '/p/api/v1/gallery/getList', probe, 'raw');
  if (day.code === 0) {
    ok(`getList with colon/space timestamps accepted (${day.data.list.length} items)`);
  } else {
    bad(`getList rejected -> ${JSON.stringify(day).slice(0, 160)}`);
    failures++;
  }

  // 5. Media needs only the token.
  const media = await new Promise((resolve) => {
    http
      .get(`http://${HOST}:${PORT}/p/api/v1/stream/p/t/1000/s/${'0'.repeat(8)}-mock-4000-8000-000000000000`, {
        headers: { AccessToken: session.token },
      }, (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve({ status: res.statusCode, type: res.headers['content-type'], size: Buffer.concat(chunks).length }));
      });
  });
  if (media.status === 200 && String(media.type).startsWith('image/')) {
    ok(`thumbnail served without a signature (${media.type}, ${media.size} bytes)`);
  } else {
    bad(`thumbnail failed -> ${JSON.stringify(media)}`);
    failures++;
  }

  // 6. Person gallery. The list endpoint only accepts a single day and needs all
  //    eight parameters, including album_id and the camelCase range duplicates.
  //    A wide range is exactly what returned HTTP 400 in the field.
  const personTimeline = await signedGet(
    session.token,
    '/p/api/v1/ai-person/photoLibrary/timeLine',
    { id: '1' },
    'raw',
  );
  if (personTimeline.code === 0 && personTimeline.data.list.length > 0) {
    ok(`person timeline returned ${personTimeline.data.list.length} days`);
  } else {
    bad(`person timeline failed -> ${JSON.stringify(personTimeline).slice(0, 140)}`);
    failures++;
  }

  const firstDay = personTimeline.data && personTimeline.data.list[0];
  const ymd = firstDay ? `${firstDay.year}:${String(firstDay.month).padStart(2, '0')}:${String(firstDay.day).padStart(2, '0')}` : null;

  if (ymd) {
    const dayScoped = await signedGet(session.token, '/p/api/v1/ai-person/photoLibrary/list', {
      album_id: '1',
      endTime: `${ymd} 23:59:59`,
      end_time: `${ymd} 23:59:59`,
      limit: '40',
      offset: '0',
      personId: '1',
      startTime: `${ymd} 00:00:00`,
      start_time: `${ymd} 00:00:00`,
    }, 'raw');
    if (dayScoped.code === 0) {
      ok(`day-scoped person photos accepted (${dayScoped.data.list.length} items on ${ymd})`);
    } else {
      bad(`day-scoped person photos rejected -> ${JSON.stringify(dayScoped).slice(0, 140)}`);
      failures++;
    }

    const wide = await signedGet(session.token, '/p/api/v1/ai-person/photoLibrary/list', {
      album_id: '1',
      endTime: '2099:12:31 23:59:59',
      end_time: '2099:12:31 23:59:59',
      limit: '40',
      offset: '0',
      personId: '1',
      startTime: '1970:01:01 00:00:00',
      start_time: '1970:01:01 00:00:00',
    }, 'raw');
    if (wide.code === 400) {
      ok('wide-range person query rejected with 400, as the real server does');
    } else {
      bad(`wide-range person query was ACCEPTED -> ${JSON.stringify(wide).slice(0, 140)}`);
      failures++;
    }

    const noAlbum = await signedGet(session.token, '/p/api/v1/ai-person/photoLibrary/list', {
      endTime: `${ymd} 23:59:59`,
      end_time: `${ymd} 23:59:59`,
      limit: '40',
      offset: '0',
      personId: '1',
      startTime: `${ymd} 00:00:00`,
      start_time: `${ymd} 00:00:00`,
    }, 'raw');
    if (noAlbum.code === 400) {
      ok('missing album_id rejected with 400');
    } else {
      bad(`missing album_id was accepted -> ${JSON.stringify(noAlbum).slice(0, 140)}`);
      failures++;
    }
  }

  // 7. Albums shared with this account come from a route of their own, not from
  //    `album/list`. Two things have to hold for that to mean anything: every entry
  //    names its owner, and the two lists are disjoint. Pointing this endpoint at
  //    `albums` would otherwise still look like a pass.
  const mine = await signedGet(session.token, '/p/api/v1/album/list', {}, 'raw');
  const toMe = await signedGet(session.token, '/p/api/v1/album_grant/list_to_me', {
    offset: '0',
    limit: '300',
    sort_by: 'share_mod_time',
    sort_direction: 'desc',
  }, 'raw');

  const shared = toMe.data?.list || [];
  if (toMe.code === 0 && shared.length > 0) {
    ok(`album_grant/list_to_me returned ${shared.length} shared album(s)`);
  } else {
    bad(`album_grant/list_to_me failed -> ${JSON.stringify(toMe).slice(0, 140)}`);
    failures++;
  }

  const unnamed = shared.filter((a) => !a.ownerName);
  if (shared.length > 0 && unnamed.length === 0) {
    ok('every shared album names its owner (ownerName is what sets them apart)');
  } else {
    bad(`${unnamed.length} shared album(s) carry no ownerName`);
    failures++;
  }

  const mineIds = new Set((mine.data?.list || []).map((a) => a.albumId));
  const overlap = shared.filter((a) => mineIds.has(a.albumId));
  if (overlap.length === 0) {
    ok('the shared list is disjoint from album/list (a separate route, not a filter)');
  } else {
    bad(`shared albums also appear in album/list -> ${JSON.stringify(overlap.map((a) => a.albumId))}`);
    failures++;
  }

  // 8. The upload affordance the slideshow's party mode is exercised with: a photo
  //    added to one album has to land at the top of that album's first page — the page
  //    a running slideshow re-reads — and must not leak into any other album.
  const albumQuery = (id) => ({
    album_id: String(id),
    limit: '40',
    offset: '0',
    sort_by: 'date_time',
    sort_direction: 'desc',
  });
  const album1Before = await signedGet(session.token, '/p/api/v1/album/photos', albumQuery(1), 'raw');
  const album2Before = await signedGet(session.token, '/p/api/v1/album/photos', albumQuery(2), 'raw');

  const uploaded = await control('/__control/add-photo?album=1');
  const album1After = await signedGet(session.token, '/p/api/v1/album/photos', albumQuery(1), 'raw');
  const album2After = await signedGet(session.token, '/p/api/v1/album/photos', albumQuery(2), 'raw');

  const firstAfter = album1After.data?.list?.[0];
  const secondAfter = album1After.data?.list?.[1];
  const firstBefore = album1Before.data?.list?.[0];
  // The page is capped at 40 and this album has far more, so the length cannot grow:
  // what proves the arrival is that it took the first slot and shifted everything
  // else down by one.
  if (firstAfter?.id === uploaded.added && secondAfter?.id === firstBefore?.id) {
    ok(`an uploaded photo arrives first in its album (id=${uploaded.added})`);
  } else {
    bad(`uploaded photo did not arrive first -> ${JSON.stringify(firstAfter)?.slice(0, 120)}`);
    failures++;
  }

  if (album2After.data?.list?.length === album2Before.data?.list?.length) {
    ok('the upload left every other album alone');
  } else {
    bad('an uploaded photo leaked into another album');
    failures++;
  }

  // 9. A grant that arrives *after* the client has already listed the section has to
  //    show up on the next read. This is the whole reason entering 他人分享 re-checks
  //    instead of trusting what it loaded earlier.
  const granted = await control('/__control/grant-album?name=Revalidation');
  const toMeAgain = await signedGet(session.token, '/p/api/v1/album_grant/list_to_me', {
    offset: '0',
    limit: '300',
    sort_by: 'share_mod_time',
    sort_direction: 'desc',
  }, 'raw');
  const reListed = toMeAgain.data?.list || [];
  if (reListed.length === shared.length + 1 && reListed.some((a) => a.albumId === granted.albumId)) {
    ok(`a new share appears on the next read (albumId=${granted.albumId})`);
  } else {
    bad(`a new share was missing from the next read -> ${reListed.length} entries`);
    failures++;
  }

  // 10. The app signs in over TLS, so the login handshake has to work on the TLS
  //     listener too — including the WebSocket upgrade, which a plain HTTPS request
  //     cannot exercise.
  try {
    const tlsLogin = await login({ tls: true });
    if (tlsLogin.token) {
      ok(`the login handshake works over wss on the TLS port (${TLS_PORT})`);
    } else {
      bad('TLS login returned no token');
      failures++;
    }
    tlsLogin.socket.close();
  } catch (error) {
    bad(`TLS login failed: ${error.message}`);
    failures++;
  }

  const stats = await control('/__control/reset');
  console.log(`\n  counters reset: ${JSON.stringify(stats)}\n`);

  session.socket.close();
  console.log(failures === 0 ? 'ALL CHECKS PASSED' : `${failures} CHECK(S) FAILED`);
  process.exit(failures === 0 ? 0 : 1);
})().catch((e) => {
  console.error(`verification crashed: ${e.message}`);
  process.exit(1);
});
