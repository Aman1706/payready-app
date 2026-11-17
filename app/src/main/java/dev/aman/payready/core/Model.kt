package dev.aman.payready.core

const val DEFAULT_TARGET = "com.google.android.apps.nbu.paisa.user"
const val PLAY_SERVICES = "com.google.android.gms"

/** The five system settings PayReady reads and writes. Nothing else is ever touched. */
enum class Setting(val global: Boolean, val key: String, val off: String) {
    A11Y_SERVICES(false, "enabled_accessibility_services", ""),
    A11Y_ENABLED(false, "accessibility_enabled", "0"),
    DEV_OPTIONS(true, "development_settings_enabled", "0"),
    ADB(true, "adb_enabled", "0"),
    ADB_WIFI(true, "adb_wifi_enabled", "0"),
}

sealed interface WriteResult {
    data object Ok : WriteResult
    data object Denied : WriteResult
    data class Failed(val reason: String) : WriteResult
}

/** The only door to system settings. The Android implementation is in platform/AndroidSettings.kt. */
interface SettingsWriter {
    fun read(setting: Setting): String?
    fun write(setting: Setting, value: String?): WriteResult
}

/**
 * What the device looked like before prepare().
 * [values] holds all five settings; [changed] is the subset prepare() turns off, and so
 * the only ones restore() writes back.
 */
data class Snapshot(
    val values: Map<Setting, String?>,
    val changed: Set<Setting>,
    val takenAt: Long,
)

/** A stored snapshot means "prepared". */
interface SnapshotStore {
    suspend fun load(): Snapshot?
    /** Must not return until the snapshot is durably written. */
    suspend fun save(snapshot: Snapshot)
    suspend fun clear()
}

interface Platform {
    /** Whether this Android version lets PayReady stop other apps. False on Android 14+. */
    val canKillOtherApps: Boolean
    fun kill(pkg: String)
    fun launch(pkg: String): Boolean
    fun onPrepared(options: Options)
    fun onRestored()
}

fun interface ActionLog {
    suspend fun log(message: String)
}

data class Options(
    /** Apps PayReady fixes for. The first is the default, used by the tile and the Pay now shortcut. */
    val targets: List<String> = listOf(DEFAULT_TARGET),
    val disableDevOptions: Boolean = true,
    val disableUsbDebugging: Boolean = true,
    val disableWirelessDebugging: Boolean = true,
    val killPlayServices: Boolean = true,
    val launchDelayMs: Long = 800,
    val autoMode: Boolean = false,
    /** After a manual prepare, watch until the user leaves the target, then restore and stop watching. */
    val autoRestore: Boolean = true,
    val restoreDebounceMs: Long = 10_000,
    val allowlist: Set<String> = DEFAULT_ALLOWLIST,
) {
    val defaultTarget: String get() = targets.firstOrNull() ?: DEFAULT_TARGET

    /** [requested] if it is one of the user's targets, otherwise the default. Callers pass untrusted extras here. */
    fun targetOrDefault(requested: String?): String = requested?.takeIf { it in targets } ?: defaultTarget
}

/** Apps that count as "still paying" for Auto mode and auto-restore. Custom Tabs run inside the browser's package. */
val DEFAULT_ALLOWLIST = setOf(
    "com.google.android.apps.messaging",
    "com.android.messaging",
    "com.android.mms",
    "com.android.chrome",
)

sealed interface Outcome {
    data object Ok : Outcome
    data object NotPrepared : Outcome
    data object PermissionDenied : Outcome
    data class Failed(val reason: String) : Outcome
}
