package com.tvphoto.data

/**
 * Which colour scheme the whole app is drawn in.
 *
 * Dark is the default and stays the default: this is a photo browser for a dim room,
 * and every surface exists to defer to the photograph. Light is offered because a
 * bright room — a kitchen, a daytime living room — turns a dark UI into a mirror, and
 * because the TV is not always the only thing the user is looking at.
 *
 * Stored by [id] rather than by ordinal so that reordering or inserting a mode cannot
 * silently change what an existing install is set to.
 */
enum class ThemeMode(val id: String) {
    DARK("dark"),
    LIGHT("light"),
    ;

    /** The other mode; the setting has two values, so either direction is a toggle. */
    fun next(): ThemeMode = if (this == DARK) LIGHT else DARK

    companion object {
        val DEFAULT = DARK

        fun fromId(id: String?): ThemeMode? = entries.firstOrNull { it.id == id }
    }
}
