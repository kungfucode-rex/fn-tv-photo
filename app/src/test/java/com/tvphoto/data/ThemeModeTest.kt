package com.tvphoto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The theme is stored by name rather than by position, so that inserting a mode later
 * cannot silently turn one install's dark TV light. These pin that, and the default.
 */
class ThemeModeTest {

    @Test
    fun `dark is the default`() {
        assertEquals(ThemeMode.DARK, ThemeMode.DEFAULT)
    }

    @Test
    fun `a stored mode is read back by name`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromId("dark"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromId("light"))
    }

    @Test
    fun `an unknown or absent mode falls back rather than throwing`() {
        assertNull(ThemeMode.fromId(null))
        assertNull(ThemeMode.fromId(""))
        assertNull(ThemeMode.fromId("sepia"))
    }

    @Test
    fun `the setting is a two-way toggle`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.DARK.next())
        assertEquals(ThemeMode.DARK, ThemeMode.LIGHT.next())
    }
}
