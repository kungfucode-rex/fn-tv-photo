package com.tvphoto.data.music

/**
 * The order the user arranged their music in, one id per line.
 *
 * The same shape as everything else stored here — a flat list, one value per line, written
 * by this app and readable in a bug report — and the same two rules: blank lines and
 * duplicates are dropped, and nothing else is validated. An id whose file is gone is
 * skipped when the order is *applied*, not when it is read, because reading a preference
 * must not depend on a mounted volume.
 */
internal fun encodeMusicOrder(ids: Collection<String>): String = ids
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .joinToString("\n")

/** Reads back what [encodeMusicOrder] wrote. */
internal fun decodeMusicOrder(raw: String?): List<String> = raw
    .orEmpty()
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()
