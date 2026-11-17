package dev.aman.payready

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.aman.payready.core.Options
import dev.aman.payready.platform.AndroidPlatform
import dev.aman.payready.platform.CHANNEL_AUTO
import dev.aman.payready.platform.NOTIFICATION_AUTO
import dev.aman.payready.platform.NOTIFICATION_PAYMENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Watches which app is in the foreground. Runs in one of two modes:
 * - Auto mode (always on): prepares when any target opens and restores once the user has
 *   been in an unrelated app for longer than the debounce.
 * - Auto-restore (only while prepared): the same restore, but never prepares. Started by a
 *   manual prepare; stops itself after the restore. It shows as the payment-mode
 *   notification rather than adding a second one.
 */
class AutoModeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val autoMode = intent?.getBooleanExtra(EXTRA_AUTO_MODE, true) ?: true
        val prepared = intent?.getBooleanExtra(EXTRA_PREPARED, false) ?: false
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        // Switching the foreground id makes Android remove the previous notification.
        if (autoMode) {
            val notification = NotificationCompat.Builder(this, CHANNEL_AUTO)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("PayReady auto mode is on")
                .setOngoing(true)
                .build()
            ServiceCompat.startForeground(this, NOTIFICATION_AUTO, notification, type)
            if (prepared) AndroidPlatform.showPaymentNotification(this) // removed if it was ours
        } else {
            val notification = AndroidPlatform.paymentNotification(this, autoRestore = true)
            ServiceCompat.startForeground(this, NOTIFICATION_PAYMENT, notification, type)
        }
        if (watcher == null) watcher = scope.launch { watch() }
        // Redelivered after a crash, so the mode survives a restart.
        return START_REDELIVER_INTENT
    }

    /** Screen on? Updated by [screenReceiver]; the watcher sleeps while this is false. */
    private val screenOn = MutableStateFlow(true)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn.value = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    override fun onCreate() {
        super.onCreate()
        screenOn.value = getSystemService(PowerManager::class.java).isInteractive
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    private suspend fun watch() {
        val usage = getSystemService(UsageStatsManager::class.java)
        val event = UsageEvents.Event()
        // Kept up to date by DataStore instead of being re-read on every check.
        val optionsState = app.store.options.stateIn(scope)
        val preparedState = app.store.preparedAt.map { it != null }.stateIn(scope)
        var foreground: String? = null
        var awaySince = 0L
        var armed = true // prepare once per visit to the target, so a manual restore sticks
        var lastQuery = System.currentTimeMillis() - 5_000

        while (scope.isActive) {
            if (!screenOn.value) {
                // Nothing can be opened with the screen off, so do nothing until it comes back on.
                screenOn.first { it }
                lastQuery = System.currentTimeMillis() - 2_000
                continue
            }
            val options = optionsState.value
            val prepared = preparedState.value
            // Auto-restore has nothing left to do once restored, however that happened.
            if (!options.autoMode && !(options.autoRestore && prepared)) break

            val now = System.currentTimeMillis()
            val events = usage.queryEvents(lastQuery, now)
            lastQuery = now
            while (events.getNextEvent(event)) {
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) foreground = event.packageName
            }

            val inTarget = foreground != null && foreground in options.targets
            val paying = inTarget || foreground in options.allowlist
            if (!inTarget) armed = true
            when {
                options.autoMode && inTarget && !prepared && armed -> {
                    armed = false
                    app.store.log("Auto mode: $foreground opened")
                    // The target is already open, so don't relaunch it.
                    app.controller.prepare(options, foreground!!, launch = false)
                    awaySince = 0L
                }
                prepared && foreground != null && !paying -> {
                    if (awaySince == 0L) {
                        awaySince = now
                    } else if (now - awaySince >= options.restoreDebounceMs) {
                        val who = if (options.autoMode) "Auto mode" else "Auto-restore"
                        app.store.log("$who: left the payment app ($foreground in front)")
                        app.controller.restore()
                        awaySince = 0L
                    }
                }
                else -> awaySince = 0L
            }
            // Check every second while in payment mode (to restore promptly), otherwise every 3 s.
            delay(if (prepared) POLL_PREPARED_MS else POLL_IDLE_MS)
        }
        // In auto-restore the notification is the payment one, which restore() could not cancel while
        // the service owned it. Remove it here; sync() re-posts it if payment mode is still on.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val POLL_PREPARED_MS = 1_000L
        private const val POLL_IDLE_MS = 3_000L

        private const val EXTRA_AUTO_MODE = "auto_mode"
        private const val EXTRA_PREPARED = "prepared"

        /**
         * Starts or stops the service to match the options and payment mode. Android only allows
         * the start while PayReady is visible; returns false if it was refused.
         */
        fun sync(context: Context, options: Options, prepared: Boolean): Boolean {
            val intent = Intent(context, AutoModeService::class.java)
            if (options.autoMode || (options.autoRestore && prepared)) {
                intent.putExtra(EXTRA_AUTO_MODE, options.autoMode).putExtra(EXTRA_PREPARED, prepared)
                return runCatching { context.startForegroundService(intent) }.isSuccess
            }
            context.stopService(intent)
            // Stopping takes the service's notification with it, which may have been the payment one.
            if (prepared) AndroidPlatform.showPaymentNotification(context)
            return true
        }
    }
}
