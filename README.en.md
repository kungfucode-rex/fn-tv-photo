# FN Photo

**English** | [简体中文](README.md)

An Android TV app for browsing a photo library stored on a **飞牛 fnOS** NAS, driven entirely
by the TV remote.

It talks to the fnOS photo gallery's own API — the same one the official app uses — so it gets
the real timeline, albums, folders, server-side thumbnails, Live Photos and video streaming,
rather than treating the NAS as a dumb file share.

<img src="artifacts/forum/app-icon.png" width="96" alt="The FN Photo icon">

![Timeline](artifacts/forum/1-timeline.jpg)

> Changing code, building, or wondering why something is written the way it is?
> [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) is the developer entry point; it leads to the
> protocol notes, the verification record and every design trade-off. This README covers
> **what the app does and how to use it**.

## Screenshots

| | |
| --- | --- |
| **Timeline**<br>![Timeline](artifacts/forum/1-timeline.jpg) | **Albums**<br>![Albums](artifacts/forum/2-albums.jpg) |
| **Shared with me**<br>![Shared with me](artifacts/forum/3-shared.jpg) | **People**<br>![People](artifacts/forum/4-people.jpg) |
| **Folders**<br>![Folders](artifacts/forum/5-folders.jpg) | **A month's photo grid**<br>![A month's photo grid](artifacts/forum/6-month-grid.jpg) |
| **Photos in a folder, with Live Photo / video badges**<br>![Photos in a folder](artifacts/forum/7-folder-photos.jpg) | **Full-screen viewer with the info band**<br>![Viewer with the info band](artifacts/forum/8-viewer.jpg) |
| **Slideshow controls with the music list**<br>![Slideshow controls with the music list](artifacts/forum/9-slideshow-music.jpg) | **Paging arrows**<br>![Paging arrows](artifacts/forum/10-paging-arrows.jpg) |
| **Light theme**<br>![Light theme](artifacts/forum/11-light-theme.jpg) | **Settings**<br>![Settings](artifacts/forum/12-settings.jpg) |
| **Sign-in**<br>![Sign-in](artifacts/forum/13-login.jpg) | **Original resolution**<br>![Original resolution](artifacts/forum/14-viewer-original.jpg) |

## What it does

Browsing:

- **My photos (timeline)** — **one row per year, the months of that year laid out across it.**
  A row starts with the year, then one card per month that has photos: month above, that
  month's first photo below. Left/right walks the months of a year, up/down moves between
  years.
- **My albums** — user-created albums with a cover and photo/video counts.
- **Shared with me** — albums other accounts on the NAS shared with yours, each card naming
  the account. An empty list is a normal state and says so, rather than offering a retry.
- **People** — face clusters from the gallery's on-device AI, as circular avatars; hidden
  faces are filtered out. An empty list is normal (AI indexing off) and explains itself.
- **Folders** — folders the gallery app manages, walked level by level. The button beside the
  title switches **grid/list**; both shapes are remembered, and they share one position memory.
- **Re-checked on entry** — selecting a section or opening a grid re-reads the list behind it,
  so a newly shared album or a freshly uploaded photo appears without a restart. A failed
  re-check never replaces what is already on screen with an error.

Viewing:

- **Full-screen viewer** — a photo fitted to the screen, left/right paging, an info band, and a
  slideshow.
- **Paging never blanks the screen** — paging moves a **pointer**: a right press advances it one
  step, and the photo on screen does not move until the one the pointer stopped on has been
  downloaded and decoded. A row of breathing arrows shows how many steps it walked. **Only the
  photo the pointer stopped on is fetched** — the ones in between are never shown and never
  downloaded. When it lands it replaces the previous photo with no black frame.
- **A photo that has not arrived is not a black screen** — the viewer opens on a spinner and
  "加载中…", dropped the moment the first photo decodes.
- **Original or thumbnail preview** — **Settings → General → Preview originals** cycles through
  **Yes** (every preview uses the original), **No** (every preview uses the large thumbnail) and
  **Slideshow only** (the default), and remembers the choice. A photo whose original is already
  in the cache is drawn at full size while paging by hand. The info band states whether what you
  are looking at is the **original** or a **thumbnail**; when it is a thumbnail an **原图**
  button appears in the corner and fetches full quality for that one photo, without changing the
  setting.
- **Video and Live Photos** — video streams with seekable scrubbing; Live Photos carry a badge
  and play their moving part.
- **It remembers where you were** — leaving a photo returns to that photo in the grid, leaving a
  grid returns to that month, and so on up.

