package dev.aman.payready

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.aman.payready.ui.LogScreen
import dev.aman.payready.ui.MainScreen
import dev.aman.payready.ui.PickerScreen
import dev.aman.payready.ui.SetupScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class Screen { Main, Picker, Log, Setup }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = colors) {
                var screen by rememberSaveable { mutableStateOf(Screen.Main) }
                BackHandler(enabled = screen != Screen.Main) { screen = Screen.Main }
                when (screen) {
                    Screen.Main -> MainScreen(onNavigate = { screen = it })
                    Screen.Picker -> PickerScreen(onBack = { screen = Screen.Main })
                    Screen.Log -> LogScreen(onBack = { screen = Screen.Main })
                    Screen.Setup -> SetupScreen(onBack = { screen = Screen.Main })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // A foreground service can only be started while the app is visible.
        // Also catches an auto-restore that a broadcast prepare couldn't start.
        app.scope.launch {
            val prepared = app.store.preparedAt.first() != null
            AutoModeService.sync(this@MainActivity, app.store.options.first(), prepared)
        }
    }
}
