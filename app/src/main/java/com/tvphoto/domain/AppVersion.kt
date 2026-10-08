package com.tvphoto.domain

/**
 * A version number compared segment by segment, as the thing an upgrade is decided on.
 *
 * Three shapes have to survive the trip from a GitHub release to here, and each one is a
 * rule rather than an accident:
 *
 *  - The tag is `v1.3.1` while the installed app calls itself `1.3.1`. The leading `v`
 *    is dropped, because otherwise the two names could never be equal and every check
 *    would claim an update that is not there.
 *  - A release may be tagged `v1.4` or `v1.4.0` for the same build. Missing segments
 *    read as zero, so those two are equal and neither is newer than the other. Trailing
 *    zeros are spelling, not version.
 *  - A pre-release is tagged `v1.4.0-rc1`. Everything from the first character that
 *    cannot be part of a number is discarded, so it reads as `1.4.0`. That is
 *    deliberate: the alternative is telling someone their app is out of date on the
 *    strength of a suffix this app has no way to rank — and a pre-release is not
 *    something to push onto a television.
 *
 * Garbage parses to null rather than to a number. A check that cannot read the version
 * it was given must report nothing; the one thing it may never do is invent a version
 * that makes the app look stale, because the app would then offer an install that does
 * not exist.
 */
data class AppVersion(val segments: List<Int>) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        val width = maxOf(segments.size, other.segments.size)
        for (index in 0 until width) {
            val mine = segments.getOrElse(index) { 0 }
            val theirs = other.segments.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    /**
     * Back to `1.3.1`.
     *
     * Not necessarily the spelling it was parsed from: the trailing zeros that [parse]
     * treats as noise are gone, so `1.4.0` prints as `1.4`. Nothing on screen comes from
     * here - the settings row shows the release's own `versionName` - so this is the
     * spelling for a log line or a test.
     */
    override fun toString(): String = segments.joinToString(".")

    companion object {
        /** `1.3.1` -> `AppVersion(1, 3, 1)`; anything unreadable -> null. */
        fun parse(raw: String?): AppVersion? {
            val trimmed = raw?.trim().orEmpty()
            val body = trimmed.removePrefix("v").removePrefix("V")
            if (body.isEmpty()) return null

            val segments = mutableListOf<Int>()
            for (segment in body.split('.')) {
                // A segment that is not a plain number ends the version, and everything
                // after it is ignored rather than read as further segments: that is what
                // turns `1.4.0-rc1` into 1, 4, 0 and stops `1.4.0+build.7` from picking
                // the 7 back up as a fourth one.
                if (segment.isEmpty() || !segment.all { it.isDigit() }) {
                    val digits = segment.takeWhile { it.isDigit() }
                    if (digits.isNotEmpty()) segments += digits.toIntOrNull() ?: return null
                    break
                }
                segments += segment.toIntOrNull() ?: return null
            }
            if (segments.isEmpty()) return null

            // Trailing zeros are dropped, and this is what keeps equality honest: as a
            // value type this class compares equal only where it also compares as the
            // same version, so `1.4` and `1.4.0` are one version rather than two that
            // happen to sort alike. The leading segment always survives, so `0` is still
            // a version and not an empty list.
            while (segments.size > 1 && segments.last() == 0) {
                segments.removeAt(segments.size - 1)
            }
            return AppVersion(segments)
        }
    }
}

/**
 * Whether the release described by [remote] should be offered to an install running
 * [local]; both are version *names*, as the settings row and the release tag spell them.
 *
 * An unreadable remote version is never an upgrade. An unreadable local one leaves
 * nothing to compare against either, so it too declines rather than guesses.
 */
fun isNewerVersion(remote: String?, local: String?): Boolean {
    val remoteVersion = AppVersion.parse(remote) ?: return false
    val localVersion = AppVersion.parse(local) ?: return false
    return remoteVersion > localVersion
}

/**
 * Whether to offer an upgrade, given both the version names and the integer version
 * codes each side carries.
 *
 * Either signal being ahead is enough, and that is the point: [remoteCode] is what the
 * TV's own installer compares, so a release whose code did not move cannot be laid over
 * this build whatever its name says; while a release whose *name* did not move is still
 * worth telling the user about. Combining them with "or" is the only arrangement that
 * never hides a release — and hiding one is worse here than offering an install the
 * installer may then refuse, because the row always carries the manual route as well.
 *
 * A missing code (a release published before the manifest carried one) simply drops that
 * half of the comparison.
 */
fun isUpdateAvailable(
    remoteVersion: String?,
    remoteCode: Int?,
    localVersion: String?,
    localCode: Int?,
): Boolean {
    val codeIsNewer = remoteCode != null && localCode != null && remoteCode > localCode
    return codeIsNewer || isNewerVersion(remoteVersion, localVersion)
}