Slideshow:

- **A slideshow only plays photos** — a video in the rotation would stall the advance and cover
  the music underneath, so both orders step over them. Videos can still be opened by hand.
- **Two doors, one value** — the **幻灯片** button in the viewer's info band and **Settings →
  Slideshow** both edit the interval (3/5/8/15/30 s) and the order (in order / shuffle);
  changing it in either place changes both. The music is ticked from the viewer's track list and
  from the music screen, and both write the same stored selection.
- **Background music under the slideshow** — starts with the show, loops the ticked tracks in
  list order, stops with the show. **One** track ships inside the APK, because a soundtrack has
  to start the instant the show does.
- **Your own music** — beyond that one bundled track, and with no computer and nothing to
  deploy. The **upload page** (the last row of the viewer's track list, `＋ 上传 / 管理背景音乐…`,
  or **Settings → Slideshow → Upload background music**) is a title and a QR code on the left
  and **one numbered scrolling list** on the right. **Upload from a phone**: the phone joins the
  same Wi-Fi, scans the code and sends mp3 files one after another — each one appears at the
  **top** of the list and its border **flashes twice**, so "did it arrive" is something the eye
  answers. In the list, **OK selects or unselects** (only ticked tracks play) and a **long press**
  brings out two buttons, 排序 and 删除: left/right moves between them, OK does the highlighted
  one, Back puts them away. Sort turns that row into ▲▼ and up/down move it one place; Back
  confirms the order. **The number is the playback order**, and the bundled track is a row like
  any other — its file is inside the app so deleting it only takes it out of the list, and a
  `恢复内置曲目` row puts it back. Uploads live in the TV's library folder, whose full path the page
  prints at the bottom of the list: copying an mp3 in there with a cable or a file manager works
  exactly like uploading it. `清空我的音乐` deletes those copies only, the library holds 1 GB with a
  40 MB per-file ceiling, and a newly added track is **not ticked for you**: ticking reloads the
  playlist from the first bar, which is not a thing "add" should do to something already playing.
- **Party mode** — a running slideshow re-reads the album every **30 seconds**: newly uploaded
  photos are **appended** to the rotation (never re-sorted, so the photo being watched is not
  swapped out mid-view), and deleted photos leave it.

图评 (photo review — a QR code on the TV, comments from a phone drifting across the photo):

- Needs a server of your own; see **The 图评 server** below.
- **A photo has to be opened for review before the server knows about it.** Preview a photo,
  press ↑ for the information band, and press **Scan to comment**: the band closes, the QR
  code appears in the corner, and the danmaku comes on — all at once. Simply looking at photos
  adds nothing; until you press that button the server does not know the picture exists. (One
  exception, and only after the review server's address has changed: a photo the server *does*
  already hold is registered once more when you open it, if its code opens somewhere else —
  that adds no photo, it just gets the address right.)
- A photo that was opened before is recognised afterwards and shows its code and its danmaku
  straight away, **without being uploaded again**. A second television has to open each photo
  once for itself.
- The danmaku is automatic: the server has comments for this photo and they drift across it;
  the server has none and nothing is drawn. The H5 page behind the code needs no app and no
  account — guests may write as 匿名.
- **A whole slideshow can carry review**: press **幻灯片** in the viewer and turn on the last row
  (**图评**), which is off by default. That show registers each photo as it appears — the party
  case — while with it off only the photos you opened by hand have a code.
- **The danmaku amount (关 / 少 / 多) lives in Settings → Photo review.** It governs the font
  size, the number of lanes and whether recent comments are replayed, and **关 means no danmaku
  at all**. The viewer's corner no longer has a review menu — just the one **Scan to comment**
  action.
- **With no review server configured — or 图评 set to 关 — neither the Scan to comment button
  nor the slideshow row's 图评 switch is drawn at all.** Two controls with no server behind them
  are an invitation to press a button that cannot answer. Filling the address in brings both
  back, and Settings → Photo review is where the address goes.
- **A comment deleted on the phone leaves the TV**: the server pushes a comment's *state*, and
  the TV replaces its copy of that id. A line already crossing finishes its pass.
- Photos only: a video gets no QR code and no danmaku layer.

Settings and the rest:

- **The settings page is two columns** — a category sub-menu on the left (**Updates / General /
  Slideshow / Photo review**) and the matching pane on the right, which is far easier to find
  things in than the single list that had grown off the bottom of the screen. Focus scrolls the
  row it lands on into view.
