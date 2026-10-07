package com.tvphoto.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Locale-aware month heading, e.g. `2026年10月` or `October 2026`. */
fun formatMonthLabel(year: Int, month: Int, locale: Locale = Locale.getDefault()): String {
    val calendar = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, 1)
    }
    val pattern = if (locale.language.startsWith("zh")) "yyyy年M月" else "MMMM yyyy"
    return SimpleDateFormat(pattern, locale).format(calendar.time)
}

/**
 * Month only, for the timeline's month cards: `10月` or `Oct`.
 *
 * Short enough to sit above a thumbnail. The English side still goes through
 * `SimpleDateFormat` rather than a hand-written table, because month names are
 * locale data.
 */
fun formatMonthShort(year: Int, month: Int, locale: Locale = Locale.getDefault()): String =
    if (locale.language.startsWith("zh")) {
        "${month}月"
    } else {
        SimpleDateFormat("MMM", locale).format(
            Calendar.getInstance().apply {
                clear()
                set(year, month - 1, 1)
            }.time,
        )
    }

/** Locale-aware day heading, e.g. `2026年1月30日 星期五` or `Friday, Jan 30, 2026`. */
fun formatDayLabel(year: Int, month: Int, day: Int, locale: Locale = Locale.getDefault()): String {
    val calendar = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day)
    }
    val pattern = if (locale.language.startsWith("zh")) {
        "yyyy年M月d日 EEEE"
    } else {
        "EEEE, MMM d, yyyy"
    }
    return SimpleDateFormat(pattern, locale).format(calendar.time)
}

/** Compact heading for tight rows, e.g. `1月30日`. */
fun formatDayShort(year: Int, month: Int, day: Int, locale: Locale = Locale.getDefault()): String =
    if (locale.language.startsWith("zh")) {
        "${month}月${day}日"
    } else {
        SimpleDateFormat("MMM d", locale).format(
            Calendar.getInstance().apply {
                clear()
                set(year, month - 1, day)
            }.time,
        )
    }

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) {
        "${bytes}B"
    } else {
        String.format(Locale.US, "%.1f%s", value, units[index])
    }
}

/**
 * Renders the `dateTime` field the gallery returns (`2026-01-30 17:45:04`) as a
 * short local time, falling back to the raw value when it cannot be parsed.
 */
fun formatTakenAt(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    val normalised = raw.replace('T', ' ').substringBefore('.')
    val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm")
    for (pattern in patterns) {
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.US).parse(normalised)
        }.getOrNull()
        if (parsed != null) {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { time = parsed }
            val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
            val locale = Locale.getDefault()
            val outPattern = when {
                locale.language.startsWith("zh") && sameYear -> "M月d日 HH:mm"
                locale.language.startsWith("zh") -> "yyyy年M月d日 HH:mm"
                sameYear -> "MMM d, HH:mm"
                else -> "MMM d yyyy, HH:mm"
            }
            return SimpleDateFormat(outPattern, locale).format(parsed)
        }
    }
    return normalised
}

/** `2.3 MB` style caption for the info overlay. */
fun mediaSubtitle(width: Int, height: Int, fileSize: Long): String {
    val parts = mutableListOf<String>()
    if (width > 0 && height > 0) parts += "${width}×${height}"
    val size = formatFileSize(fileSize)
    if (size.isNotEmpty()) parts += size
    return parts.joinToString(" · ")
}

/** Formats an elapsed duration as `mm:ss`, used by the video controls. */
fun formatDuration(millis: Long): String {
    val totalSeconds = abs(millis) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
