package dev.aman.payready.platform

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.aman.payready.core.ActionLog
import dev.aman.payready.core.DEFAULT_ALLOWLIST
import dev.aman.payready.core.DEFAULT_TARGET
import dev.aman.payready.core.Options
import dev.aman.payready.core.Setting
import dev.aman.payready.core.Snapshot
import dev.aman.payready.core.SnapshotStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.dataStore by preferencesDataStore("payready")

data class LogEntry(val time: Long, val message: String)

/** Everything PayReady persists: the snapshot, the options, the broadcast token and the activity log. */
class Store(context: Context) : SnapshotStore, ActionLog {
    private val data = context.dataStore

    // --- Snapshot ---

    /** When payment mode was turned on, or null when it is off. */
    val preparedAt: Flow<Long?> = data.data.map { if (it[PREPARED] == true) it[PREPARED_AT] ?: 0L else null }

    override suspend fun load(): Snapshot? {
        val prefs = data.data.first()
        if (prefs[PREPARED] != true) return null
        return Snapshot(
            values = Setting.entries.associateWith { prefs[snapshotKey(it)] },
            changed = prefs[CHANGED].orEmpty().mapNotNull { name -> Setting.entries.find { it.name == name } }.toSet(),
            takenAt = prefs[PREPARED_AT] ?: 0L,
        )
    }

    /** edit() returns only after the data is written to disk. */
    override suspend fun save(snapshot: Snapshot) {
        data.edit { prefs ->
            for ((setting, value) in snapshot.values) {
                if (value == null) prefs.remove(snapshotKey(setting)) else prefs[snapshotKey(setting)] = value
            }
            prefs[CHANGED] = snapshot.changed.map { it.name }.toSet()
            prefs[PREPARED_AT] = snapshot.takenAt
            prefs[PREPARED] = true
        }
    }

    override suspend fun clear() {
        data.edit { prefs ->
            prefs.remove(PREPARED)
            prefs.remove(PREPARED_AT)
            prefs.remove(CHANGED)
            Setting.entries.forEach { prefs.remove(snapshotKey(it)) }
        }
    }

    // --- Options ---

    val options: Flow<Options> = data.data.map { prefs ->
        val defaults = Options()
        Options(
            targets = prefs[TARGETS]?.split('\n')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: listOf(prefs[OLD_TARGET] ?: DEFAULT_TARGET),
            disableDevOptions = prefs[DISABLE_DEV] ?: defaults.disableDevOptions,
            disableUsbDebugging = prefs[DISABLE_ADB] ?: defaults.disableUsbDebugging,
            disableWirelessDebugging = prefs[DISABLE_ADB_WIFI] ?: defaults.disableWirelessDebugging,
            killPlayServices = prefs[KILL_GMS] ?: defaults.killPlayServices,
            launchDelayMs = prefs[LAUNCH_DELAY] ?: defaults.launchDelayMs,
            autoMode = prefs[AUTO_MODE] ?: defaults.autoMode,
            autoRestore = prefs[AUTO_RESTORE] ?: defaults.autoRestore,
            restoreDebounceMs = prefs[DEBOUNCE] ?: defaults.restoreDebounceMs,
            allowlist = prefs[ALLOWLIST] ?: DEFAULT_ALLOWLIST,
        )
    }

    suspend fun setOptions(options: Options) {
        data.edit { prefs ->
            prefs[TARGETS] = options.targets.distinct().joinToString("\n")
            prefs.remove(OLD_TARGET)
            prefs[DISABLE_DEV] = options.disableDevOptions
            prefs[DISABLE_ADB] = options.disableUsbDebugging
            prefs[DISABLE_ADB_WIFI] = options.disableWirelessDebugging
            prefs[KILL_GMS] = options.killPlayServices
            prefs[LAUNCH_DELAY] = options.launchDelayMs
            prefs[AUTO_MODE] = options.autoMode
            prefs[AUTO_RESTORE] = options.autoRestore
            prefs[DEBOUNCE] = options.restoreDebounceMs
            prefs[ALLOWLIST] = options.allowlist
        }
    }

    // --- Broadcast token ---

    /** Random token that PREPARE/RESTORE broadcasts must carry. Generated on first use. */
    suspend fun token(): String =
        data.edit { prefs ->
            if (prefs[TOKEN] == null) {
                val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                prefs[TOKEN] = bytes.joinToString("") { "%02x".format(it) }
            }
        }[TOKEN]!!

    // --- Activity log (last 50 entries, stays on the device) ---

    val logEntries: Flow<List<LogEntry>> = data.data.map { prefs ->
        prefs[LOG].orEmpty().lines().filter { it.isNotBlank() }.map {
            LogEntry(it.substringBefore('\t').toLongOrNull() ?: 0L, it.substringAfter('\t'))
        }
    }

    override suspend fun log(message: String) {
        val line = "${System.currentTimeMillis()}\t${message.replace('\n', ' ')}"
        data.edit { prefs ->
            prefs[LOG] = (prefs[LOG].orEmpty().lines().filter { it.isNotBlank() } + line)
                .takeLast(MAX_LOG_ENTRIES).joinToString("\n")
        }
    }

    suspend fun clearLog() {
        data.edit { it.remove(LOG) }
    }

    private fun snapshotKey(setting: Setting): Preferences.Key<String> = stringPreferencesKey("snapshot_${setting.key}")

    private companion object {
        const val MAX_LOG_ENTRIES = 50
        val PREPARED = booleanPreferencesKey("is_prepared")
        val PREPARED_AT = longPreferencesKey("prepared_at")
        val CHANGED = stringSetPreferencesKey("snapshot_changed")
        val TARGETS = stringPreferencesKey("targets") // newline-separated, ordered; first is the default
        val OLD_TARGET = stringPreferencesKey("target") // single target from version 1
        val DISABLE_DEV = booleanPreferencesKey("disable_dev_options")
        val DISABLE_ADB = booleanPreferencesKey("disable_usb_debugging")
        val DISABLE_ADB_WIFI = booleanPreferencesKey("disable_wireless_debugging")
        val KILL_GMS = booleanPreferencesKey("kill_play_services")
        val LAUNCH_DELAY = longPreferencesKey("launch_delay_ms")
        val AUTO_MODE = booleanPreferencesKey("auto_mode")
        val AUTO_RESTORE = booleanPreferencesKey("auto_restore")
        val DEBOUNCE = longPreferencesKey("restore_debounce_ms")
        val ALLOWLIST = stringSetPreferencesKey("allowlist")
        val TOKEN = stringPreferencesKey("broadcast_token")
        val LOG = stringPreferencesKey("activity_log")
    }
}
