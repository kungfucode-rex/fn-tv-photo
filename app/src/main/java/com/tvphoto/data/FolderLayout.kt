package com.tvphoto.data

/**
 * How the 文件夹 section lays its folders out.
 *
 * [GRID] is the default, by request: a managed folder is a place with a name and a count,
 * and four of them across a 1080p screen is a shape you can take in at a glance. The
 * original single-column list spent a full-width bar per folder on two lines of text and a
 * number, which on a television reads as a mostly-empty row rather than as a folder.
 *
 * The list is kept because a long folder path is truncated in a grid cell and complete in a
 * row, and a library with similarly-named folders is easier to scan one per line.
 *
 * Stored by [id] rather than by ordinal, like `ThemeMode` and `PreviewOriginal`, so that
 * inserting an option cannot silently change what an existing install is set to.
 */
enum class FolderLayout(val id: String) {
    GRID("grid"),
    LIST("list"),

    ;

    /** The other one, for the toggle button. */
    fun toggled(): FolderLayout = if (this == GRID) LIST else GRID

    companion object {
        val DEFAULT = GRID

        fun fromId(id: String?): FolderLayout? = entries.firstOrNull { it.id == id }
    }
}
