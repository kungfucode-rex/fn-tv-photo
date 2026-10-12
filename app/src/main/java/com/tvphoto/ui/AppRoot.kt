package com.tvphoto.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.BuildConfig
import com.tvphoto.R
import com.tvphoto.data.SessionState
import com.tvphoto.ui.components.Spinner
import com.tvphoto.ui.gallery.GalleryScreen
import com.tvphoto.ui.home.HomeScreen
import com.tvphoto.ui.login.LoginScreen
import com.tvphoto.ui.music.MusicUploadScreen
import com.tvphoto.ui.viewer.ViewerScreen
import kotlinx.coroutines.delay

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val restoring by viewModel.restoringSession.collectAsStateWithLifecycle()
    val recovering by viewModel.recoveringSession.collectAsStateWithLifecycle()

    when {
        sessionState is SessionState.SignedIn -> SignedInRoot(viewModel)

        // Credentials are on the device and the app is signing in with them: hold the
        // loading screen rather than drawing a login form that is about to be replaced.
        restoring -> RestoringSession(LoadingReason.RESTORING) { viewModel.cancelAutoSignIn() }

        // A stale token is being replaced behind the user's back. The form is deliberately
        // not shown here: nobody signed out, and a screen that says "log in" over a working
        // re-login is the app asking for something it is already doing. A re-login that
        // really fails leaves `SessionState.Failed`, which falls through to the form below
        // with the server's own reason on it.
        recovering -> RestoringSession(LoadingReason.RECOVERING, onCancel = null)

        else -> LoginScreen(viewModel, sessionState)
    }
}

/** Which of the two start-up-shaped sign-ins the loading screen is covering. */
private enum class LoadingReason(val title: Int, val hint: Int, val slow: Int) {
    RESTORING(R.string.login_restoring, R.string.login_restoring_hint, R.string.login_restoring_slow),
    RECOVERING(R.string.login_recovering, R.string.login_recovering_hint, R.string.login_recovering_slow),
}

/**
 * What the app shows while it signs in with the account it already has — at start-up, or
 * after a token went stale while the app was sitting there.
 *
 * It has to be able to take the screen forever if the NAS is off, so it says what it is
 * doing and, once that has gone on long enough to look stuck, offers the way out a remote
 * can actually press. The stale-token case has no way out to offer — the re-login is the
 * only route back to the library, and a Back that cancelled it would leave the app on a
 * login form with nothing to explain itself — so [onCancel] is null there and Back stays
 * with whatever the system does by default.
 */
@Composable
private fun RestoringSession(
    reason: LoadingReason = LoadingReason.RESTORING,
    onCancel: (() -> Unit)?,
) {
    var slow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(SLOW_SIGN_IN_MILLIS)
        slow = true
    }

    if (onCancel != null) {
        BackHandler(enabled = slow) { onCancel() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Spinner()
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(reason.title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(if (slow) reason.slow else reason.hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How long a start-up sign-in may run before it is treated as possibly stuck. */
private const val SLOW_SIGN_IN_MILLIS = 6_000L

@Composable
private fun SignedInRoot(viewModel: MainViewModel) {
    val stack by viewModel.stack.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var lastBackAt by remember { mutableLongStateOf(0L) }
    var showExitHint by remember { mutableStateOf(false) }

    // Back walks the stack; at the root a second press within the window exits,
    // which is the convention TV users expect and avoids an accidental quit.
    BackHandler {
        if (!viewModel.back()) {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < EXIT_WINDOW_MS) {
                (context as? Activity)?.finish()
            } else {
                lastBackAt = now
                showExitHint = true
            }
        }
    }

    // ------------------------------------------------------------------ 屏保相册
    //
    // The clock, and the two ways out of it. All three live here rather than in the album
    // screens or the viewer because this is the only composable that sees *every* key: a
    // screen only handles the keys it uses, and a user who has walked away from a television
    // is by definition not using any of them.

    val activityAt by viewModel.lastActivityAt.collectAsStateWithLifecycle()
    val screenSaverTarget by viewModel.screenSaverTarget.collectAsStateWithLifecycle()
    val screenSaverActive = stack.any { it is Screen.ScreenSaverAlbum }

    // Coming back to the app counts as activity: a television that was switched to another
    // input for an hour has not been "left alone" in the way this feature means, and firing the
    // screensaver over the first frame back would be a picture nobody asked for.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.recordActivity() }

    // Keyed on the timestamp, so any activity at all cancels the wait and starts a new one —
    // there is no interval to poll. It waits only while there is an album to play and nothing
    // is playing it, which is what stops a second trigger while the first is still loading.
    LaunchedEffect(activityAt, screenSaverTarget, screenSaverActive) {
        val target = screenSaverTarget ?: return@LaunchedEffect
        if (screenSaverActive) return@LaunchedEffect
        delay(BuildConfig.SCREEN_SAVER_IDLE_MILLIS)
        viewModel.openScreenSaver(target)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Every key and every touch is recorded, and the screensaver is ended by any of
            // them. Preview, so this sees the key before the screen under it acts on it: the
            // press that wakes the television must not also page the slideshow behind it, which
            // is what would happen if the viewer handled it first and this only ever saw what
            // was left.
            .onPreviewKeyEvent { event ->
                viewModel.recordActivity()
                if (screenSaverActive && event.type == KeyEventType.KeyDown) {
                    viewModel.stopScreenSaver()
                    true
                } else {
                    screenSaverActive
                }
            }
            // A television is not only driven by its remote: a touch screen, a mouse or an air
            // remote's pointer all arrive here, and a screensaver that a tap cannot dismiss is
            // a screensaver somebody has to find the remote for. The press callback is the
            // signal — the gesture itself is never read, which is why a hold works as well as
            // a tap and why nothing here has to know where the pointer was.
            .pointerInput(screenSaverActive) {
                if (!screenSaverActive) return@pointerInput
                detectTapGestures(
                    onPress = {
                        viewModel.recordActivity()
                        viewModel.stopScreenSaver()
                    },
                )
            },
    ) {
        when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(viewModel)

            is Screen.MonthPhotos,
            is Screen.AlbumPhotos,
            is Screen.PersonPhotos,
            is Screen.FolderBrowse,
            -> GalleryScreen(viewModel, screen)

            Screen.Music -> MusicUploadScreen(viewModel)

            // The key is the screen itself, so the viewer's own state — which photo is on
            // screen now, which step is queued — is not carried into the next photo the user
            // opens. Without it, opening a picture straight after the screensaver came back to
            // the screensaver's last frame and its index into somebody else's album.
            is Screen.Viewer -> androidx.compose.runtime.key(screen) {
                ViewerScreen(viewModel, screen, screenSaver = screenSaverActive)
            }

            // The marker screen the screensaver is built from: pushed under the viewer and
            // drawn as a viewer on its own, for the single frame before the viewer arrives and
            // for anything that takes the viewer off the stack.
            is Screen.ScreenSaverAlbum -> androidx.compose.runtime.key(screen) {
                ViewerScreen(viewModel, Screen.Viewer(0, screen.album.name), screenSaver = true)
            }
        }

        if (showExitHint) {
            LaunchedResetHint { showExitHint = false }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(com.tvphoto.R.string.exit_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** Clears the exit hint shortly after it appears. */
@Composable
private fun LaunchedResetHint(onReset: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2500)
        onReset()
    }
}

private const val EXIT_WINDOW_MS = 2000L
