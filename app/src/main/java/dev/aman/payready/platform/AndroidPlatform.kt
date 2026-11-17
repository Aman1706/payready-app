package dev.aman.payready.platform

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import dev.aman.payready.AutoModeService
import dev.aman.payready.PayTileService
import dev.aman.payready.R
import dev.aman.payready.RestoreReceiver
import dev.aman.payready.app
import dev.aman.payready.core.Options
import dev.aman.payready.core.Platform
import kotlinx.coroutines.launch

const val CHANNEL_PAYMENT = "payment_mode"
const val CHANNEL_AUTO = "auto_mode"
const val NOTIFICATION_PAYMENT = 1
const val NOTIFICATION_AUTO = 2

class AndroidPlatform(private val context: Context) : Platform {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_PAYMENT, "Payment mode", NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_AUTO, "Auto mode", NotificationManager.IMPORTANCE_MIN)
        )
    }

    // Since Android 14, killBackgroundProcesses() silently ignores other packages.
    override val canKillOtherApps = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    override fun kill(pkg: String) {
        context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg)
    }

    override fun launch(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    override fun onPrepared(options: Options) {
        showPaymentNotification(context)
        refreshTile()
        // Watch for leaving the target. Android refuses this from the background (a broadcast);
        // the next time PayReady is opened, MainActivity starts it.
        if (!AutoModeService.sync(context, options, prepared = true)) {
            context.app.scope.launch { context.app.store.log("Auto-restore could not start from the background") }
        }
    }

    override fun onRestored() {
        notifications.cancel(NOTIFICATION_PAYMENT)
        refreshTile()
    }

    companion object {
        /** The ongoing "payment mode on" notification. [autoRestore] when the watcher is running. */
        fun paymentNotification(context: Context, autoRestore: Boolean): Notification {
            val restore = PendingIntent.getBroadcast(
                context, 0, Intent(context, RestoreReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_PAYMENT)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("Payment mode on — tap to restore")
                .setContentText(
                    if (autoRestore) "Restores by itself once you leave the payment app."
                    else "Accessibility and debugging are off until you restore."
                )
                .setOngoing(true)
                .setContentIntent(restore)
                .addAction(0, context.getString(R.string.restore), restore)
                .build()
        }

        fun showPaymentNotification(context: Context) {
            // Without the notification permission this is silently dropped.
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_PAYMENT, paymentNotification(context, autoRestore = false))
        }
    }

    private fun refreshTile() {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, PayTileService::class.java))
        }
    }
}
