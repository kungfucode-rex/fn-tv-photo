package com.tvphoto.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.BuildConfig
import com.tvphoto.R
import com.tvphoto.data.PreviewOriginal
import com.tvphoto.data.SessionState
import com.tvphoto.data.ThemeMode
import com.tvphoto.data.fn.SignMode
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.UpdateState
import com.tvphoto.ui.components.FocusableSurface
import com.tvphoto.ui.components.SectionHeader
import com.tvphoto.ui.components.formatFileSize

@Composable
fun SettingsSection(viewModel: MainViewModel) {
    val signMode by viewModel.signMode.collectAsStateWithLifecycle()
    val previewOriginal by viewModel.previewOriginal.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()

    val session = (sessionState as? SessionState.SignedIn)?.session
    var cacheCleared by remember { mutableStateOf(false) }
    var usageTick by remember { mutableIntStateOf(0) }
    var cachedPair by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    // Re-read whenever the screen appears or the cache is cleared, so the figure is
    // never a stale impression of a self-trimming LRU.
    LaunchedEffect(usageTick) {
        cachedPair = viewModel.imageCacheUsage()
    }

    /** "1.2GB / 5.0GB", or the cleared confirmation for a moment after clearing. */
    @Composable
    fun cacheUsage(): String {
        if (cacheCleared) return stringResource(R.string.settings_cache_cleared)
        val pair = cachedPair ?: return stringResource(R.string.settings_cache_measuring)
        return stringResource(
            R.string.settings_cache_usage,
            formatFileSize(pair.first),
            formatFileSize(pair.second),
        )
    }

    LaunchedEffect(cacheCleared) {
        if (cacheCleared) {
            kotlinx.coroutines.delay(2000)
            cacheCleared = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // The rows are taller than a 1080p screen once every one of them is here,
            // and the last one used to be cut off at the bottom edge — a rounded box
            // with no text in it, which reads as a button that does nothing. Focus
            // brings the row it lands on into view, so nothing is out of reach.
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(title = stringResource(R.string.settings_title))
        Spacer(Modifier.height(12.dp))

        InfoRow(
            label = stringResource(R.string.settings_server),
            value = session?.baseUrl.orEmpty(),
        )
        InfoRow(
            label = stringResource(R.string.settings_account),
            value = session?.userName.orEmpty(),
        )
        InfoRow(
            label = stringResource(R.string.settings_version),
            value = "FN Photo ${BuildConfig.VERSION_NAME}",
        )

        // Asks GitHub by itself the moment this screen appears, throttled in the view
        // model. The row is the whole interface: no dialog, no second button, and nothing
        // that can be typed on a television.
        LaunchedEffect(Unit) { viewModel.autoCheckForUpdate() }
        val update by viewModel.update.collectAsStateWithLifecycle()
        val updateRow = updateRowText(update, viewModel.updateReleasePageUrl)
        ActionRow(
            label = stringResource(R.string.settings_update),
            value = updateRow.value,
            hint = updateRow.hint,
            valueColor = if (update is UpdateState.Available ||
                update is UpdateState.NeedsPermission ||
                update is UpdateState.ReadyToInstall
            ) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            onClick = viewModel::onUpdateRowClick,
        )

        // Read once per visit: it cannot change while the process lives, and the one
        // time it matters — a launch that died on a TV with no console — it is the only
        // report the user can actually read out.
        val lastCrash = remember { viewModel.lastCrash() }
        InfoRow(
            label = stringResource(R.string.settings_last_crash),
            value = lastCrash ?: stringResource(R.string.settings_crash_none),
        )

        // A TV remote has no easy way to pick from a list, so each setting cycles
        // through its options on OK.
        ActionRow(
            label = stringResource(R.string.settings_theme),
            value = when (themeMode) {
                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
            },
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { viewModel.setThemeMode(themeMode.next()) },
        )

        ActionRow(
            label = stringResource(R.string.settings_sign_mode),
            value = when (signMode) {
                SignMode.RAW_VALUES -> "RAW"
                SignMode.ENCODED_QUERY -> "ENCODED"
            },
            hint = stringResource(R.string.settings_sign_mode_hint),
            onClick = {
                viewModel.setSignMode(
                    when (signMode) {
                        SignMode.RAW_VALUES -> SignMode.ENCODED_QUERY
                        SignMode.ENCODED_QUERY -> SignMode.RAW_VALUES
                    },
                )
            },
        )

        ActionRow(
            label = stringResource(R.string.settings_preview_original),
            value = when (previewOriginal) {
                PreviewOriginal.ALWAYS -> stringResource(R.string.settings_preview_original_yes)
                PreviewOriginal.NEVER -> stringResource(R.string.settings_preview_original_no)
                PreviewOriginal.SLIDESHOW_ONLY ->
                    stringResource(R.string.settings_preview_original_slideshow)
            },
            hint = stringResource(R.string.settings_preview_original_hint),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { viewModel.setPreviewOriginal(previewOriginal.next()) },
        )

        ActionRow(
            label = stringResource(R.string.settings_cache),
            value = cacheUsage(),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = {
                viewModel.clearImageCache()
                cacheCleared = true
                usageTick++
            },
        )
    }
}

/**
 * The value and the hint the 检测升级 row shows for [state].
 *
 * [releasePageUrl] is carried into the hint whenever the answer is "there is something to
 * install and this row cannot finish the job" - an unverifiable release, a permission the
 * user has not granted, an installer that would not open. That is the workaround the row
 * owes the user: the same APK, fetched by hand on a device that can do it.
 */
private data class UpdateRowText(val value: String, val hint: String?)

@Composable
private fun updateRowText(state: UpdateState, releasePageUrl: String): UpdateRowText = when (state) {
    is UpdateState.Idle -> UpdateRowText(stringResource(R.string.settings_update_idle), null)

    is UpdateState.Checking -> UpdateRowText(stringResource(R.string.settings_update_checking), null)

    is UpdateState.UpToDate ->
        UpdateRowText(stringResource(R.string.settings_update_up_to_date), null)

    is UpdateState.Available -> UpdateRowText(
        value = stringResource(R.string.settings_update_available, state.release.versionName),
        hint = stringResource(
            if (state.release.isInstallable) {
                R.string.settings_update_hint_available
            } else {
                R.string.settings_update_hint_tag_only
            },
            releasePageUrl,
        ),
    )

    is UpdateState.Downloading -> UpdateRowText(
        value = stringResource(R.string.settings_update_downloading, state.percent),
        hint = stringResource(R.string.settings_update_hint_downloading),
    )

    is UpdateState.NeedsPermission -> UpdateRowText(
        value = stringResource(R.string.settings_update_needs_permission),
        hint = stringResource(R.string.settings_update_hint_permission),
    )

    is UpdateState.ReadyToInstall -> UpdateRowText(
        value = stringResource(R.string.settings_update_ready),
        hint = stringResource(R.string.settings_update_hint_ready),
    )

    is UpdateState.Failed -> UpdateRowText(
        value = stringResource(
            when (state.kind) {
                UpdateState.Failed.Kind.CHECK -> R.string.settings_update_failed_check
                UpdateState.Failed.Kind.DOWNLOAD -> R.string.settings_update_failed_download
                UpdateState.Failed.Kind.VERIFY -> R.string.settings_update_failed_verify
                UpdateState.Failed.Kind.SIGNATURE -> R.string.settings_update_failed_signature
                UpdateState.Failed.Kind.INSTALL -> R.string.settings_update_failed_install
            },
        ),
        // A refused installer is not something pressing OK again is likely to fix, so that
        // one points at the manual route instead of at itself. Everything else repeats the
        // reason the failure gave, when it gave one: on a television this row is the only
        // place that reason can be read, and it is the difference between "检查失败" and
        // "无法解析主机名" - one is a mystery, the other is a diagnosis.
        hint = when {
            state.kind == UpdateState.Failed.Kind.INSTALL ->
                stringResource(R.string.settings_update_hint_manual, releasePageUrl)

            !state.detail.isNullOrBlank() ->
                stringResource(R.string.settings_update_hint_reason, state.detail)

            else -> stringResource(R.string.settings_update_hint_retry)
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(220.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ActionRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.width(360.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!hint.isNullOrBlank()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
            )
        }
    }
}
