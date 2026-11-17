package dev.aman.payready.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * All of PayReady's behaviour. No UI or Android dependencies: the activity, tile, shortcut,
 * broadcast receiver and Auto-mode service all call this class.
 */
class PayReadyController(
    private val settings: SettingsWriter,
    private val store: SnapshotStore,
    private val platform: Platform,
    private val log: ActionLog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /** Save state, turn off accessibility and debugging, kill Play Services and the target, open the target. */
    suspend fun prepare(
        options: Options,
        target: String = options.defaultTarget,
        launch: Boolean = true,
    ): Outcome = mutex.withLock {
        // 1. Save first. An existing snapshot is never overwritten, so a second tap
        //    cannot save the "off" state.
        val existing = store.load()
        val snapshot = existing ?: Snapshot(
            values = Setting.entries.associateWith { settings.read(it) },
            changed = settingsToTurnOff(options),
            takenAt = clock(),
        ).also { store.save(it) }
        if (existing == null) {
            log.log("Prepare: saved " + snapshot.values.entries.joinToString { "${it.key.key}=${it.value}" })
        } else {
            log.log("Prepare: already in payment mode, keeping the saved snapshot")
        }

        // 2 + 3. Turn things off.
        for (setting in DISABLE_ORDER) {
            if (setting !in snapshot.changed) continue
            val result = settings.write(setting, setting.off)
            log.log("Set ${setting.key} to '${setting.off}': ${describe(result)}")
            if (result != WriteResult.Ok) {
                if (existing == null) restoreLocked() // undo this attempt
                return toOutcome(result)
            }
        }

        // 4. Kill the target and Play Services so they drop their cached state.
        if (platform.canKillOtherApps) {
            platform.kill(target)
            if (options.killPlayServices) platform.kill(PLAY_SERVICES)
            log.log("Asked Android to stop $target" + if (options.killPlayServices) " and $PLAY_SERVICES" else "")
        } else {
            log.log("Skipped stopping apps: Android 14+ only lets an app stop its own processes")
        }

        // 6. Notification and tile.
        platform.onPrepared(options)

        // 5. Open the target.
        if (launch) {
            delay(options.launchDelayMs)
            val launched = platform.launch(target)
            log.log(if (launched) "Opened $target" else "Could not open $target")
        }
        Outcome.Ok
    }

    /** Write back exactly what the snapshot recorded. Does nothing when not prepared. */
    suspend fun restore(): Outcome = mutex.withLock { restoreLocked() }

    private suspend fun restoreLocked(): Outcome {
        val snapshot = store.load() ?: return Outcome.NotPrepared
        var failure: WriteResult? = null
        for (setting in RESTORE_ORDER) {
            if (setting !in snapshot.changed) continue
            val saved = snapshot.values[setting]
            if (settings.read(setting) == saved) continue
            val result = settings.write(setting, saved)
            log.log("Restored ${setting.key} to '$saved': ${describe(result)}")
            if (result != WriteResult.Ok && failure == null) failure = result
        }
        // Keep the snapshot if anything failed, so restore can be tried again.
        failure?.let { return toOutcome(it) }
        store.clear()
        platform.onRestored()
        log.log("Restore finished")
        return Outcome.Ok
    }

    private fun settingsToTurnOff(options: Options): Set<Setting> = buildSet {
        add(Setting.A11Y_SERVICES)
        add(Setting.A11Y_ENABLED)
        if (options.disableWirelessDebugging) add(Setting.ADB_WIFI)
        if (options.disableUsbDebugging) add(Setting.ADB)
        if (options.disableDevOptions) add(Setting.DEV_OPTIONS)
    }

    private fun describe(result: WriteResult) = when (result) {
        WriteResult.Ok -> "ok"
        WriteResult.Denied -> "permission not granted"
        is WriteResult.Failed -> "failed (${result.reason})"
    }

    private fun toOutcome(result: WriteResult): Outcome = when (result) {
        WriteResult.Ok -> Outcome.Ok
        WriteResult.Denied -> Outcome.PermissionDenied
        is WriteResult.Failed -> Outcome.Failed(result.reason)
    }

    companion object {
        val DISABLE_ORDER = listOf(
            Setting.A11Y_SERVICES, Setting.A11Y_ENABLED,
            Setting.ADB_WIFI, Setting.ADB, Setting.DEV_OPTIONS,
        )
        /** Dev options come back before adb. */
        val RESTORE_ORDER = listOf(
            Setting.A11Y_SERVICES, Setting.A11Y_ENABLED,
            Setting.DEV_OPTIONS, Setting.ADB, Setting.ADB_WIFI,
        )
    }
}
