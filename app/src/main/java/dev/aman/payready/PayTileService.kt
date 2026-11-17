package dev.aman.payready

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile: tap to prepare, tap again to restore. */
class PayTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        scope.launch { show(app.store.load() != null) }
    }

    override fun onClick() {
        scope.launch {
            if (app.store.load() != null) {
                // Run on the app scope so it finishes even if the tile is unbound.
                app.scope.launch { app.controller.restore() }.join()
                show(app.store.load() != null)
            } else {
                openTrampoline()
            }
        }
    }

    private fun openTrampoline() {
        val intent = Intent(this, TrampolineActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun show(prepared: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (prepared) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(if (prepared) R.string.restore else R.string.pay_now)
        tile.subtitle = if (prepared) "Payment mode on" else null
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
