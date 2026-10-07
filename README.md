# TV Photo

An Android TV app for browsing a photo library stored on a **飞牛 fnOS** NAS, driven
entirely by the TV remote.

It talks to the fnOS photo gallery's own API — the same one the official app uses —
so it gets the real timeline, albums, folders, server-side thumbnails, Live Photos
and video streaming, rather than treating the NAS as a dumb file share.

![Timeline](artifacts/screenshots/22-autologin-restart.png)

## What it does

- **我的照片 (the timeline)** — **one row per year, that year's months across it**. A row
  opens with the year, then a card per month: the month above, that month's first photo
  below. Months with no photos get no card. Left/right walks the months of a year, up/down
  moves between years, so a decade of library is a few dense rows instead of a
  screenful of near-empty year bars.
- **我的相册 (albums)** — user-created albums with covers and photo/video counts.
- **他人分享相册 (shared with me)** — albums other accounts on the NAS have shared
  with you, each card naming the account it came from. A route of its own
  (`album_grant/list_to_me`), not a filter on your own album list. An empty list is a
  normal state — it means nobody has shared one — so it says so rather than offering a
  retry.
- **People** — face clusters from the gallery's on-device AI, with circular face
  avatars. Hidden clusters are filtered out. An empty list is a normal state (AI
  indexing off), so it explains that instead of offering a retry.
- **Folders** — the folders the gallery app manages, with drill-down into
  subfolders and their media.
- **Party mode** — a running slideshow re-reads the album every **30 s**, so a picture a
  colleague sends to the album joins the rotation without anyone touching the remote,
  and one the host deletes leaves it. Arrivals are appended rather than re-sorted into
  their newest-first position: the show walks the list by index, so re-sorting would
  swap the photo on screen mid-display, and appending means each arrival is shown once
  per pass rather than being skipped as already passed. A photo that is no longer in the
  freshly-read page is dropped — see **A deletion leaves the show** below for how much
  one page can be trusted to say.
- **Fresh on entry** — selecting a section, and opening a photo grid, re-checks the
  list behind it, so an album someone shares with the account or a photo uploaded to
  the NAS turns up without restarting the app. Whatever is already on screen stays up
  while that runs: a re-check never blanks it, and a failed one never replaces working
  data with an error. A month whose photo count has moved also drops its cached cover.
- **Full-screen viewer** — fit-to-screen photos, left/right paging, an information
  band, and a slideshow. The band's corner button opens slideshow controls — play,
  interval, order (顺序/随机) and background music — without leaving the photo. The show
  itself walks photos only: a clip in the rotation would stop the advance and talk over
  the music, so videos are looked past in either order. No permanent on-screen shortcut
  bar; toggling the slideshow shows a brief notice that dismisses itself.
- **A photo that has not arrived is not a black screen** — the viewer opens on a spinner
  and 加载中… until the first picture is decoded, and drops them the instant it is
  (`140-viewer-loading.png`, `142-viewer-loaded.png`). Paging never needs them: the photo
  being left stays up until the next one is ready. The corner arrows are the other half of
  that rule — they report the user's own presses, never the show's own fetching.
- **Background music under the slideshow** — **背景音乐**, after 播放顺序 in the
  same row, opens a list of tracks above itself: up/down walks them, OK ticks and
  unticks, Back closes the list with the focus back on the control it came from
  (`121-music-list-open.png`, `124-music-both-ticked.png`). The music starts with the
  show, plays the ticked tracks in the order they are listed, loops, and stops with the
  show. It is *held* rather than discarded in the two places something else has the
  room's attention: while the settings row is open, which holds the photo timer for
  exactly the same reason, and while a video is on screen, which plays its own audio.
  Leaving the viewer ends it. The tracks travel inside the APK (8.5 MB of it) instead of
  being fetched from the NAS, because a soundtrack has to start with the show: a track
  that had to be authenticated and buffered first would put silence in front of every
  one.
- **Dark or light** — **Settings → 主题** switches the whole app and remembers the
  answer. Dark stays the default, and the one a photo browser in a dim room wants; light
  is there because a bright room turns a dark UI into a mirror, and a TV is not always
  the only thing being looked at. The window is filled with the chosen background before
  Compose draws its first frame, so a light-mode launch does not flash black first
  (`110`–`113`, `131`).