- **Update check** — **Settings → Updates**: opening the pane checks once, OK checks again. It
  asks GitHub and Gitee (both at once, taking the newer answer). When something newer exists the
  row reads **有新版本 x.y.z**; OK downloads it, **verifies the SHA-256 first, then hands it to
  the system installer** — a file that does not match is deleted and reported as 安装包校验失败.
  Android 8 and above also needs the "install unknown apps" grant, which the app asks for
  **before** downloading. Any route that fails leaves its reason and the manual fallback (the
  release page address) on the row.
- **A red dot on 设置 in the main menu when a newer build exists** — the app checks by itself at
  start-up, so you do not have to open the settings page to find out.
- **Dark or light** — **Settings → General → Theme** switches the whole app and remembers it.
  Dark is the default and what a dim room wants; light exists because a bright room turns a dark
  interface into a mirror. No restart needed.
- **Address only, HTTPS only** — the login field takes an address like `192.168.31.14:50317`;
  with no port it uses fnOS's secure default of 5667. Typing `http://` is folded to `https://`,
  so credentials never leave the TV in the clear.
- **The NAS certificate is trusted on first use.** fnOS serves a self-signed certificate; the
  app remembers the fingerprint on first connection and refuses a **different** one once, saying
  so — pressing 登录 again accepts it. These certificates last about three months, so renewal is
  normal.
- **Access code** — if fnOS gates its login entry behind one, the app passes the gate by itself;
  the field only appears when the server actually demands it.
- **Signing in once is enough** — credentials are saved as they are submitted, so even a
  **failed** attempt (wrong access code, a NAS that is asleep) does not make you retype
  everything on a remote. Credentials are remembered **per server and account**, and the login
  screen offers them as one-press shortcuts.
- **An expired session recovers by itself** — after the TV has been left alone long enough for
  the NAS to retire the session, a page shows a 401 and a 重试 button; retrying re-establishes
  the session exactly as a fresh login would (access-code gate included), so there is no need to
  sign out and back in. Only a genuine failure — a changed access code, say — falls back to the
  login form, with the server's own reason on it.
- **Image cache** — **Settings → General → Clear image cache**: capped at **5 GB**, and never more
  than a quarter of the space the device has free (TV boxes often have only a gigabyte or two, and a
  cache that fills the volume is, to Android, simply a cache to delete — after which the whole album
  is downloaded again). LRU-evicted; images only, since video streams and is never written to disk.
  The settings row shows both what is used and the ceiling actually in force, and says so when free
  space lowered it.
- **When it crashes on the TV** — uncaught exceptions are written to `files/last-crash.txt`, and
  the first line of the most recent one shows under **Settings → General → Last crash**, where
  somebody holding the remote can read it out.

## Remote control

| Key | Timeline / albums / folders | Viewer |
| --- | --- | --- |
| D-pad | Move focus | — |
| ← / → | — | Previous / next photo; once a row is open (the slideshow settings row, or 图评's three switches), or when the corner holds two or more buttons, these walk those controls instead |
| ↑ / ↓ | — | Show / hide the info band; once open, ↑/↓ walk the corner controls: 原图 → 图评 → 幻灯片 → close the band; on a selected setting control they change its value |
| OK | Open the focused item | Start / pause the slideshow (play/pause for video); with the band open, operate the highlighted control |
| Back | Up one level | Close the open row if there is one, otherwise leave the viewer |
| Back ×2 | Exit the app (at the top level) | — |

Settings is two columns: up/down moves between the categories on the left, and whichever one has
the cursor is the pane shown on the right; right enters the options, left goes back to the list.

## Requirements

- An Android TV or TV box running **Android 6.0 (API 23)** or newer.
- A 飞牛 NAS reachable over **HTTPS** on the LAN with the gallery service running — its secure
  web port is 50317 on the author's NAS and 5667 by default. There is no plaintext mode; the
  NAS's self-signed certificate is trusted on first use (see above).
- A fnOS account that can read photos. What **Folders** lists is the folders the gallery app
  **manages**; add some in fnOS if the list is empty.

## Install

Signed APKs are attached to the GitHub releases — no need to build anything:

**<https://github.com/kungfucode-rex/fn-tv-photo/releases/latest>** — `FN-tvphoto-1.5.6.apk`,
about 26 MiB. The same release, more reliably reachable from mainland China, is on Gitee:
**<https://gitee.com/kungfucode/fn-tv-photo/releases/latest>**.

Onto the TV:

```bash
adb install -r FN-tvphoto-1.5.6.apk
```

Once installed, the app can update itself: **Settings → Updates → Check for updates**, or open it
from the red dot beside 设置 in the main menu.

The same APK installs on a phone with an ordinary launcher icon — the UI stays a landscape,
D-pad-first TV layout.

To build it yourself, publish a test build, or put a debug build on a TV, see
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md).

