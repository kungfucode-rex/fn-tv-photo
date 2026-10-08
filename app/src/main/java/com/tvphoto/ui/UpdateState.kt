package com.tvphoto.ui

import com.tvphoto.data.UpdateRelease

/**
 * Where the settings row's upgrade check has got to.
 *
 * Every state here is something the row can put on screen and act on with one press of
 * OK, because a television has no second way in: there is no dialog to dismiss, no
 * notification to tap, and nothing that can be typed. A check that is running, a download
 * that is half done and a permission the user still has to grant are therefore states of
 * the same row rather than separate screens.
 */
sealed interface UpdateState {

    /** Nothing has been asked yet this session. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** This build is the newest published one; the installed version is shown so the row says what it checked. */
    data class UpToDate(val version: String) : UpdateState

    /**
     * A newer release exists.
     *
     * [UpdateRelease.isInstallable] separates the two cases that look alike here: a
     * release carrying a manifest can be downloaded, while one known only by its tag can
     * only be reported, and the row then offers the manual route instead.
     */
    data class Available(val release: UpdateRelease) : UpdateState

    /** Downloading [release]; [percent] is 0..100. */
    data class Downloading(val release: UpdateRelease, val percent: Int) : UpdateState

    /**
     * Android will not let this app open an installer until the user grants it.
     *
     * [release] is held so that pressing OK after granting continues where the user was,
     * rather than starting the whole check again.
     */
    data class NeedsPermission(val release: UpdateRelease) : UpdateState

    /** Downloaded, verified, and handed to the installer once already. */
    data class ReadyToInstall(val release: UpdateRelease) : UpdateState

    /**
     * The last attempt did not finish. [retry] is the release to act on when the user
     * presses OK - null means there was nothing to install and the check itself is what
     * to repeat.
     *
     * [detail] is why it did not finish, in the words of whatever refused: an exception
     * name and message, or an HTTP status. It is shown under the row because on a
     * television there is nowhere else to read it - this app has no console and the
     * person holding the remote cannot open a log - and because "检查失败" alone cannot
     * be acted on by anyone, including the person who wrote it.
     */
    data class Failed(
        val kind: Kind,
        val retry: UpdateRelease? = null,
        val detail: String? = null,
    ) : UpdateState {
        enum class Kind { CHECK, DOWNLOAD, VERIFY, SIGNATURE, INSTALL }
    }

    /** True while an attempt is in flight, so a second press cannot start a second one. */
    val isBusy: Boolean get() = this is Checking || this is Downloading
}