- **Paging never blanks the screen.** A page turn is a *queue*, not a jump: pressing
  right puts the next photo in the queue and, until it has actually been downloaded and
  decoded, the photo on screen stays exactly where it is and a row of breathing arrows
  appears in the bottom-right corner — one per photo still queued, so three presses
  right read as three photos, and left likewise. The moment the queued photo is ready it
  becomes the photo on screen, in place of the one before it, with no black frame in
  between. Two image layers sit under the viewer at all times and a page turn only
  changes which one is opaque, because changing the *model* of the visible one is what
  used to blank the screen while the new photo downloaded.
- **A deletion leaves the show.** The 30 s re-read is compared with the list the
  slideshow is walking, and a photo the page no longer lists is dropped from the
  rotation instead of staying on screen as a picture that is no longer in the library.
  How much a single page can prove is read from the page itself: one that did not fill
  up reached the end of the album and speaks for all of it, while a full page only
  covers its own window — and anything the show appended itself is by construction among
  the newest photos, so its absence is conclusive even past that window.
- **Remembers where you were** — backing out of a photo returns to that photo in the
  grid, backing out of a grid returns to that month, and so on up the chain. The
  navigation rail does not steal focus back.
- **Video and Live Photos** — videos stream with seeking; Live Photos are badged
  and play their motion clip. **This is the one path that does not use the app's pinned
  certificate trust, so against a NAS whose certificate is only pinned it fails** — see
  *Known limitations*.
- **Fast viewing** — the cache is kept warm for the next 50 full-size originals
  *starting from the photo you are on*, so paging forward is what gets accelerated.
  Media requests also run eight-at-a-time rather than OkHttp's default five, because the
  prefetcher and the thumbnails on screen share one client: with five, the pictures the
  user is looking at queued behind full-size originals nobody had asked for yet, which
  reads on a TV as the grid stuttering as focus moves.
- **Sign in once** — credentials are stored on submit, so even a *failed* attempt
  (wrong access code, NAS asleep) does not make you retype everything with a remote.
  They are remembered per **server + account**, not per server: one NAS with several
  accounts keeps each one's own password and 访问码, and the login screen offers them as
  one-press shortcuts. Typing a different account's name for the same address brings that
  account's password with it. Starting the app with a saved login shows a **loading
  screen**, not the login form: the form used to appear for a moment, take focus and ask
  the system for the on-screen keyboard, and then be thrown away the instant the sign-in
  landed. If the sign-in takes more than a few seconds the loading screen says so and
  offers the way out a remote can press — Back — which leaves the form, prefilled, with
  the server's own message on it.
- **Address only, HTTPS only** — the login field takes a server address such as
  `192.168.31.14:50317`; with no port it uses the fnOS secure default, 5667. There is no
  cleartext mode: a typed `http://` is folded to `https://`, so credentials never leave
  the TV in the clear.
