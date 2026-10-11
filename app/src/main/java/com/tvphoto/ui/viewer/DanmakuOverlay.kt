package com.tvphoto.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.tvphoto.data.review.DanmakuFeed
import com.tvphoto.data.review.DanmakuLevel
import com.tvphoto.data.review.DanmakuSpec
import com.tvphoto.data.review.ReviewComment
import kotlinx.coroutines.delay

/**
 * The danmaku layer.
 *
 * Three decisions make this cheap enough for a low-end TV box, and the design names all
 * three (docs/tuping-design.md §9.5):
 *
 *  1. **One frame clock drives every line.** A `withFrameNanos` loop writes a single
 *     `MutableState<Long>`; each line reads it inside `graphicsLayer`. That block is a
 *     draw-phase block, so a moving line causes neither recomposition nor relayout — the
 *     naive version, which reads the clock in a composable and passes `translationX` as an
 *     argument parameter, recomposes the whole overlay sixty times a second.
 *  2. **Each text is measured once and cached.** `TextMeasurer` is not free, and measuring
 *     on every frame costs more than the photo underneath.
 *  3. **Nothing here is focusable.** The layer has no focus modifier, no `clickable` and no
 *     key handler: a D-pad press belongs to the photo, and this is what
 *     "弹幕层绝不抢遥控器焦点" means in practice.
 *
 * The clock stops the moment nothing is moving. An idle photo must not keep a 60 Hz
 * wake-up alive on a box that is meant to sit there for hours.
 */
@Composable
fun DanmakuOverlay(
    comments: List<ReviewComment>,
    level: DanmakuLevel,
    /** Changes whenever the photo does, so the layer resets with it. */
    photoKey: Any?,
    modifier: Modifier = Modifier,
) {
    if (level == DanmakuLevel.OFF) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        val feed = remember(level, widthPx) { DanmakuFeed(level, widthPx) }

        // **The size is converted through the density, and this is not a detail.**
        //
        // The design's sizes are in pixels *at 1080p* — "38px, readable without shouting" —
        // and every other number in this layer is in screen pixels too: the speed, the track
        // heights, the band, the 45% ceiling. Using the number as `sp` instead, which is what
        // this did first, draws 38sp on a 320dpi television, and that is 76 pixels: twice the
        // intended size, and twice the width the feed had reserved for the line. What a guest
        // saw was the first two characters of their own comment and an ellipsis — "这是…" for
        // "这是在干什么呢" — because the box was half the width of the text in it.
        val textSize = with(density) { level.textPx.toSp() }

        // The same style for every line: white with a black outline. A photograph has no
        // fixed background of its own, so an outline is the only thing readable on all of
        // them.
        val style = remember(textSize) {
            TextStyle(
                fontSize = textSize,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                shadow = Shadow(color = Color.Black, offset = Offset.Zero, blurRadius = OUTLINE_BLUR_PX),
            )
        }

        // Each line's width, measured once and cached for the crossing (§9.5).
        //
        // This used to be left to `ReviewComment.widthPx`, which *estimates*: one em per CJK
        // character, 0.55 for Latin. The estimate is what the box was sized from, and a box
        // narrower than the text in it is a line that ends in an ellipsis — for a run of
        // wide Latin letters (a `W` is nearly a full em, not 0.55) it still would. Measuring
        // is exact for any script, and `TextMeasurer` caches by text and style, so the cost
        // is one layout per comment per photo.
        val measurer = rememberTextMeasurer()
        val measuredWidths = remember(comments, style) {
            comments.associate { comment ->
                comment.id to measurer.measure(
                    text = comment.content,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    constraints = Constraints(maxWidth = Constraints.Infinity),
                ).size.width.toFloat()
            }
        }
        fun widthOf(comment: ReviewComment): Float =
            measuredWidths[comment.id] ?: comment.widthPx(level.textPx)

        // The thread this photo has, once: the replay and the loop below are both built from
        // this one list. (How many rows it is laid out in is not — that is the 少/多 setting,
        // and the feed has it.)
        val visible = comments.filter { it.status == DanmakuFeed.VISIBLE_STATUS }

        // Bumped whenever the feed takes a line, and the frame clock below is keyed on it.
        //
        // The clock stops when nothing is moving — that is the rule that keeps a photo from
        // waking a box sixty times a second for hours — but the loop that offers comments
        // changes nothing the composition can observe. Without this tick, a line offered
        // while the layer was idle would sit just off the right edge and never move, and its
        // track would stay occupied for good.
        var lineTick by remember(feed) { mutableIntStateOf(0) }

        // A new photo clears the layer: a comment about the previous picture sliding over
        // the next one is simply wrong.
        LaunchedEffect(photoKey) { feed.reset() }

        // How many rows the band may use is the 少/多 setting's ceiling — two or four — and it
        // needs no effect here: the feed takes it from the level it was built with, and the
        // level is one of the keys it is remembered on, so switching 少/多 rebuilds it.

        // The opening replay: the last few comments, staggered, so arriving on a photo shows
        // that somebody has said something about it without dumping a wall of text.
        LaunchedEffect(photoKey, comments) {
            val replay = visible.takeLast(level.replay)
            replay.forEachIndexed { index, comment ->
                if (index > 0) delay(index * DanmakuFeed.REPLAY_STAGGER_MS)
                if (feed.offer(comment, nowNanos(), widthOf(comment))) lineTick++
            }
        }

        // The loop: for as long as this photo is on screen, the comments it has keep
        // crossing it, one per beat, round-robin. The design's replay-only rule left a photo
        // that stays up for a minute silent after the first few seconds — and a guest who is
        // still reading deserves to see the thread again. The feed owns the floor: nothing
        // comes round again inside DanmakuFeed.REPEAT_COOLDOWN_MS, which is what makes a
        // one-comment photo a slow repeat rather than a stutter.
        LaunchedEffect(feed, photoKey, comments) {
            if (visible.isEmpty()) return@LaunchedEffect
            var index = 0
            while (true) {
                delay(DanmakuFeed.REPLAY_STAGGER_MS)
                val comment = visible[index % visible.size]
                index++
                if (feed.offer(comment, nowNanos(), widthOf(comment))) lineTick++
            }
        }

        var clock by remember(feed) { mutableLongStateOf(nowNanos()) }

        LaunchedEffect(feed, photoKey, comments, lineTick) {
            while (true) {
                var moving = false
                withFrameNanos { frameTime ->
                    clock = frameTime
                    moving = feed.advance(frameTime)
                }
                if (!moving) break
            }
        }

        val band = DanmakuSpec.band(heightPx, level.tracks)
        // The band is the share of the screen this setting allows — the top quarter on 少, the
        // top two fifths on 多 — and each line is centred in its own row inside it, so two rows
        // sit across the top of the picture instead of down its middle. See
        // DanmakuSpec.bandFraction.
        val rowPitch = DanmakuSpec.trackPitchPx(heightPx, level.tracks)
        val lineHeight = DanmakuSpec.lineHeightPx(level.textPx, heightPx)

        feed.onScreen.forEach { line ->
            DanmakuLine(
                line = line,
                clock = clock,
                style = style,
                screenWidthPx = widthPx,
                topPx = DanmakuSpec.trackTop(line.track, rowPitch, band.start, lineHeight),
            )
        }
    }
}