## The 图评 server (Docker)

That 图评 button — a QR code in the corner of a photo, a guest scanning it, their comment
drifting back across the TV — needs a server. It lives in [`server/`](server/README.md): a static
Go binary carrying its own H5 comment page, whose entire state is one SQLite file and a thumbnail
directory under `data/`.

**The only value a deployer has to fill in is an admin token**; everything else has a default.
The finer details (the full environment-variable table, signing keys, address rules, building
from source) are in [`server/README.md`](server/README.md).

### What you need

- A machine running Docker (fnOS / Synology / QNAP / any Linux) **on the same LAN as the TV and
  the phone**;
- FN Photo installed on the TV (see **Install** above);
- A free port (8080 by default) and a directory for its data.

**Do not expose this port to the internet.** It has no account system beyond one admin token and
its comments are visible to anyone who can reach it; the LAN is its design boundary.

### One file is enough (recommended)

The image is on Docker Hub and is **multi-architecture**: amd64 or arm64 is decided by the
image's own manifest, so there is no binary to pick, no Dockerfile to manage and no files to
collect in one directory.

In fnOS **Docker → Compose → New project**: name it `tuping`, paste the block below, replace
`TUPING_ADMIN_TOKEN` with your own token, and press **start** (this route has **no "build" step**
— it only pulls the image):

```yaml
services:
  tuping:
    image: "kungfucode/tuping:0.1.4"
    container_name: "tuping"
    restart: "unless-stopped"
    ports:
      - "8080:8080"
    volumes:
      - "./data:/data"
    environment:
      TUPING_ADMIN_TOKEN: "change-me"
```

The left-hand `8080` is the port on the NAS (change it to `8090:8080` and so on if it is taken —
the addresses used on the TV and the phone then carry that port too); `./data` is where comments,
thumbnails and the signing key land; `container_name` makes the container `tuping` (the
`docker exec tuping …` commands below use that name).

> **Every line here is short and every value quoted, on purpose**: some tools reflow long lines
> when copying from a rendered page, and break on `:` and `-` inside **unquoted** values —
> `kungfucode/tuping:0.1.4` becomes two lines and `unless-stopped` becomes `unless-` +
> `stopped`, and the whole block fails to parse. Quoted values are never split, hence the quotes.
> If you would rather not risk it, do not paste: copy
> [`server/docker-compose.image.yml`](server/docker-compose.image.yml) into a directory with a
> file manager and create the project **by path** in the panel, or use the command below.

Without Compose, the equivalent one-liner:

```sh
docker run -d --name tuping --restart unless-stopped -p 8080:8080 \
  -v "$PWD/data:/data" -e TUPING_ADMIN_TOKEN='your-token' kungfucode/tuping:0.1.4
```

| Variable | What to put in it |
| --- | --- |
| `TUPING_ADMIN_TOKEN` | The admin token, typed by hand when signing in to `/admin`; **blank = no admin** (no "admin sign-in" appears on the page) |

Everything else has a default: `TUPING_SECRET` (the signing key) is generated on first start and
stored under `data/`; `TUPING_BASE_URL` (the address inside the QR code) defaults to "whoever is
connecting" — the address in a code is the one the TV reported when it registered the photo.

### Three-step acceptance

1. **Read the log**: once the container is up, two lines should be there — missing either one
   means something is wrong:

   ```
   level=INFO msg="running unprivileged" uid=10001 gid=10001
   level=INFO msg="tupingd listening" addr=[::]:8080 ... db=/data/tuping.db
   ```

2. **Ask the service**: `curl http://<NAS address>:8080/healthz` → `{"ok":true,"version":"0.1.4",...}`.
3. **Open the admin page**: `http://<NAS address>:8080/admin` on a phone, sign in with the token
   from step 2, and see the review queue.

Then on the TV: **Settings → Photo review → Review server** takes **the same address**
(`http://192.168.31.14:8080` and so on — the phone has to be able to open it too). Preview a
photo, press ↑, press **Scan to comment** → the QR code appears → scan it with the phone and
write a comment → the comment drifts across the TV.

### When something is wrong

