import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * One spelling of the version. It reaches the manifest, the settings screen (through
 * BuildConfig.VERSION_NAME) and the APK's file name, so bumping it here also renames
 * the artifact that gets published.
 */
val appVersionName = "1.4.11"
android {
    namespace = "com.tvphoto"
    compileSdk = 36

    defaultConfig {
        // The id an install is known by, and the one a forum post quotes. It carries
        // the author's own prefix; `namespace` stays com.tvphoto, which is where the
        // code and the R/BuildConfig classes live, so only the packaged id moves. A
        // new id is a different app to Android: it installs *beside* the old one and
        // starts from empty settings.
        applicationId = "com.kungfucode.fntvphoto"
        minSdk = 23
        targetSdk = 36
        // 3 carried the rename from TV Photo to FN Photo; 4 was the first build of the preview
        // tier work and 5 the one it ships as, because a TV's installer refuses to lay a build
        // over another with the same version code — 1.3 could not be installed over 1.3.
        // 6 is 1.3.2, whose one new thing is the update check; it also has to move for that
        // check to be testable, since the installer compares this number rather than the name.
        // 7 is 1.3.3, identical to 1.3.2 but for the number: it exists so an install of 1.3.2
        // has something to find.
        // 8 is 1.3.4, which puts the reason a check failed on the row instead of only in the
        // log - a television has no console, and "检查失败" on its own is not a diagnosis -
        // and asks Gitee as well as GitHub, so a network that cannot reach one of them is
        // no longer a network that cannot check.
        // 9 is 1.3.5, 1.3.4 with the window between the two sources widened to three
        // seconds; it is also the release a 1.3.4 install has to find, so that the Gitee
        // path is exercised end to end on a television rather than only in a terminal.
        // It was republished unchanged in number after the signature check it shipped with
        // turned out to refuse genuine updates - see the note in ApkInstaller.signingFlags.
        // 10 is 1.3.6, identical to 1.3.5 but for the number: the release a 1.3.5 install
        // has to find, which is how the repaired check is proved on a television.
        // 11 is 1.3.7, which drops the Settings row for the slideshow interval - the viewer
        // has always had the same control - and cuts the bundled music from nine tracks to
        // four, taking the package from 66 MB to 26 MB.
        // 12 is 1.3.8, the first build with 图评 (photo review): a QR code in the corner of a
        // full-screen photo, a danmaku layer over it, and six Settings rows for the server,
        // the upload scope, the danmaku level and the connection status. The number has to
        // move for the simple reason every earlier bump did: a television's installer
        // refuses to lay a build over another with the same versionCode, so the one build
        // that most needs installing over the previous one would be the one that could not.
        // 13 is 1.3.9, the 图评 build after living with it on a television for an evening.
        // Almost everything in it came from somebody using the thing rather than from the
        // design: a 图评 button in the viewer's corner that owns the code, the danmaku and
        // how much of it; the arrows in the viewer routed back to 1.3.7's habit (left/right
        // along a row, up/down for its value, down to close); the folder list given a grid
        // and a toggle; the 评论 N pill that read as a stuck toast removed; and the corner
        // code and its caption made translucent, which needed the *server* to stop painting
        // an opaque white quiet zone.
        //
        // 14 is 1.4.0, and it exists because 13's APK was published and then made wrong by
        // the same afternoon: the translucency went too far (0.62 now, was 0.28) and the
        // server grew a warning for the base-URL mistake that had been handing guests links
        // they could not open. A published build whose behaviour has changed needs a new
        // number, or somebody installs 1.3.9 twice and gets two different applications.
        //
        // 15 is 1.4.1: the QR corner moved to the right (where a camera's own watermark is
        // not), and the app now tells the server which address it reaches it on, so a code
        // points at the public address the TV was actually configured with instead of
        // whatever TUPING_BASE_URL happened to say. That also fixed a first-install bug
        // where the photo upload went out with the empty device token the client had been
        // built with, one line before `ensureDevice` minted a real one.
        // 16 is 1.4.2, which repairs the one state 图评 could not get out of. A server that
        // has been reinstalled, restored from an older backup, or reached at a new address
        // answers /healthz — so the settings row reads 已连接 — while refusing every request
        // that carries the device token this television still holds; the viewer's 图评 button
        // then greys out over a "failed" status, which put its menu, and the switches in it,
        // out of reach with nothing on screen to explain why. A refused token is now answered
        // by registering again, the status row checks both halves of "connected" instead of
        // only whether the server answers, and a failed call no longer disables the button.
        // The number has to move for the same reason every earlier bump did — a television's
        // installer refuses to lay a build over another with the same versionCode, so the
        // build that fixes this would be the one that could not be installed.
        // 17 is 1.4.3, which is the danmaku layer after it was finally watched on a
        // television with a real comment on it. Four things were wrong or wanted, and the
        // first explains half the list: the layer drew its text at `level.textPx` *sp* while
        // sizing the box around it in *pixels*, so on a 320dpi screen every line was drawn
        // twice the intended size inside half the width it needed — "这是在干什么呢" arrived
        // as "这是…". The text is now converted through the density, measured with a
        // TextMeasurer instead of estimated, the speed is 260 px/s rather than 320, the
        // sizes are 34/40 px rather than 38/44, and the layer loops through a photo's
        // comments with a five-second floor on the same one coming round again.
        // 18 is 1.4.4, which fixes what living with 1.4.3's danmaku turned up on the first
        // evening: a comment about one photo crossed the *next* one when the viewer paged
        // to it. A page turn moves the photo on the frame the button is pressed while the
        // review state catches up an effect later, and for that frame the layer was handed
        // the previous photo's comments under the new photo's key — the replay offered one
        // and a line crosses for seven seconds. The same window drew the previous photo's
        // QR code over the next picture, one frame wide, which is a code that comments on
        // the wrong photograph. Both are the same fix: an answer whose identity is not the
        // photo on screen counts as no answer at all.
        // 19 is 1.4.5, where the slideshow stopped freezing on a slow photo. Playing three
        // pictures and then sitting on the fourth until the media client's 60-second read
        // timeout was one bug with two halves: a step onto a photo whose original had not
        // been fetched waits for it with no ceiling, and the prefetch window — the thing that
        // would have fetched it — stands down for exactly as long as the step is in flight,
        // so the photos behind it were never warmed either ("后面的图片也不会自动加载"). A
        // step now settles for the thumbnail tier after eight seconds and says so on screen,
        // and the window keeps running while a show is playing, because a show is when
        // warming ahead has to keep running.
        // 20 is 1.4.6, which is the danmaku layer's second pass over the same evening:
        // 再慢一点 (220 px/s, from 260) and the track rule. `freeTrack` used to take the
        // lowest track that was *either* empty or merely reusable, so once the first line had
        // cleared the entry point every following one — and the loop offers one every 1.2
        // seconds — went to track 0 as well: six tracks, one row of text queued up down a
        // single line of the photograph ("分3、4行错开，现在是一行，文字重叠了"). An empty track
        // now wins, and the drawn line no longer carries a one-width horizontal offset that
        // the feed's geometry did not know about — which is why a line used to vanish with a
        // third of itself still on screen.
        // 21 is 1.4.7, which takes back one half of 1.4.5. That change let the prefetch window
        // keep running while a slideshow step was in flight, on the theory that a show is when
        // warming ahead matters most; the effect was that the step's own original download now
        // shared the wire with four prefetch streams and lost the race, so the eight-second
        // ceiling (the other half, which stays) handed the room a thumbnail instead —
        // reported as "好多原图都一直加载不出来". The freeze that 1.4.5 was fixing is what the
        // ceiling fixes; the pipeline does not need the window held over it to keep moving.
        // 22 is 1.4.8, which is one afternoon's worth of reports from a television and a
        // phone, none of them from the code. 图评 could be switched off by a byte nobody can
        // see: the review address was stored with a NUL on the end, `trim()` does not remove a
        // NUL (it is not whitespace), `HttpUrl` then refused to parse it, and the layer hid
        // itself — button grey, no code, no danmaku — while the settings row drew an address
        // that looked perfectly good. Reported as "图评服务器为什么连不上". The address is now
        // cleaned on the way into the store and on the way out, so a value an older build
        // stored badly heals by being read rather than by being retyped on a remote.
        // A slideshow over a shared album's 12 MB originals ran on thumbnails for as long as
        // it was left playing: the eight-second ceiling cancelled the step's *own* download,
        // the prefetch window restarted at the photo already on screen, and the shuffled order
        // made a positional window useless — and about seven minutes in, the app died with a
        // Java-heap OutOfMemoryError on OkHttp's reader thread. The ceiling now follows the
        // interval (4-12 s), a late original keeps downloading behind the thumbnail and swaps
        // in when it lands, and a running show's window covers the whole list. The danmaku
        // laid out one row per comment ("5 个评论就会用 5 行", then "还是没有任何变化" when the
        // first fix still gave a comment a row of its own): the rows are now the 少/多
        // setting's ceiling — two or four — taken in turn and packed inside the top quarter or
        // the top two fifths of the screen, with 200px between two comments sharing a row. The
        // comment length the server accepts came down with them, from 80 characters to the 20
        // a line can actually show (that half is the server's, not this APK's).
        // 23 is 1.4.9, which fixes a show that could not leave the first page. The viewer is
        // handed whatever list the grid had loaded - one page, sixty photos - and nothing ever
        // asked for the rest: an album of 217 played its first sixty and began again at the
        // first, with the remaining photos never requested at all, which is also why their
        // thumbnails were not there to see ("播放五六十张之后，又从第一张开始播放。后边其实还有
        // 很多照片"). The grid asks for its next page when its last rows come into view; a viewer
        // has no scroll position, so `needsNextPage` is that same rule - eight from the end -
        // plus the one case position cannot decide: a running show draws its next photo from
        // anywhere in the list, so the album is loaded as fast as the server answers it. On the
        // emulator the list went from 120 to 223 items (217 photos and 6 videos) within three
        // seconds of the show starting, and the show then carried on to 192 / 223 instead of
        // looping at 60.
        // 24 is 1.4.10, which is the same day's second half and a *test* build rather than a
        // release: two things reported from a television and a phone, plus the one that made
        // them worth looking at.
        // (1) A comment deleted on the H5 page kept crossing the photo it was written about.
        // The live socket only ever carried *arrivals*, so a comment that changed - edited,
        // held, blocked, deleted - never reached a television that already held it, and the
        // client only ever appended to its copy anyway. The socket now carries a comment's
        // *state* and the client replaces the copy it holds by id, which is what stops a
        // deleted line being replayed. A line already crossing finishes its pass.
        // (2) A preview whose original was already in the image cache was still drawn as the
        // thumbnail while paging by hand. The wait is the only reason that default exists, and
        // a file already on disk has no wait: the viewer asks the disk about the photo on
        // screen and the eight behind it, draws the original where the answer is yes, and
        // swaps the one on screen over on the hidden layer, so nothing goes black. 否 is
        // deliberately not overridden - it says what is *drawn*, and it is how a box that
        // cannot afford a 12-megapixel decode opts out.
        // (3) The one that is the server's rather than this APK's: the H5 comment page grew an
        // admin login, with the TUPING_ADMIN_TOKEN as its password and 编辑 / 删除 under every
        // comment. Change (1) is what makes those reach a television.
        // 25 is 1.4.11, which is the same evening's report and a *test* build: "我构建了新的
        // server 端，扫码后提示这张照片不在图片数据库里". The rebuilt server was not in
        // question. Its /healthz said uptime 331 s and thumbBytes 0 - a data directory with
        // nothing in it - the television's log said its device token was refused at 11:52:46,
        // and both short codes it was holding answered 404 when fetched. What was wrong is
        // that the repair written for exactly this state (1.4.2, "a server that does not know
        // this device") re-registered the *device* and then kept every cached *photo*: its
        // comment said so on purpose, on the theory that a photo registered again by the new
        // device comes back as the same photo. That is true while the server still holds the
        // rows, and the whole reason the repair exists is the case where it does not. The
        // viewer therefore went on drawing a code the new server had never issued, the live
        // socket for the same cached id answered 404, and nothing on screen or in the app
        // could get out of it short of a restart.
        // A repair now drops everything the old server answered - identities, comments and
        // the QR PNGs on disk - and registers the photo on screen again, so a working code
        // replaces the dead one. The same conclusion is also reachable without a refusal: a
        // history read that comes back not_found means the server has no row for that photo,
        // which drops that one identity and registers it again (once per photo, because a
        // server that 404s a photo it has just been handed must not become a registration
        // loop). The decision lives in DeviceRepair now, where a local unit test can drive it
        // against real sockets: 6 cases in ReviewRecoveryTest, including the two that cost
        // this build its afternoon - a repair reports the server replaced exactly once, and a
        // burst of refused calls still mints exactly one device.
        versionCode = 25
        versionName = appVersionName
    }

    androidResources {
        // Only the languages the UI actually ships with.
        localeFilters += setOf("en", "zh")
    }

    signingConfigs {
        create("tvphoto") {
            storeFile = file("tvphoto.jks")
            storePassword = "tvphoto"
            keyAlias = "tvphoto"
            keyPassword = "tvphoto"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the project keystore so the APK can be sideloaded onto a TV.
            signingConfig = signingConfigs.getByName("tvphoto")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/INDEX.LIST",
        )
    }

    lint {
        abortOnError = false
    }
}