/**
 * One line, at a fixed height and a horizontal position that only the draw phase changes.
 *
 * The text is measured once per comment — `remember(key)` is what makes that true for the
 * whole crossing — and the width it reported is what the line's declared box is, so the
 * box does not have to be re-derived when the position moves.
 */
@Composable
private fun DanmakuLine(
    line: DanmakuFeed.Active,
    clock: Long,
    style: TextStyle,
    screenWidthPx: Float,
    topPx: Float,
) {
    val density = LocalDensity.current
    val widthDp = with(density) { line.widthPx.toDp() }
    val topDp = with(density) { topPx.toDp() }

    Text(
        text = line.comment.content,
        style = style,
        maxLines = 1,
        softWrap = false,
        // The ellipsis lands where the design's 45% ceiling falls, which is what makes the
        // longest allowed line take 12.7 seconds rather than fourteen or fifteen.
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            // **No horizontal offset.** The feed's geometry — [DanmakuFeed.Active.leftX],
            // `finished`, the track gap, and the stay extension's crossing time — all treat
            // `leftX` as the line's left edge. Drawing it one line-width further right (which
            // this did) meant a line was retired while a third of it was still on screen: it
            // vanished mid-crossing, and the model's tail was a line-width behind the pixels.
            // The line is off-screen at `leftX == screenWidth` as it is.
            .offset(y = topDp)
            .graphicsLayer {
                // The only per-frame value, and the only place it is read. `graphicsLayer`'s
                // block is a draw-phase block, so this moves the line without recomposing
                // or relaying anything out.
                translationX = line.leftX(clock, screenWidthPx)
            }
            .width(widthDp),
    )
}