| Symptom | What it is / what to do |
| --- | --- |
| Pulling the image is slow or fails with `401`, `403` or `connection reset` | The NAS's registry mirror. **Some mirrors only mirror official images**: `docker.1panel.live` and `docker.xuanyuan.me` serve `library/alpine` fine but answer `403` for this one. Two mirrors measured to work are `https://dockerproxy.net` and `https://hub.1panel.dev`. Put one of each kind under Docker's **Images → Registry settings**, then **stop and start the Docker service** and start the project again. |
| The container restarts in a loop, log says `unable to open database file` | The data directory's owner does not match the container's uid. The image handles this itself; only a mount on SMB/CIFS, which cannot `chown`, needs `user: "0:0"` added to the service. |
| It will not start, `port is already allocated` | Something else holds 8080: change the left-hand side of `"8080:8080"` (to `"8090:8080"`), and carry that port in the TV and phone addresses too. |
| The QR code scans but will not open | The address inside a code is the one the TV reported: first confirm on the TV that a phone can open that address (same LAN, right port), then register the photo again on the TV. |
| HTTPS configured, but http still works and https does not connect | Check two things in order: ① run `/usr/local/bin/tupingd -check` in the container — **no `tls` line at all** means this binary knows nothing about TLS (0.1.1 and later do), so move the image tag to 0.1.4 or build it yourself; ② a `tls` line that is still `off` means the environment variables never reached the container — after editing the compose file you must press **"start / redeploy"**, pressing only "restart" is not enough. |
| The container stays unhealthy after enabling HTTPS | The old compose file's health check was `wget http://127.0.0.1:8080/healthz`, which cannot work under TLS — the service is fine. Replace it with the binary's own probe: `test: ["CMD", "/usr/local/bin/tupingd", "-health"]`. |
| The page says 发得太快了，歇一会儿 (HTTP 429) | **Rate limiting working, not a fault.** Wait a minute, or change photo; the full table and its reasoning are in [`docs/tuping-design.md`](docs/tuping-design.md) §7. |
| Scanning on the TV says 这张照片不在图评里 | The code on the TV was issued by a **previous** server (reinstalled, or a different `data/` directory). 1.4.11 onwards gets out of this by itself; earlier builds need a restart. Do not swap the `data/` directory if you want to keep the comments. |

### If you want HTTPS

Three additions to that compose file give you HTTPS (the domain, the port mapping, and obtaining
and renewing the certificate are yours to decide — an ACME client, a reverse proxy or a tunnel):

```yaml
    ports:
      - "443:8080"
    volumes:
      - "./certs:/certs:ro"
    environment:
      TUPING_TLS_CERT: "/certs/fullchain.pem"
      TUPING_TLS_KEY: "/certs/privkey.pem"
```

(The left-hand `443:8080` is the port on the NAS, so phones no longer need one; `./certs` holds
the certificate and key and must be readable by uid 10001.) Then set the TV's **Review server**
to `https://your.domain`.

Two traps worth knowing in advance: **the two variables must come as a pair** (setting only one
fails the start and names the missing one, rather than quietly falling back to plaintext); and
**a self-signed certificate is no use to guests** — the QR code is scanned by somebody else's
phone, and WeChat blocks untrusted certificates, so a green padlock needs a real one. All the
detail is in the **HTTPS** section of [`server/README.md`](server/README.md).

### Upgrade / back up / remove

- **Upgrade**: change the image tag in the compose file and press "start / redeploy" — "restart"
  alone neither swaps the image **nor installs new environment variables**.
- **Back up**: three things under `data/` — `tuping.db` (comments), `thumbs/` (thumbnails) and
  `secret` (the signing key). The first two are essential.
- **Remove**: stop the project and delete the container; delete `data/` too if the comments
  should go with it.

## More documentation

| To learn about | Read |
| --- | --- |
| Using the app, installing it, deploying the server | this README |
| Changing code, building, running tests, releasing, the emulator and mocks | [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) |
| How each feature was verified, and which traps it came from | [`docs/verification.md`](docs/verification.md) |
| The reverse-engineered fnOS gallery API (with evidence) | [`docs/fnos-photo-api.md`](docs/fnos-photo-api.md) |
| 图评's protocol and decisions (the single source of truth) | [`docs/tuping-design.md`](docs/tuping-design.md) |
| Your own slideshow music: the library, the phone upload, the directory scan | [`docs/custom-music-design.md`](docs/custom-music-design.md) |
| The 图评 server's own documentation | [`server/README.md`](server/README.md) |
| A quick orientation for an agent or a new contributor | [`AGENTS.md`](AGENTS.md) |