- **The NAS certificate is trusted on first use.** fnOS serves a self-signed certificate
  (`O=fnOS CN=fnOS`) whose `subjectAltName` is `DNS:fnOS` — no IP, no name a user could
  type — so no stock HTTPS client can validate it, for two independent reasons. The app
  therefore remembers the fingerprint the first time it connects, and refuses a *different*
  one once: you are told the certificate changed and the next press of 登录 accepts it.
  These certificates last about three months, so a renewal is normal, not an attack.
  See [docs/fnos-photo-api.md](docs/fnos-photo-api.md#7-tls--the-self-signed-certificate).
- **FN ID sign-in was removed.** It resolved through the vendor's cloud, whose relay
  (`<fnid>.fnos.net:443`) answers every real path with a 302 to its own landing page and
  proxies nothing to the NAS, so that route could never carry the API. What a remote setup
  needs instead is in [docs/fnos-photo-api.md](docs/fnos-photo-api.md#6-fn-connect--removed).
- **访问码 (access code)** — if fnOS is configured to gate the login entry, the app
  passes that gate itself. The field only appears when the server actually asks for
  it. See [docs/fnos-photo-api.md](docs/fnos-photo-api.md#2b-the-访问码-access-code-gate).

## Image cache

| | |
| --- | --- |
| Ceiling | **5 GB**, LRU — the oldest entries are dropped automatically once exceeded |
| Contents | **Images only.** Video is streamed by Media3 with no cache configured, so a video file is never written to disk |
| Location | the app's `cache/image_cache` directory |
| Visible in | **Settings → 清除图片缓存**, shown as `已用 x / 5.0GB` |

Prefetching decodes at 1×1 on purpose: Coil writes the **full** response to the disk
cache before decoding, so the whole original is cached while only a single pixel is
ever held in memory. Asking for full-size decodes would mean fifty multi-megapixel
bitmaps in RAM for images the user may never open.

The window is anchored at the **current** photo and only re-anchors once the user
moves outside it — restarting on every focus change would cancel downloads already
in flight, so scrolling would starve the cache instead of filling it. Measured on
device with a cold cache: opening a grid fetched exactly **50 originals**
(ids 1015–1068), and moving 13 rows down fetched **37 new ones** (1066–1104).

## Crashing on a TV

A television has no console, so a crash there leaves exactly one piece of evidence —
that the app vanished — and the next launch starts from scratch. Uncaught exceptions are
therefore written to `files/last-crash.txt` and the first line of the last one is shown
in **Settings → 上次崩溃**, where somebody with a remote can read it out. The handler
installed to do that passes the failure on to whatever handler was already there, so a
crash still ends the process the way Android intends; it only records.

Two start-up paths that could once kill the process on their own are now contained: the
automatic sign-in with saved credentials, and Coil's disk cache. The second is the one
worth naming — a cache directory the system half-cleaned, a full volume or a journal left
behind by a kill made building the image loader throw, and because that happens lazily,
the throw landed inside the first image request, during composition. It now costs the
disk cache and nothing else: the app runs memory-only, and says so in the log.

## Remote control

| Key | Timeline / Albums / Folders | Viewer |
| --- | --- | --- |
| D-pad | move focus | — |
| ← / → | — | previous / next photo; picks a control when the slideshow row is open |
| ↑ / ↓ | — | show / hide the information band; changes the value when a slideshow stepper is selected |
| OK | open the focused item | start / pause slideshow (play/pause for video); with the band up, operates the focused control |
| Back | up one level | close the slideshow row if it is open, otherwise leave the viewer |
| Back ×2 | exit the app (from the top level) | — |

Paging is queued rather than immediate, so ← / → show what is on its way: arrows in the
bottom-right corner, one per photo still queued, breathing while they wait. Nothing about
the list moves until the queued photo is ready; then it becomes the photo on screen.

**The arrows are for the user's own presses.** A running slideshow fetches its next
picture through the same queue, and reporting that would put code on screen that nobody
typed — so the queue remembers which of its entries the show asked for and counts only
the rest. Press ← or → during a show and the arrow is back, because that one *was* typed.

A photo that has not arrived yet used to be a black window, which says nothing about
whether anything is happening. The viewer now shows a spinner and 加载中… instead, and
drops them the moment the picture is decoded. It only ever appears when there is genuinely
nothing to look at: a page turn keeps the photo being left on screen until the next one is
ready, and a file the server could not produce is not "on its way".

The bottom band holds the photo's details on the left and a **幻灯片** button in the
corner. That button opens a row of controls above itself: play/pause, the interval
(3 s / 5 s / 8 s / 15 s / 30 s), the order (**顺序** / **随机**) and the background
music. Left/right picks a control and up/down changes its value. Both are remembered, and
the interval is the same one **Settings** edits. The photo deliberately does not advance
while the row is open. Back closes the row and leaves the button up; a second Back leaves
the viewer.

**The show walks photos and nothing else.** A clip in the rotation would stop the advance
and talk over the music playing underneath it, so the next picture is chosen by looking
past videos — several in a row if that is what the library has, and in either order
(**顺序** walks; **随机** draws from the photos, so a run of clips cannot skew the pick).
A clip is still reachable by hand, and if one is on screen during a show it gets one
interval like everything else and the show moves on, rather than waiting for a stream that
may never play.

**背景音乐 is the one control that is not a value to step.** It is a multi-select, so OK
— or either arrow — opens the list of tracks above the row, where up/down walks the
tracks, OK ticks and unticks (applied at once; the tick is the confirmation), and Back
closes the list and puts the focus back on the control it came from. Left/right and the
photo keys do nothing while the list is up, so nothing moves underneath it.

**Setting controls all take one shape** — the slideshow row is the reference. A pill
reads `label · value · ▲▼`, with the label on the left and the arrows on the right of
the value, dimmed until the control is selected. Left/right moves between controls,
up/down changes the value and applies it at once, and OK steps it on. The arrows are the
point of the shape: they are what tells someone that up/down does something here. Do not
drop them, and do not stack them above and below the value — that costs a line of height
for no gain. (`ActionStepper` in `ui/viewer/` is the implementation; lift it into
`ui/components/` when a second settings surface needs it.) The music control keeps that
pill's geometry but opens a list instead of stepping: cycling one pill through "none,
this one, that one, both" would hide the ticks — the thing being decided — behind one
press at a time.

## Requirements

- Android TV or a TV box running **Android 6.0 (API 23)** or newer.
- A fnOS NAS reachable on the LAN **over HTTPS**, with the photo gallery service
  running — its secure web port, 50317 on the author's NAS and 5667 by default. The app
  has no cleartext mode, and the NAS's self-signed certificate is trusted on first use
  (see above).
- The fnOS account must be able to read the photos. The gallery app's **managed
  folders** are what appears under *Folders*; add them in fnOS if the list is empty.

## Install

Build a signed APK:

```powershell
pwsh -File tools/build.ps1 -Tasks assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
#    and, in the same run, \\fnos-ms01\Temp\软件\app-release-20261007-150353.apk
```

A run that assembles an APK **uploads it to the share the TV is installed from** as well
(`tools/publish-apk.ps1`), reading the copy back to compare hashes. Building and publishing
are one step on purpose: the file on the NAS cannot then lag behind the source, and nobody
has to remember to copy it. Only **release** is published by default — the debug APK is for
the emulator, and a second file in a folder people install from is just a way to install
the wrong one. `-NoPublish` builds only, `-PublishVariants @('release','debug')` sends
both, and `-PublishDestination <path>` sends it elsewhere. A share that is asleep or not
authenticated does not fail the build — it warns, and the APK is still in
`app/build/outputs/apk/`.

The name on the share carries the APK's own **build time** — `app-release-<yyyyMMdd-HHmmss>.apk`
— so an older build stays there to fall back to and nothing is silently overwritten. The
stamp comes from the file's timestamp rather than the clock at upload, so re-publishing the
same APK reuses the same name instead of piling up duplicates. A leftover untimestamped
`app-<variant>.apk` from the earlier scheme is deleted, but **only** when it is provably the
same bytes as the copy just published; otherwise it is left alone and reported.

Both build scripts are deliberately **pure ASCII**. Windows PowerShell decodes a `.ps1` as
ANSI unless the file carries a UTF-8 BOM, so a literal `软件` in the script arrives as
mojibake and every copy fails with "share is not reachable" — which is exactly what happened
the first time this was written, and again as a mangled em dash in a warning message. The
share name is therefore assembled as `[char]0x8F6F + [char]0x4EF6`, and no message in either
script uses a character outside ASCII.

Then install it on the TV:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with `app/tvphoto.jks`. That keystore is **not** in
version control; generate one first (or let the debug build use its own key):

```powershell
keytool -genkeypair -v -keystore app/tvphoto.jks -alias tvphoto `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass tvphoto -keypass tvphoto `
  -dname "CN=TV Photo, OU=AndroidTV, O=TV Photo, L=Local, ST=Local, C=CN"
```

For a debug build use `tools/build.ps1 -Tasks assembleDebug` and install
`app-debug.apk`; note its application id is `com.tvphoto.debug`.

The same APK also installs on a phone, with a normal desktop icon. `MainActivity`
declares `LEANBACK_LAUNCHER` for the Android TV home screen *and* the plain
`LAUNCHER` category that phone launchers enumerate, and `android.software.leanback`
is `required="false"` so the APK is not filtered off phones. Without the `LAUNCHER`
category the app installs and can be started by an explicit `am start`, but no
launcher ever shows it. On a phone the UI is still the forced-landscape, D-pad-first
TV layout.

## Project layout

```
app/src/main/java/com/tvphoto/
  data/
    fn/               fnOS protocol: crypto, signing, login, HTTP, endpoints
    AppContainer.kt   hand-rolled DI; one token source shared by API + media
    BaseUrl.kt        address normalisation (incl. full-width IME input)
    CrashLog.kt       keeps the last uncaught exception for the Settings screen
    SessionRepository.kt  login lifecycle, keepalive, re-login on expiry
    PhotoRepository.kt    the only data entry point the UI sees
    ThemeMode.kt      dark or light, and which one an install is set to
    SlideshowMusic.kt     the tracks that ship with the app, and how a
                          selection of them is stored and ordered
    SlideshowMusicPlayer.kt  ExoPlayer looping the ticked tracks under a show
  domain/Models.kt    UI-facing models
  domain/SlideshowOrder.kt  which picture a show moves to next: photos, skip the clips
  ui/
    login/ home/ gallery/ viewer/   screens
    components/Spinner.kt   the drawn arc, shared by start-up and by the viewer
    MainViewModel.kt  navigation stack + per-section state
app/src/main/assets/music/   the two tracks a slideshow can play
tools/
  build.ps1           Gradle wrapper with the local toolchain; publishes the APK
  publish-apk.ps1     copies a built APK to \\fnos-ms01\Temp\软件 and verifies it
  tv.ps1              install / launch / screenshot / key injection
  mock-nas/           a mock fnOS server that enforces the real auth rules, plus
                      controls for timing- and change-dependent behaviour
                      (slow media, add/delete a photo, pin a signing rule)
docs/fnos-photo-api.md   the reverse-engineered protocol, with evidence
```

## How this was verified

There is no way to unit-test a closed protocol against the real device from here, so
verification was built deliberately:

1. **A mock fnOS server that actually enforces the rules** — `tools/mock-nas`. Unlike
   the mock shipped with the reference project, it validates the `authx` signature,
   rejects a numeric `si`, and can be pinned to either signing rule at runtime. It serves
   **both** transports: cleartext on 5666 (what `verify.js` drives, and what is easiest to
   read raw traffic from) and TLS on **5667**, with a self-signed `O=fnOS CN=fnOS`
   certificate whose `subjectAltName` is `DNS:fnOS` — the same shape a real NAS presents,
   so the client's first-use trust path is exercised rather than bypassed. Point the app at
   `10.0.2.2:5667` to run it against the mock.
2. **An independent reference client** — `tools/mock-nas/verify.js` re-implements the
   protocol from scratch and asserts that the matching rule is accepted while the
   non-matching one is rejected with `5000`, plus that the login handshake works over
   `wss` on the TLS port. Run it before trusting any conclusion drawn from the mock:

   ```powershell
   cd tools/mock-nas
   npm install
   node server.js          # terminal 1
   node verify.js          # terminal 2
   ```

   It already caught one real bug in the mock itself (the encoded candidate included
   a leading `?` that clients do not sign).
3. **On-device runs on an Android TV emulator** — every screen and remote action was
   exercised against the mock, with screenshots in `artifacts/screenshots/`.
4. **A negotiated-signature test** — with the mock pinned to the rule the client does
   *not* start on, requests are rejected, the client flips rule, retries, succeeds and
   persists the choice. Confirmed by reading both the server counters and the app's
   stored preference.
5. **Unit tests** for address normalisation: `pwsh -File tools/build.ps1 -Tasks testDebugUnitTest`.
   These caught a real bug where `trimEnd('/')` turned `http://` into `http:`.
6. **Controls on the mock for the timing-dependent behaviour** — a viewer that keeps the
   current photo up while the next one downloads cannot be checked by reading code, and
   on a LAN the wait is too short to see. `__control/slow-media?ms=12000&sizes=o` makes
   originals arrive twelve seconds late, and `__control/delete-photo?id=N` removes a
   photo the way a host tidying up a party album would. Neither changes what the protocol
   looks like — only how long it takes, and what the next read of the library contains.

   That is how the paging queue and the deletion path were both confirmed on the
   emulator: with a slow original, queueing 55 page-turns leaves the picture on screen
   with five arrows and a `+2` overflow in the corner (`91-arrows-queue.png`), and the
   queued photo replaces it when it lands (`92-arrows-advanced.png`) — a black frame
   would have shown up in either. Uploading to the running slideshow moved the count in
   the information band from `4 / 16` to `4 / 17`, and deleting that same photo moved it
   back to `4 / 16` two polls later.

   The slow-media control found a real bug in the first cut of the queue: the photo
   *behind* the visible one is often the very photo the queue is waiting for — paging
   back is the obvious case — and that layer had already loaded it, so no load callback
   was ever going to fire and the arrow sat there forever. Layers now track what they are
   actually showing as well as what they have been asked for.
7. **The theme and the soundtrack, on the device** — neither can be read out of the code.
   The theme was toggled from **Settings → 主题** and then walked through: the settings
   screen, the timeline and a month grid in light (`110`–`113`), and the same app after a
   restart, which came back light because the window had been filled with the light
   background before Compose drew (`131`); `138-settings-dark-restored.png` is the same
   row switched back. The viewer stays dark in both modes (`114-viewer-light.png`) — it
   is a surface over a photograph, not over the app. The music was walked the way a
   remote walks it: the list opening above the row (`121`), a tick (`122`), the same
   press taking it off again (`123`), both tracks ticked (`124`), the list closing with
   the focus still on the control it came from (`125`), the row closing without starting
   anything (`126`), left/right doing nothing at all underneath the list — that press
   left the frame byte-identical to the one before it — and the same two ticks still
   there after a restart (`132`, `133`).

   The audio itself was read from the device rather than inferred. With the show running,
   `dumpsys audio` lists `pack: com.tvphoto.debug` holding `USAGE_MEDIA /
   CONTENT_TYPE_MUSIC` focus through `media3`'s `AudioFocusManager`, and the `AudioOut_D`
   thread is out of standby; after stopping the show, and again after leaving the viewer
   mid-show, that focus entry is gone and the thread is back in standby. Focus alone does
   **not** distinguish playing from paused — `media3` keeps it while a paused player sits
   in `STATE_READY` — so the pause paths were read from the output thread instead: with
   the settings row open the thread drops to `0 active` tracks and standby, and returns
   to one active track when the row closes. The same measurement with the show parked on
   a video (`136-slideshow-video.png`) also reads `0 active`, which is the only track
   count that could be the music there — the mock's video carries no audio stream of its
   own (`soun` and `mp4a` boxes absent), so an active track on it could only have been
   the music. What is *not* waited out on a device is a full pass of two four-minute
   tracks: that the ticked tracks play in the order they are listed, and loop, is pinned
   by `SlideshowMusicTest` (the selection is stored in catalogue order, whatever order it
   was ticked in) and by the player's `REPEAT_MODE_ALL` over that playlist.
8. **The three viewer rules that only show up against a slow or mixed library** —
   `__control/slow-media?ms=30000&sizes=o` with the image cache emptied makes a photo take
   half a minute, which is the only way to see any of these. The black window is a spinner
   while that runs (`140`, `141`) and the picture replaces it with nothing left over
   (`142`). With the same delay and a show running, the corner is *empty* while the show's
   own next photo is in flight (`143-slideshow-no-arrows.png`) and shows an arrow the
   moment a key is pressed (`144-manual-press-arrows.png`) — one press, one arrow, from a
   queue that held both. And with 顺序 set, stepping through a month whose clip sits at
   `4 / 16` reads `1 → 2 → 3 → 5 → 6 …`: the clip is walked over, and the position trace
   never once reads `VID_`. Stepping onto that clip by hand while the show runs does not
   stall it either — it gets one interval and the show is on the next picture.
   `SlideshowOrderTest` pins the same rules off the device, over every seed for the
   shuffled draw.

Two bugs were found only by testing the way a user actually would:

- **Credentials were never written to storage.** Auto-login silently never happened.
  Earlier runs passed only because the preferences had been seeded by hand — a good
  reminder that a hand-seeded fixture can hide a broken happy path.
- **A Chinese IME renders `.` as `。`.** Typing `10.0.2.2` could store
  `10。0。2。2`, which only connected because the URL stack's IDN nameprep happened to
  fold it back. The input is now normalised explicitly.

And one found by reporting a symptom rather than reading code:

- **Sign out, sign in again → `errno 401`.** Request ids embed a session id returned
  by login, but the generator is a process-wide singleton that outlived the
  sign-out, so the second handshake advertised the *previous* session's id. The mock
  server had silently accepted this because it ignored `reqid` entirely — a mock is
  only as strict as the fields it checks. It now enforces session-id freshness and
  `verify.js` asserts the rejection, so dropping `FnReqId.reset()` makes the flow
  fail loudly instead of regressing quietly. The login error also now includes the
  server's own wording, since a bare `errno 401` is not actionable.

And one found by pointing the app at a real NAS for the first time:

- **The device was gated by 访问码.** The WebSocket upgrade returned a bare `401`,
  which looks like a credential problem and is not. The gateway's
  `X-Trim-Safe-Code-Challenge` header gives it away, and the challenge page's own
  script reveals the `/access_code_verify` exchange. Support for it was implemented
  from that, so the app works *without* asking the user to weaken their NAS security —
  which would have been the lazy fix.

## Known limitations

- **The protocol is private and unversioned.** The signing salt/secret are global
  constants lifted from the fnOS frontend, and v1 and v2 endpoints already coexist.
  A server-side change can break this app. See
  [docs/fnos-photo-api.md](docs/fnos-photo-api.md) for the risk assessment and the
  WebDAV alternative.
- **The password is stored in plain `SharedPreferences`.** This is forced by the
  absence of any refresh-token flow: recovering from an expired `AccessToken`
  requires replaying the full login. For a LAN appliance app this is a deliberate
  trade-off, but it is worth knowing.
- **Read-only.** Browsing, viewing and slideshow are implemented; delete, upload,
  rename and favourites are not.
- **No pinch-zoom or pan** in the viewer.
- **Video playback does not use the app's certificate trust, so a clip on a NAS whose
  certificate is only pinned will not play.** Every other request — login, the JSON API,
  thumbnails, full-size photos — goes through OkHttp clients carrying
  `FnCertificateTrust`. The viewer's `VideoPlayer` builds its own `DefaultHttpDataSource`
  instead, which uses the *system* trust store, so a self-signed `O=fnOS CN=fnOS`
  certificate fails it with `Trust anchor for certification path not found` while the
  pictures on either side of it load perfectly. It would work if the certificate were also
  installed as a user CA (the app's `network_security_config` trusts `src="user"`), which
  is why this went unnoticed. The fix is to give the player the same client
  (media3's OkHttp data source over `AppContainer.mediaHttp`, which needs the
  `media3-datasource-okhttp` artifact). It is left alone here because the slideshow now
  walks past clips rather than depending on one playing.
- **A deletion deep inside a long list is noticed late.** The 30 s re-read is one page,
  so it can only vouch for its own window: a photo deleted below that window leaves the
  rotation on the pass that shrinks the list far enough for the window to reach it. A
  slideshow normally holds exactly one page — the viewer never pages in more — so in
  practice the window is the whole list; the gap needs a list longer than one page, which
  means a grid that was scrolled to the end before the viewer was opened.
- **Only tested against the mock server**, not a physical NAS. The protocol follows
  two shipping implementations, but a real device may still differ in details.
- The `POST /p/api/v1/photo/collect` favourites endpoint and the AI features
  (people, places, search) exist in the API but are not surfaced in the UI.

## Development toolchain

`tools/setup-toolchain.ps1` provisions a self-contained JDK 17 + Android SDK +
Gradle under `F:\android-toolchain`, so nothing is installed system-wide and the
whole thing can be deleted in one step. `tools/setup-emulator.ps1` and
`tools/create-avd.ps1` add an Android TV system image and an AVD.

One environment note worth recording: every Gradle distribution URL redirects to
`github.com`, which was unreachable from this network. The toolchain script uses the
Tencent mirror instead.
