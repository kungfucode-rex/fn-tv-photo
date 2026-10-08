package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an upgrade check is allowed to conclude from two version strings.
 *
 * The failure that matters is a false offer: an app that tells someone on a sofa that a
 * new version exists when it does not, or offers one it cannot rank. So these pin the
 * awkward spellings — the tag's leading `v`, a missing trailing segment, a pre-release
 * suffix, an unreadable string — rather than the easy ones.
 */
class AppVersionTest {

    @Test
    fun `a tag and an app version are the same version`() {
        assertEquals(AppVersion.parse("1.3.1"), AppVersion.parse("v1.3.1"))
        assertEquals(0, AppVersion.parse("v1.3.1")!!.compareTo(AppVersion.parse("1.3.1")!!))
    }

    @Test
    fun `a missing trailing segment reads as zero`() {
        assertEquals(AppVersion.parse("1.4"), AppVersion.parse("1.4.0"))
        assertEquals(AppVersion.parse("1.4"), AppVersion.parse("1.4.0.0"))
        assertFalse(isNewerVersion("v1.4.0", "1.4"))
        // Equality and ordering agree, which is what a value type owes its callers: two
        // spellings of one version are one version, not two that merely sort alike.
        assertEquals(0, AppVersion.parse("1.4.0")!!.compareTo(AppVersion.parse("v1.4")!!))
    }

    @Test
    fun `segments compare as numbers, not as text`() {
        assertTrue(isNewerVersion("v1.10.0", "1.9.9"))
        assertTrue(isNewerVersion("v1.3.10", "1.3.9"))
        assertFalse(isNewerVersion("v1.9.0", "1.10.0"))
    }

    @Test
    fun `the same version is not an upgrade`() {
        assertFalse(isNewerVersion("v1.3.1", "1.3.1"))
        assertFalse(isNewerVersion("1.3.1", "v1.3.1"))
    }

    @Test
    fun `an older release is not an upgrade`() {
        assertFalse(isNewerVersion("v1.2.9", "1.3.1"))
        assertFalse(isNewerVersion("v1.2", "1.3.1"))
    }

    @Test
    fun `a pre-release suffix is dropped rather than ranked`() {
        assertEquals(AppVersion.parse("1.4.0"), AppVersion.parse("v1.4.0-rc1"))
        assertEquals(AppVersion.parse("1.4.0"), AppVersion.parse("1.4.0+build.7"))
        // A pre-release of the version already installed is not an upgrade to offer a
        // television; reading the suffix as "1.4.0" is what makes that so.
        assertFalse(isNewerVersion("v1.4.0-rc1", "1.4.0"))
        assertTrue(isNewerVersion("v1.4.0-rc1", "1.3.1"))
    }

    @Test
    fun `surrounding text is tolerated without inventing a version`() {
        assertEquals(AppVersion.parse("1.3.1"), AppVersion.parse("  1.3.1  "))
        assertNull(AppVersion.parse("vNext"))
        assertNull(AppVersion.parse("release-1.4"))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("   "))
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse("v"))
    }

    @Test
    fun `an unreadable version on either side is never an upgrade`() {
        assertFalse(isNewerVersion("vNext", "1.3.1"))
        assertFalse(isNewerVersion("1.4.0", null))
        assertFalse(isNewerVersion(null, "1.3.1"))
    }

    @Test
    fun `a version prints back without the noise it was parsed with`() {
        assertEquals("1.3.1", AppVersion.parse("v1.3.1").toString())
        // Trailing zeros are dropped on the way in, so this is the spelling that comes
        // back out; the row itself shows the release's own versionName, never this.
        assertEquals("1.4", AppVersion.parse("1.4.0-rc1").toString())
    }

    @Test
    fun `either signal being ahead is enough to offer a release`() {
        // The installer compares codes, so a code that moved is an upgrade even when the
        // name did not.
        assertTrue(isUpdateAvailable("1.3.1", 6, "1.3.1", 5))
        // And a name that moved is worth reporting even when the code did not, which is
        // the mistake of bumping only appVersionName in app/build.gradle.kts.
        assertTrue(isUpdateAvailable("1.4.0", 5, "1.3.1", 5))
    }

    @Test
    fun `a release that is behind on both signals is silent`() {
        assertFalse(isUpdateAvailable("1.2.0", 4, "1.3.1", 5))
        assertFalse(isUpdateAvailable("1.3.1", 5, "1.3.1", 5))
    }

    @Test
    fun `a manifest published before codes existed still decides on the name`() {
        assertTrue(isUpdateAvailable("1.4.0", null, "1.3.1", 5))
        assertFalse(isUpdateAvailable("1.3.1", null, "1.3.1", 5))
    }

    @Test
    fun `a missing local code cannot make an upgrade appear`() {
        assertFalse(isUpdateAvailable("1.3.1", 6, "1.3.1", null))
        assertTrue(isUpdateAvailable("1.4.0", 6, "1.3.1", null))
    }
}