/*
 * The release APK is named after the app and its version, not after the Gradle variant.
 *
 * `app-release.apk` says nothing about which app it is once the file is sitting in a TV's
 * download folder beside other sideloaded packages. `FN-tvphoto-1.3.1.apk` carries the
 * product name, the half of the applicationId that belongs to the app (`fntvphoto`), and
 * the version - so a folder holding two of them still says which is which.
 *
 * The debug build keeps AGP's own name: tools/tv.ps1 installs it on the emulator and
 * nobody is ever handed it. tools/publish-apk.ps1 and tools/tv.ps1 find the APK by the
 * same pattern, so a rename here means renaming there too; the README "安装 / Install"
 * section quotes the name as well.
 *
 * The cast is load-bearing. AGP 8.13 declares no `outputFileName` on the public
 * VariantOutput interface - only the impl class has the setter - so an AGP that moves it
 * fails to compile here rather than quietly shipping the default name again.
 */
androidComponents {
    onVariants { variant ->
        if (variant.buildType == "release") {
            variant.outputs.forEach { output ->
                (output as com.android.build.api.variant.impl.VariantOutputImpl)
                    .outputFileName.set("FN-tvphoto-$appVersionName.apk")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/*
 * Dependency versions are pinned rather than ranged.
 *
 * This SDK ships platform 36 and nothing higher, so every Android library has to
 * declare `minCompileSdk <= 36`. Several current releases (Compose 1.12, core 1.19,
 * lifecycle 2.11, Coil 3.6, OkHttp 5.5) require compileSdk 37 and Android Gradle
 * Plugin 9.1+, which cannot be satisfied here. tools/check-deps.ps1 reads that
 * requirement straight out of each AAR's metadata, and these are its newest picks
 * that still build against platform 36.
 */
dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Compose 1.11.0 via the BOM. The TV libraries below request 1.10.3, so the
    // BOM raises them to 1.11.0 and nothing reaches the compileSdk-37 line.
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.1.0")

    // Image loading. Coil shares the token-bearing OkHttp client.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")

    implementation("com.squareup.okhttp3:okhttp:5.2.3")

    // Video playback for the video entries that live in the NAS gallery.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in JVM unit tests: every method throws
    // "not mocked". The real implementation is needed to test response parsing.
    testImplementation("org.json:json:20231013")
}
