package dev.aman.payready

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.aman.payready.core.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which target a pinned shortcut opens. Only honoured if it is in the user's target list. */
const val EXTRA_TARGET = "target"

/**
 * Invisible activity behind the "Pay now" shortcut and the tile. Runs prepare, which opens
 * the target, then finishes. It is an activity because Android does not let background
 * code open another app.
 */
class TrampolineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                val options = app.store.options.first()
                app.controller.prepare(options, options.targetOrDefault(intent.getStringExtra(EXTRA_TARGET)))
            }
            if (outcome != Outcome.Ok) {
                val message = if (outcome == Outcome.PermissionDenied) "Permission not granted" else "PayReady could not change settings"
                Toast.makeText(this@TrampolineActivity, message, Toast.LENGTH_LONG).show()
                startActivity(Intent(this@TrampolineActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        }
    }
}