/**
 * The QR code with its one line of explanation, bottom-left, above the information bar.
 *
 * **The label is not decoration.** A code on its own is a puzzle: the design puts it in the
 * corner of a photograph, where a guest has no reason to believe it is for them or what it
 * would do. One line under it — 扫码评论这张照片 — is the difference between "there is a
 * square on the TV" and somebody actually pointing a phone at it. It is passed in rather
 * than written here so the wording stays in the string resources with every other line.
 *
 * **The quiet zone comes from the server's PNG, and nothing is added here.** `qr.go`
 * renders the code with `QuietZone = 4` modules baked in — measured on a real response,
 * a 440px PNG has 12px modules and its first dark pixel sits at (48,48), exactly four
 * modules in. The first version of this composable *also* padded the image inside the
 * plate, and the two margins stacked: the plate ended up about eight modules of white
 * wide around the code, which is what "白边巨宽" was. There is one quiet zone, and the
 * server owns it.
 *
 * The white plate is still here, and it is nearly free: the PNG's own quiet zone is the
 * outermost thing in the image, so scaling it to fill the plate leaves the plate's edge
 * white whatever is behind it. That is what keeps a code drawn over a bright photograph
 * from losing its finders — the plate does not have to be larger than the code for that.
 *
 * **The plate is no longer opaque white; it is a light veil over the photograph.** Pure
 * white was asked to be toned down because a solid white rectangle in the corner of a
 * photograph reads as a hole punched in the picture. What the scanner needs is not
 * whiteness but a *luminance step*: dark modules against a lighter surround, with the
 * four-module quiet zone intact. A veil keeps that step over dark and mid-tone pictures
 * and reduces it over pale ones.
 *
 * That is a real trade and it is the one thing about this composable that can break
 * scanning: over a photograph that is brighter than the veil, the dark modules and the
 * quiet zone converge and the finders stop resolving. Measured against the two brightest
 * surfaces this app draws — a white sky, and the white information band — the veil is
 * still the darker of the two. If a code in this style ever fails to scan, this constant
 * is what to change, and opaque white is the fallback that is known to work.
 *
 * Static on purpose. A breathing or fading code is a code that cannot be scanned: the
 * whole point of the corner is that a phone can be pointed at it for as long as it takes.
 * There is no animation in this composable, and adding one would be a bug.
 */
@Composable
fun ReviewQrCode(
    pngFile: java.io.File?,
    label: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = QR_SIZE_DP,
) {
    if (pngFile == null) return

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(sizeDp.dp)
                .clip(RoundedCornerShape(6.dp))
                // `Color.White.copy(alpha = …)` rather than a grey: alpha composites the
                // photograph underneath back in, so the corner takes its colour from the
                // picture instead of sitting on it as a grey card. See the note above on
                // what this costs a scanner.
                .background(Color.White.copy(alpha = PLATE_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            coil3.compose.AsyncImage(
                model = pngFile,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (label.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            // White on a dark veil rather than plain white text. A photograph has no fixed
            // background and this line sits under the code over whatever happens to be
            // there — but the veil is translucent for the same reason the plate is, so the
            // two read as one piece of glass rather than a white square above a black bar.
            // The white glyphs keep their contrast against the photograph underneath, which
            // is what a solid dark bar was buying and no longer needs to.
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 17.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = LABEL_ALPHA))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** The caption's veil. See the note where it is used. Raised for the same reason the plate was. */
private const val LABEL_ALPHA = 0.55f

/**
 * A frame timestamp, in nanoseconds.
 *
 * `System.nanoTime` rather than the Compose clock: the replay effect runs before the first
 * frame, and the feed only ever compares stamps with each other, so any monotonic source
 * works as long as it is the same one.
 */
private fun nowNanos(): Long = System.nanoTime()

/**
 * The code's size, in dp.
 *
 * **96dp, which is well under the 200px §9.1 asks for as a floor** — half the 160dp this
 * started at, which was itself already below it. The design's number comes from the
 * "scannable from three metres" acceptance criterion; on a real television the code read
 * as a panel competing with the photograph rather than as a corner mark. Two rounds of
 * "still too big" later, this is the size that was asked for, and the person asking is
 * the one looking at the screen.
 *
 * What it costs is only measurable with a phone, and §15.2's experiment 4 (scan at
 * 2 / 3 / 4 metres with 160 / 200 / 240px canvases) has never been run. Measured on the
 * emulator at 1080p for the record: the plate is 192×192 px (17.8% of the screen height)
 * and one module is about **5.8 px**. If a code this size cannot be scanned from where a
 * guest sits, this is the one number to raise — and the `ReviewQrCode(sizeDp = )`
 * parameter exists so it can be raised from the call site without touching this file.
 */
private const val QR_SIZE_DP = 96

/**
 * How much white the code sits on.
 *
 * Three values have been tried, and the useful range is narrower than it looks:
 *
 *   - `1.0` — an opaque white card. Read as a hole punched in the photograph.
 *   - `0.28` — the photograph came through clearly, and the code nearly vanished into a
 *     dark picture. Too far the other way: a code nobody can *find* is not a code.
 *   - `0.62` — this. The veil is plainly lighter than what is behind it on every kind of
 *     photograph, and a bright sky still tints the corner rather than being covered by it.
 *
 * The floor is set by findability rather than by the scanner: a phone has to notice the
 * corner before it can read it, and at 0.28 the plate was closer to the picture than to
 * white. `1f` restores the version known to scan; see the note on [ReviewQrCode].
 */
private const val PLATE_ALPHA = 0.62f

/** How far the black outline bleeds, in pixels. Three at 1080p, as the design asks. */
private const val OUTLINE_BLUR_PX = 3f
