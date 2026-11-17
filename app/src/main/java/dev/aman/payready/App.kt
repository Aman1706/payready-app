package dev.aman.payready

import android.app.Application
import dev.aman.payready.core.PayReadyController
import dev.aman.payready.platform.AndroidPlatform
import dev.aman.payready.platform.AndroidSettings
import dev.aman.payready.platform.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    lateinit var store: Store
        private set
    lateinit var settings: AndroidSettings
        private set
    lateinit var controller: PayReadyController
        private set

    /** For work that must finish even if the screen that started it goes away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        settings = AndroidSettings(contentResolver)
        val platform = AndroidPlatform(this).also { it.createChannels() }
        controller = PayReadyController(settings, store, platform, store)
    }
}

val android.content.Context.app: App get() = applicationContext as App
