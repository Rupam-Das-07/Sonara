package com.example.sonara

import android.app.Application
import android.util.Log
import com.example.sonara.core.notification.NotificationChannels

/**
 * Application class initializing application-scoped singletons via AppContainer.
 */
class SonaraApp : Application() {

    companion object {
        private const val TAG = "SonaraApp"
    }

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SonaraApp initializing")
        container = AppContainer(this)

        // Register notification channels once (idempotent) and begin observing downloads so
        // progress/completion/failure notifications are rendered from the existing state stream.
        // Channel registration and observation need no runtime permission; posting is gated later.
        NotificationChannels.ensureChannels(this)
        container.downloadNotifier.start()
    }
}
