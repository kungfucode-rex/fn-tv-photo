package com.tvphoto.ui.music

import com.tvphoto.data.music.MusicEntry
import com.tvphoto.data.music.MusicSource

/**
 * One line of the viewer's track list.
 *
 * The list used to be exactly the catalogue, so a row index *was* a track. It now carries a
 * way in to the upload page as well, and a cursor that walked onto that row and treated OK
 * as a tick would be a press aimed at nothing. Hence a row type and two functions that know
 * which rows a cursor may land on — small, pure, and pinned by tests rather than by watching
 * a television.
 */
sealed interface MusicRow {
    /** Whether the cursor may stop here. */
    val selectable: Boolean

    data class Track(val entry: MusicEntry) : MusicRow {
        override val selectable: Boolean get() = true
    }

    /** `＋ 上传 / 管理背景音乐…`, which opens the upload page. */
    data object Manage : MusicRow {
        override val selectable: Boolean get() = true
    }
}

/**
 * Builds the list: the tracks in the order the library gives them, then the way in.
 *
 * No group headings, and that is the point of 1.5.1's order: the list *is* the playlist, so
 * a heading that claimed "these are bundled, those are yours" would be describing a
 * distinction the order no longer makes — and the numbers on the upload page count a single
 * sequence. Which track came from where is on the row itself, as a label.
 */
fun musicRows(entries: List<MusicEntry>): List<MusicRow> = buildList {
    entries.forEach { add(MusicRow.Track(it)) }
    add(MusicRow.Manage)
}

/**
 * The next row the cursor may land on, wrapping at both ends.
 *
 * Wrapping rather than clamping because that is what the list did when every row was a
 * track: holding down through the catalogue came back to the first song, and a cursor that
 * stopped dead on the last row would read as a broken key.
 */
fun nextSelectableRow(rows: List<MusicRow>, from: Int, delta: Int): Int {
    val selectable = rows.indices.filter { rows[it].selectable }
    if (selectable.isEmpty()) return 0
    if (from !in rows.indices || !rows[from].selectable) return selectable.first()
    val at = selectable.indexOf(from)
    return selectable[(at + delta).mod(selectable.size)]
}

/**
 * Where the cursor opens: the first ticked track, or the first row when nothing is ticked.
 *
 * The list is what the setting *is*, so it opens on the setting rather than always at the
 * top — the same rule it had when the catalogue was the whole list.
 */
fun firstTickedRow(rows: List<MusicRow>, ticked: Set<String>): Int {
    val at = rows.indexOfFirst { it is MusicRow.Track && it.entry.id in ticked }
    return if (at >= 0) at else rows.indexOfFirst { it.selectable }.coerceAtLeast(0)
}
