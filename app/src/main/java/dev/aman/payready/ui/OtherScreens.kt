package dev.aman.payready.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.aman.payready.app
import dev.aman.payready.core.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class AppEntry(val label: String, val pkg: String, val icon: ImageBitmap)

@Composable
fun PickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by context.app.store.options.collectAsStateWithLifecycle(Options())
    var query by remember { mutableStateOf("") }

    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.Default) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != context.packageName }
                .map {
                    AppEntry(
                        label = it.loadLabel(pm).toString(),
                        pkg = it.activityInfo.packageName,
                        icon = it.loadIcon(pm).toBitmap(96, 96).asImageBitmap(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    SubScreen("Add an app", onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            val all = apps
            if (all == null) {
                Text("Loading apps…", Modifier.padding(16.dp))
            } else {
                val shown = all.filter {
                    it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown, key = { it.pkg }) { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = entry.pkg !in current.targets) {
                                    scope.launch {
                                        val store = context.app.store
                                        val options = store.options.first()
                                        store.setOptions(options.copy(targets = options.targets + entry.pkg))
                                        onBack()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Image(entry.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                                Text(entry.pkg, style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.pkg in current.targets) Text("Added", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LogScreen(onBack: () -> Unit) {
    val store = LocalContext.current.app.store
    val scope = rememberCoroutineScope()
    val entries by store.logEntries.collectAsStateWithLifecycle(emptyList())
    val format = remember { SimpleDateFormat("d MMM HH:mm:ss", Locale.getDefault()) }

    SubScreen(
        title = "Activity log",
        onBack = onBack,
        actions = { TextButton(onClick = { scope.launch { store.clearLog() } }) { Text("Clear") } },
    ) { padding ->
        if (entries.isEmpty()) {
            Text("Nothing has happened yet.", Modifier.padding(padding).padding(16.dp))
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(entries.asReversed()) { entry ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            format.format(Date(entry.time)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var status by remember { mutableStateOf(readDeviceStatus(context)) }
    LifecycleResumeEffect(Unit) {
        status = readDeviceStatus(context)
        onPauseOrDispose { }
    }

    SubScreen("Setup", onBack) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (status.canWriteSettings) "Permission granted. You're set." else "Permission not granted",
                style = MaterialTheme.typography.titleMedium,
                color = if (status.canWriteSettings) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            Text(
                "PayReady needs permission to change system settings. Android only gives it over adb. " +
                    "Connect the phone to a computer with USB debugging on and run this once:"
            )
            Card(Modifier.fillMaxWidth()) {
                Text(
                    GRANT_COMMAND,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { clipboard.setText(AnnotatedString(GRANT_COMMAND)) }) { Text("Copy command") }
                TextButton(onClick = { status = readDeviceStatus(context) }) { Text("Check again") }
            }
            Text(
                "The permission survives reboots and app updates. Uninstalling PayReady removes it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
