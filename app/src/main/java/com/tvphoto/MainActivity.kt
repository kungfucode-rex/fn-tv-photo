package com.tvphoto

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import com.tvphoto.ui.AppRoot
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.theme.TvPhotoTheme
import com.tvphoto.ui.theme.windowBackgroundArgb

class MainActivity : ComponentActivity() {

    private val container get() = (application as TvPhotoApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A photo viewer on a TV should never dim or sleep mid-slideshow.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // The window is filled before Compose has drawn anything, and the theme in
        // `themes.xml` can only name one static colour. Without this the light theme
        // flashes black on every launch, which on a TV reads as a restart.
        window.setBackgroundDrawable(ColorDrawable(windowBackgroundArgb(container.settings.themeMode)))

        setContent {
            // The activity's view model, which is the same instance AppRoot composes
            // with: the theme is a property of the whole tree, so it has to be read
            // above everything the settings screen can change it from.
            val viewModel: MainViewModel = viewModel()
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

            TvPhotoTheme(mode = themeMode) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        AppRoot(viewModel)
                    }
                }
            }
        }
    }
}
