package com.tvphoto.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.R
import com.tvphoto.data.ERROR_HOST_REQUIRED
import com.tvphoto.data.SessionState
import com.tvphoto.data.fn.ERROR_ACCESS_CODE_REJECTED
import com.tvphoto.data.fn.ERROR_ACCESS_CODE_REQUIRED
import com.tvphoto.data.fn.ERROR_CERTIFICATE_CHANGED
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.components.FocusableSurface
import com.tvphoto.ui.components.TvTextField

@Composable
fun LoginScreen(viewModel: MainViewModel, state: SessionState) {
    var host by remember { mutableStateOf(viewModel.savedHost) }
    var userName by remember { mutableStateOf(viewModel.savedUser) }
    var password by remember { mutableStateOf(viewModel.savedPassword) }
    var accessCode by remember { mutableStateOf(viewModel.savedAccessCode) }

    // The access code is off by default: most fnOS installs do not use one, and an
    // extra field on a TV keyboard is a real cost. It appears when the server
    // actually demands it, or when one has already been saved.
    var showAccessCode by remember { mutableStateOf(viewModel.savedAccessCode.isNotBlank()) }

    val signingIn = state is SessionState.SigningIn
    val errorMessage = (state as? SessionState.Failed)?.message
    val hostFocus = remember { FocusRequester() }
    val accessCodeFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { hostFocus.requestFocus() }
    }

    LaunchedEffect(errorMessage) {
        if (errorMessage == ERROR_ACCESS_CODE_REQUIRED) {
            showAccessCode = true
            runCatching { accessCodeFocus.requestFocus() }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                    ),
                ),
            )
            .padding(horizontal = 64.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            BrandColumn()

            // Typing a hostname with a remote is painful, so previously used logins are
            // offered as one-press shortcuts. Each carries its own account: one NAS can
            // have several, with different passwords and access codes.
            if (viewModel.savedAccounts.isNotEmpty()) {
                Spacer(Modifier.height(40.dp))
                Text(
                    text = stringResource(R.string.login_remember),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                viewModel.savedAccounts.forEach { account ->
                    FocusableSurface(
                        onClick = {
                            // Fill the form as well as signing in, so a failure leaves
                            // this account's details on screen to correct rather than
                            // the previous account's.
                            host = account.host
                            userName = account.user
                            password = account.password
                            accessCode = account.accessCode
                            if (account.accessCode.isNotBlank()) showAccessCode = true
                            viewModel.signIn(
                                account.host,
                                account.user,
                                account.password,
                                account.accessCode,
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(
                                text = account.user,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = account.host,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.width(64.dp))

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                // Scrollable because the access-code field only appears when the
                // server demands it, which pushes the form past the viewport on a
                // 1080p TV — and the error text below the button is exactly what the
                // user needs to read at that moment.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            // The NAS address, and nothing else: FN ID sign-in was removed once the
            // vendor's relay turned out not to carry the API. An address with no port
            // takes the fnOS default, 5666.
            TvTextField(
                value = host,
                onValueChange = { host = it },
                label = stringResource(R.string.login_host),
                placeholder = stringResource(R.string.login_host_hint),
                keyboardType = KeyboardType.Uri,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(hostFocus),
            )

            Spacer(Modifier.height(14.dp))

            TvTextField(
                value = userName,
                onValueChange = { typed ->
                    userName = typed
                    // Switching to another account on the same NAS brings that account's
                    // password with it instead of leaving the previous one's in the
                    // field — which is exactly how a sign-in used to fail here.
                    viewModel.savedAccountFor(host, typed)?.let { saved ->
                        password = saved.password
                        accessCode = saved.accessCode
                        if (saved.accessCode.isNotBlank()) showAccessCode = true
                    }
                },
                label = stringResource(R.string.login_user),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(14.dp))

            TvTextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.login_password),
                isPassword = true,
                keyboardType = KeyboardType.Password,
                modifier = Modifier.fillMaxWidth(),
            )

            if (showAccessCode) {
                Spacer(Modifier.height(16.dp))
                TvTextField(
                    value = accessCode,
                    onValueChange = { accessCode = it },
                    label = stringResource(R.string.login_access_code),
                    placeholder = stringResource(R.string.login_access_code_hint),
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(accessCodeFocus),
                )
            }

            Spacer(Modifier.height(16.dp))

            FocusableSurface(
                onClick = { viewModel.signIn(host, userName, password, accessCode) },
                shape = RoundedCornerShape(12.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(220.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (signingIn) {
                            stringResource(R.string.login_connecting)
                        } else {
                            stringResource(R.string.login_submit)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = when (errorMessage) {
                            ERROR_HOST_REQUIRED -> stringResource(R.string.host_required)
                            ERROR_ACCESS_CODE_REQUIRED -> stringResource(R.string.access_code_required)
                            ERROR_ACCESS_CODE_REJECTED -> stringResource(R.string.access_code_rejected)
                            ERROR_CERTIFICATE_CHANGED -> stringResource(R.string.certificate_changed)
                            else -> stringResource(R.string.login_failed) + " · " + errorMessage
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandColumn(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
