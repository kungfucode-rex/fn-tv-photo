package com.tvphoto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a release manifest, and reading the tag out of the redirect GitHub answers
 * `releases/latest` with.
 *
 * Both parse things this app does not control: a file that may be missing, an error page
 * where JSON was expected, a redirect that may land on the releases index because no
 * release was ever published. Every one of those has to come out as "nothing to report" -
 * the failure that matters here is a version invented from a stray number in a path,
 * because the app would then offer an install that does not exist.
 */
class UpdateCheckerTest {

    @Test
    fun `a full manifest reads back field for field`() {
        val release = parseUpdateManifest(
            """
            {
              "versionName": "1.4.0",
              "versionCode": 6,
              "apk": "FN-tvphoto-1.4.0.apk",
              "apkUrl": "https://github.com/kungfucode-rex/fn-tv-photo/releases/download/v1.4.0/FN-tvphoto-1.4.0.apk",
              "sha256": "CFBF6A183541E712E9DEF78716FA3CE2A0CC2E754F172D5ED2BB0DF297C2CF92",
              "sizeBytes": 68891443
            }
            """.trimIndent()
        )!!

        assertEquals("1.4.0", release.versionName)
        assertEquals(6, release.versionCode)
        assertEquals(68891443L, release.sizeBytes)
        assertTrue(release.apkUrl!!.endsWith("FN-tvphoto-1.4.0.apk"))
        assertTrue(release.isInstallable)
    }

    @Test
    fun `a hash written in lower case or with spaces still matches`() {
        val release = parseUpdateManifest(
            """{"versionName":"1.4.0","apkUrl":"https://example/apk","sha256":" cfbf 6a18 "}"""
        )!!
        assertEquals("CFBF6A18", release.sha256)
    }

    @Test
    fun `a manifest that names a version without a file is reported but not installable`() {
        // This is what every release published before version.json existed looks like,
        // and it is the whole reason the fallback tag path is worth having.
        val release = parseUpdateManifest("""{"versionName":"1.4.0"}""")!!
        assertEquals("1.4.0", release.versionName)
        assertNull(release.versionCode)
        assertNull(release.apkUrl)
        assertFalse(release.isInstallable)
    }

    @Test
    fun `an apk without a hash is not installable`() {
        val release = parseUpdateManifest(
            """{"versionName":"1.4.0","apkUrl":"https://example/apk"}"""
        )!!
        assertFalse(release.isInstallable)
    }

    @Test
    fun `anything that is not a manifest reads as no manifest`() {
        assertNull(parseUpdateManifest(""))
        assertNull(parseUpdateManifest("Not Found"))
        assertNull(parseUpdateManifest("<html><body>404</body></html>"))
        assertNull(parseUpdateManifest("[]"))
        // A version is what makes a manifest usable; a name-less document is not one,
        // however well formed the rest of it is.
        assertNull(parseUpdateManifest("""{"apkUrl":"https://example/apk","sha256":"AB"}"""))
        assertNull(parseUpdateManifest("""{"versionName":"   "}"""))
    }

    @Test
    fun `the tag is read off the url the redirect landed on`() {
        assertEquals(
            "v1.4.0",
            releaseTagFrom("https://github.com/kungfucode-rex/fn-tv-photo/releases/tag/v1.4.0"),
        )
        assertEquals(
            "v1.4.0",
            releaseTagFrom("https://github.com/kungfucode-rex/fn-tv-photo/releases/tag/v1.4.0?expanded=true"),
        )
    }

    @Test
    fun `a repository with no published release reads as no tag`() {
        assertNull(releaseTagFrom("https://github.com/kungfucode-rex/fn-tv-photo/releases"))
        assertNull(releaseTagFrom("https://github.com/kungfucode-rex/fn-tv-photo/releases/latest"))
        assertNull(releaseTagFrom("https://github.com/kungfucode-rex/fn-tv-photo/releases/tag/"))
        assertNull(releaseTagFrom(""))
    }

    @Test
    fun `two sources answering differently offer the newer release, not the first one`() {
        // The state the two hosts were actually in: GitHub still serving 1.3.3 while Gitee
        // already carried 1.3.4. Which one answered first is a matter of which network is
        // faster, and that must not decide which version a user is offered.
        val gitee = UpdateRelease(versionName = "1.3.4", versionCode = 8)
        val github = UpdateRelease(versionName = "1.3.3", versionCode = 7)

        assertEquals("1.3.4", newestRelease(listOf(github, gitee), "1.3.3", 7)?.versionName)
        assertEquals("1.3.4", newestRelease(listOf(gitee, github), "1.3.3", 7)?.versionName)
    }

    @Test
    fun `nothing newer than this build is not an upgrade`() {
        val releases = listOf(
            UpdateRelease(versionName = "1.3.3", versionCode = 7),
            UpdateRelease(versionName = "1.3.2", versionCode = 6),
        )
        assertNull(newestRelease(releases, "1.3.4", 8))
        // A source that named only its version - no manifest, no code - is compared on the
        // name alone, and a name equal to this build is not an upgrade either.
        assertNull(newestRelease(listOf(UpdateRelease(versionName = "1.3.4")), "1.3.4", 8))
    }

    @Test
    fun `a higher version code wins even when the names read the same`() {
        val byCode = UpdateRelease(versionName = "1.3.4", versionCode = 9)
        val byNameOnly = UpdateRelease(versionName = "1.3.4")
        assertEquals(
            9,
            newestRelease(listOf(byNameOnly, byCode), "1.3.4", 8)?.versionCode,
        )
    }

    @Test
    fun `no answers at all offers nothing rather than inventing a release`() {
        assertNull(newestRelease(emptyList(), "1.3.4", 8))
    }
}
