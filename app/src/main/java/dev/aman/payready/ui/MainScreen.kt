package dev.aman.payready.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aman.payready.AutoModeService
import dev.aman.payready.EXTRA_TARGET
import dev.aman.payready.R
import dev.aman.payready.Screen
import dev.aman.payready.TrampolineActivity
import dev.aman.payready.app
import dev.aman.payready.core.Options
import dev.aman.payready.core.Outcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

private const val STALE_AFTER_MS = 30 * 60 * 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val options by app.store.options.collectAsStateWithLifecycle(Options())
    val preparedAt by app.store.preparedAt.collectAsStateWithLifecycle(null)
    var status by remember { mutableStateOf(readDeviceStatus(context)) }
    var showAllowlist by remember { mutableStateOf(false) }
    val labels = remember(options.targets) { options.targets.associateWith { appLabel(context, it) } }
    fun label(pkg: String) = labels[pkg] ?: pkg

    // Re-read the device state whenever the screen comes back or payment mode changes.
    LifecycleResumeEffect(Unit) {
        status = readDeviceStatus(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(preparedAt) {
        delay(300)
        status = readDeviceStatus(context)
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = readDeviceStatus(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !status.notifications) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun save(changed: Options) {
        scope.launch { app.store.setOptions(changed) }
    }

    /** Saves first, so the service reads the new options when it starts. */
    fun saveAndSync(changed: Options) {
        scope.launch {
            app.store.setOptions(changed)
            AutoModeService.sync(context, changed, preparedAt != null)
        }
    }

    // Runs on the app scope: the work must finish even though this screen goes to the background.
    fun run(action: suspend () -> Outcome) {
        scope.launch {
            val outcome = withContext(app.scope.coroutineContext) { action() }
            status = readDeviceStatus(context)
            when (outcome) {
                Outcome.Ok -> Unit
                Outcome.NotPrepared -> snackbar.showSnackbar("Nothing to restore")
                Outcome.PermissionDenied -> onNavigate(Screen.Setup)
                is Outcome.Failed -> snackbar.showSnackbar("Failed: ${outcome.reason}")
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("PayReady") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            preparedAt?.let { since ->
                val stale = System.currentTimeMillis() - since > STALE_AFTER_MS
                PaymentModeBanner(stale = stale, onRestore = { run { app.controller.restore() } })
            }

            if (!status.canWriteSettings) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Permission not granted", style = MaterialTheme.typography.titleMedium)
                        Text("PayReady can't change settings until you run one adb command.")
                        Button(onClick = { onNavigate(Screen.Setup) }) { Text("Show setup") }
                    }
                }
            }

            Button(
                onClick = { run { app.controller.prepare(options, options.defaultTarget) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
            ) { Text("Fix & open ${label(options.defaultTarget)}", style = MaterialTheme.typography.titleMedium) }

            for (target in options.targets.drop(1)) {
                FilledTonalButton(
                    onClick = { run { app.controller.prepare(options, target) } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Fix & open ${label(target)}") }
            }

            OutlinedButton(
                onClick = { run { app.controller.restore() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore everything") }

            StatusCard(status, onUsageAccess = {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            })

            Section("Target apps") {
                Text(
                    "The first app is the default, used by the tile and the Pay now shortcut.",
                    style = MaterialTheme.typography.bodySmall,
                )
                options.targets.forEachIndexed { index, target ->
                    Column(Modifier.padding(top = 12.dp)) {
                        Text(
                            label(target) + if (index == 0) " (default)" else "",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(target, style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (index > 0) {
                                TextButton(onClick = {
                                    save(options.copy(targets = listOf(target) + (options.targets - target)))
                                }) { Text("Make default") }
                            }
                            TextButton(onClick = { pinShortcut(context, target, label(target)) }) { Text("Pin") }
                            if (options.targets.size > 1) {
                                TextButton(onClick = { save(options.copy(targets = options.targets - target)) }) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { onNavigate(Screen.Picker) }) { Text("Add an app") }
            }

            Section("When fixing") {
                Toggle("Turn off developer options", options.disableDevOptions) {
                    save(options.copy(disableDevOptions = it))
                }
                Toggle("Turn off USB debugging", options.disableUsbDebugging) {
                    save(options.copy(disableUsbDebugging = it))
                }
                Toggle("Turn off wireless debugging", options.disableWirelessDebugging) {
                    save(options.copy(disableWirelessDebugging = it))
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Toggle("Kill Google Play Services", options.killPlayServices) {
                        save(options.copy(killPlayServices = it))
                    }
                } else {
                    Text(
                        "Android 14 and later don't let apps stop other apps, so PayReady can't restart " +
                            "GPay or Play Services. If GPay still says to restart, restart the phone once.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                SliderRow(
                    label = { "Wait ${it.roundToLong()} ms before opening the app" },
                    value = options.launchDelayMs.toFloat(),
                    range = 0f..3000f,
                    steps = 29,
                    onChange = { save(options.copy(launchDelayMs = it.roundToLong())) },
                )
            }

            Section("After paying") {
                Toggle("Restore when I leave the app", options.autoRestore) {
                    saveAndSync(options.copy(autoRestore = it))
                }
                Text(
                    "After you tap Fix, PayReady watches until you switch away, then restores " +
                        "and stops. It only runs while payment mode is on.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if ((options.autoMode || options.autoRestore) && !status.usageAccess) {
                    Text(
                        "Needs Usage Access to see which app is open.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                SliderRow(
                    label = { "Restore after ${it.roundToLong()} s in another app" },
                    value = options.restoreDebounceMs / 1000f,
                    range = 3f..60f,
                    steps = 56,
                    onChange = { save(options.copy(restoreDebounceMs = it.roundToLong() * 1000)) },
                )
                TextButton(onClick = { showAllowlist = true }) {
                    Text("Apps that count as still paying (${options.allowlist.size})")
                }
            }

            Section("Auto mode") {
                Toggle("Fix when a target app opens, restore when I leave", options.autoMode) {
                    saveAndSync(options.copy(autoMode = it))
                }
                Text(
                    "Runs all the time with its own notification. It may still hit the restart prompt, " +
                        "because the app can check before PayReady acts. The tile and the Pay now " +
                        "shortcut are the reliable way.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Shortcuts and automation") {
                Text(
                    "Add the PayReady tile from the Quick Settings editor, or long-press the app icon for Pay now. " +
                        "Use Pin on a target app to put a shortcut for it on the home screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val clipboard = LocalClipboardManager.current
                TextButton(onClick = {
                    scope.launch {
                        clipboard.setText(AnnotatedString(app.store.token()))
                        snackbar.showSnackbar("Token copied")
                    }
                }) { Text("Copy the broadcast token (MacroDroid/Tasker)") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNavigate(Screen.Log) }, modifier = Modifier.weight(1f)) {
                    Text("Activity log")
                }
                OutlinedButton(onClick = { onNavigate(Screen.Setup) }, modifier = Modifier.weight(1f)) {
                    Text("Setup")
                }
            }
        }
    }

    if (showAllowlist) {
        AllowlistDialog(
            current = options.allowlist,
            onDismiss = { showAllowlist = false },
            onSave = {
                save(options.copy(allowlist = it))
                showAllowlist = false
            },
        )
    }
}

/** Home-screen shortcut that fixes and opens [target]. */
private fun pinShortcut(context: Context, target: String, label: String) {
    val shortcut = ShortcutInfoCompat.Builder(context, "pay_$target")
        .setShortLabel("Pay: $label")
        .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
        .setIntent(
            Intent(context, TrampolineActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_TARGET, target)
        )
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}

@Composable
private fun PaymentModeBanner(stale: Boolean, onRestore: () -> Unit) {
    val container =
        if (stale) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer
    Card(colors = CardDefaults.cardColors(containerColor = container), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (stale) "You're still in payment mode" else "Payment mode is on",
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Accessibility and debugging stay off until you restore.")
            Button(onClick = onRestore) { Text("Restore") }
        }
    }
}

@Composable
private fun StatusCard(status: DeviceStatus, onUsageAccess: () -> Unit) {
    Section("Status") {
        StatusRow("Settings permission", if (status.canWriteSettings) "Granted" else "Not granted")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Usage Access", Modifier.weight(1f))
            if (status.usageAccess) Text("Granted") else TextButton(onClick = onUsageAccess) { Text("Grant") }
        }
        StatusRow("Notifications", if (status.notifications) "Allowed" else "Blocked")
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        StatusRow(
            "Accessibility services",
            if (status.accessibilityServices.isEmpty()) "None" else status.accessibilityServices.joinToString(),
        )
        StatusRow("Developer options", onOff(status.devOptions))
        StatusRow("USB debugging", onOff(status.usbDebugging))
        StatusRow("Wireless debugging", onOff(status.wirelessDebugging))
    }
}

private fun onOff(on: Boolean) = if (on) "On" else "Off"

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(top = 8.dp)) { content() }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(
    label: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    var position by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(top = 8.dp)) {
        Text(label(position))
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = { onChange(position) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun AllowlistDialog(current: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var text by remember { mutableStateOf(current.sorted().joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Still paying") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Auto mode won't restore while one of these apps is open. One package name per line.")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet())
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shared by the secondary screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { actions() },
            )
        },
        content = content,
    )
}
