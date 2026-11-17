package dev.aman.payready

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.MessageDigest

const val ACTION_PREPARE = "dev.aman.payready.PREPARE"
const val ACTION_RESTORE = "dev.aman.payready.RESTORE"
const val EXTRA_TOKEN = "token"

/**
 * Entry point for MacroDroid/Tasker. Any app can send to this receiver, so a broadcast
 * is ignored unless it carries the token shown in PayReady's settings.
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        val pending = goAsync()
        app.scope.launch {
            try {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: return@launch
                if (!MessageDigest.isEqual(token.toByteArray(), app.store.token().toByteArray())) return@launch
                when (intent.action) {
                    ACTION_PREPARE -> {
                        val options = app.store.options.first()
                        val target = options.targetOrDefault(intent.getStringExtra(EXTRA_TARGET))
                        app.store.log("Broadcast: prepare for $target")
                        app.controller.prepare(options, target)
                    }
                    ACTION_RESTORE -> {
                        app.store.log("Broadcast: restore")
                        app.controller.restore()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** The notification's Restore action. Not exported. */
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.app
        val pending = goAsync()
        app.scope.launch {
            try {
                app.controller.restore()
            } finally {
                pending.finish()
            }
        }
    }
}
