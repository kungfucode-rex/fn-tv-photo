# AGENTS.md — orientation for agents and new contributors

Read this first. It is the shortest path to being useful in this repository, and it points at
the documents that hold the detail.

## What this is

Three products in one repository:

| | |
| --- | --- |
| `app/` | **FN Photo** — an Android TV app (Kotlin + Jetpack Compose for TV) that browses a photo library on a 飞牛 fnOS NAS through fnOS's own private API. Remote-first, landscape, minSdk 23. |
| `server/` | **图评 (tuping)** — a Go service that lets a guest scan a QR code on the TV and comment from a phone; comments drift back as danmaku. Its own `go.mod`; Gradle never touches it. |
| `tools/` | Build, publish, emulator, and the two mock servers the verification story depends on. |

## Commands

```powershell
& .\tools\build.ps1 -Tasks testDebugUnitTest              # compile the app + run JVM unit tests
& .\tools\build.ps1 -Tasks assembleDebug                  # debug APK (for the emulator)
& .\tools\build.ps1 -Tasks assembleRelease                # release APK (signed, published, writes version.json)
& .\tools\build.ps1 -Tasks testDebugUnitTest,assembleDebug -NoPublish   # the usual inner loop
& .\tools\tuping.ps1 -Tasks test                          # the Go service's tests
& .\tools\publish-test.ps1                                # bump version + debug build + publish to the share
```

Do **not** call `pwsh -File …` from inside this environment — invoke the script with `& .\tools\…`
instead. `build.ps1` sets `JAVA_HOME` / `ANDROID_HOME` / `PATH` against a self-contained toolchain
under `F:\android-toolchain` (see [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)).

Unit tests live in `app/src/test/java/com/tvphoto/`, are plain JUnit4, and deliberately avoid
Android APIs so they run on the JVM. Pure decision logic is extracted into `domain/` or into small
objects such as `data/SessionRecovery.kt` precisely so it can be pinned by a test.

## Where the thinking lives

| Document | Contents |
| --- | --- |
| [`README.md`](README.md) / [`README.en.md`](README.en.md) | Features and usage, for users. |
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | Structure, build, release, and the traps that bite. |
| [`docs/verification.md`](docs/verification.md) | How each feature was verified, with readings, plus every bug that testing found. |
| [`docs/fnos-photo-api.md`](docs/fnos-photo-api.md) | The reverse-engineered NAS protocol, with evidence. |
| [`docs/tuping-design.md`](docs/tuping-design.md) | 图评's protocol and decisions — the single source of truth for the service. |
| [`docs/custom-music-design.md`](docs/custom-music-design.md) | Your own slideshow music — the upload page and its QR server, the one list that is also the playlist, and what is deliberately not built. |
| [`server/README.md`](server/README.md) | Deploying the 图评 server. |

## Things a change must not break

- **The build is the test.** `testDebugUnitTest` only compiles; it does not repackage. Run
  `assembleDebug` too before installing anything. A TV's installer refuses a build with the same
  `versionCode`, so a debug build that is meant to be installed needs a bump — `publish-test.ps1`
  does that and then reads the version back off the share.
- **`app/src/main/assets/music/` and `SLIDESHOW_MUSIC` must agree in both directions**; a file in
  the folder that is not in the catalogue fails the build, on purpose. Music the *user* adds never
  goes in there — uploads land in the app's own storage directory, so that check stays about what
  the package carries.
- **The music library is one directory, and what 删除 may do depends on where a track lives.**
  `MusicSource` decides: an upload is a copy the app made and may delete, the bundled track is
  inside the APK so deleting it only takes it out of the list (reversibly, via `restoreBuiltins`).
  There is no scanning of the device or a USB stick — that was removed in 1.5.3, along with both
  storage permissions — so a file reaches the library by phone upload or by being copied into the
  app's own storage directory. The upload server listens only while its page is on screen, writes
  only into that directory, and its URL token is the only credential there is.
- **The two publish scripts stay pure ASCII.** Windows PowerShell decodes a `.ps1` as ANSI unless
  it has a UTF-8 BOM, so a literal Chinese character in `tools/build.ps1` or
  `tools/publish-apk.ps1` corrupts the script; the share's name is spelled in code points.
- **Strings resources:** a `'` inside a `<string>` makes the resource flattener read `\u` and fail
  the build with "Invalid unicode escape sequence". Write the sentence without the apostrophe.
- **One setting, one stored value.** The slideshow interval and order are editable from both the
  viewer's 幻灯片 row and Settings → Slideshow; both go through the same `MainViewModel` setter and
  read the same `StateFlow`. Never give a preference two stored copies.
- **A television has no console.** Failures that a user must act on have to be readable on screen —
  that is why the update row carries its reason, why 图评 has a status row, and why the last crash
  is printed in Settings. Prefer surfacing a reason to logging it and saying "failed".
- **The data layer carries no `Context`.** Protocol-level failures are locale-neutral technical
  strings; the UI adds a localised prefix (`R.string.error_prefix`).
- **Comments explain *why*, at the length the decision deserves.** This codebase's comments are
  unusually long and specific — they record measurements, rejected alternatives and bugs that
  actually happened. Match that style; do not replace a reason with a restatement of the code.
- **`FnHttp` clients are not interchangeable.** The NAS clients pin a self-signed certificate and
  attach an `AccessToken`; 图评 has its own client on purpose (see `AppContainer`), and
  `UpdateChecker` has a third that talks to Gitee and GitHub and must never be handed a NAS token.

## Verifying a change

Anything about what is on screen — layout, focus, whether a download happens, whether audio plays —
is proven on the Android TV emulator against `tools/mock-nas`, which enforces real auth rules and
has `__control/` switches for slow media, adding and deleting photos, and pinning the signature
rule. Protocol- and policy-level rules belong in `testDebugUnitTest` instead. Record what you
measured in [`docs/verification.md`](docs/verification.md), in the same voice as the existing
entries: numbered, with the reading that proves it.
