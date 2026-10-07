package com.tvphoto.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.R
import com.tvphoto.data.SessionState
import com.tvphoto.ui.components.Spinner
import com.tvphoto.ui.gallery.GalleryScreen
import com.tvphoto.ui.home.HomeScreen
import com.tvphoto.ui.login.LoginScreen
import com.tvphoto.ui.viewer.ViewerScreen
import kotlinx.coroutines.delay

@Composable
fun AppRoot(viewModel: MainViewModel = viewModel()) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val restoring by viewModel.restoringSession.collectAsStateWithLifecycle()

    when {
        sessionState is SessionState.SignedIn -> SignedInRoot(viewModel)

        // Credentials are on the device and the app is signing in with them: hold the
        // loading screen rather than drawing a login form that is about to be replaced.
        restoring -> RestoringSession { viewModel.cancelAutoSignIn() }

        else -> LoginScreen(viewModel, sessionState)
    }
}

/**
 * What the app shows while it signs in with the account it already has.
 *
 * It has to be able to take the screen forever if the NAS is off, so it says what it
 * is doing and, once that has gone on long enough to look stuck, offers the way out
 * a remote can actually press.
 */
@Composable
private fun RestoringSession(onCancel: () -> Unit) {
    var slow by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(SLOW_SIGN_IN_MILLIS)
        slow = true
    }

    BackHandler(enabled = slow) { onCancel() }

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
                text = stringResource(R.string.login_restoring),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    if (slow) R.string.login_restoring_slow else R.string.login_restoring_hint,
                ),
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

    Box(Modifier.fillMaxSize()) {
        when (val screen = stack.last()) {
            Screen.Home -> HomeScreen(viewModel)

            is Screen.MonthPhotos,
            is Screen.AlbumPhotos,
            is Screen.PersonPhotos,
            is Screen.FolderBrowse,
            -> GalleryScreen(viewModel, screen)

            is Screen.Viewer -> ViewerScreen(viewModel, screen)
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
