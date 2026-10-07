package com.tvphoto

import android.app.Application
import com.tvphoto.data.AppContainer

class TvPhotoApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // First thing after the container exists: if start-up fails on the TV — which is
        // the one place this app cannot be debugged from — the reason is at least kept
        // for the Settings screen rather than lost with the process.
        container.crashLog.install()
        container.installImageLoader()
    }
}
