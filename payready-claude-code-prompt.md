# Build "PayReady": a no-root Android utility that clears GPay's accessibility block

## Context
Google Pay India (and some bank apps) refuse payments while any third-party accessibility service is enabled. Turning the service off isn't enough: GPay still says "turn it off and restart", because Google Play Services caches the accessibility state, and Developer Options / USB debugging may also be flagged.

I use a button-remapper app that needs accessibility. I want one tap to: save the current state → turn off accessibility + dev options/debugging → kill Play Services and the target app → open the target app. Then one tap (or auto, on leaving the app) to restore exactly what was on before.

Target device: Nothing Phone on Nothing OS 5 (Android 17). No root, no Shizuku, no LSPosed. The only privileged permission is `WRITE_SECURE_SETTINGS`, granted once over ADB.

## Tech stack
- Kotlin, Jetpack Compose (Material 3), single-activity app
- minSdk 31, targetSdk/compileSdk = latest stable available in the installed SDK
- Package: `dev.aman.payready`
- DataStore (Preferences) for persisted state
- No INTERNET permission, no analytics, no third-party SDKs beyond AndroidX/Compose
- Gradle Kotlin DSL with a version catalog

## Permissions (manifest)
- `android.permission.WRITE_SECURE_SETTINGS` (granted via `adb shell pm grant dev.aman.payready android.permission.WRITE_SECURE_SETTINGS`)
- `android.permission.KILL_BACKGROUND_PROCESSES`
- `android.permission.PACKAGE_USAGE_STATS` (Usage Access, for Auto mode; user grants in Settings)
- `android.permission.POST_NOTIFICATIONS`
- `android.permission.FOREGROUND_SERVICE` + the matching specific type (`specialUse`) for the Auto-mode watcher
- `<queries>` for the default target and launcher intents, so the app picker works under package visibility

## Core logic: `PayReadyController`
Put this in one class with no UI dependencies, so it can be unit-tested and called from the activity, tile, shortcut and broadcast receiver.

### `prepare(target: String)`
1. **Save first, then change anything.** Read and persist (DataStore, written synchronously with commit semantics before any change):
   - `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` (string, may be null/empty)
   - `Settings.Secure.ACCESSIBILITY_ENABLED`
   - `Settings.Global.DEVELOPMENT_SETTINGS_ENABLED`
   - `Settings.Global.ADB_ENABLED`
   - `Settings.Global.ADB_WIFI_ENABLED` (string key `"adb_wifi_enabled"`)
   - a flag `isPrepared = true` and a timestamp

   If `isPrepared` is already true, do NOT overwrite the saved snapshot (protects against double taps saving the "off" state).
2. Set `enabled_accessibility_services` to empty, and `accessibility_enabled` to 0.
3. Set `adb_wifi_enabled`, `adb_enabled` and `development_settings_enabled` to 0, in that order. Make each of these individually toggleable in settings, defaulting ON.
4. `killBackgroundProcesses` on the target package and on `com.google.android.gms`.
5. Wait ~800 ms (configurable), then launch the target's launch intent with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`.
6. Show an ongoing notification: "Payment mode on — tap to restore", with a Restore action.

### `restore()`
- Only if `isPrepared`: write back exactly the saved values (restore dev options before adb), clear `isPrepared`, dismiss the notification.
- Never enable anything that wasn't on in the snapshot.

### Safety
- Every settings write goes through one helper that catches `SecurityException` and returns a typed result. The UI must show "Permission not granted" with the exact adb command and a copy button.
- On app start, if `isPrepared` is true and older than 30 minutes, show a prominent "You're still in payment mode — Restore" banner (don't auto-restore silently).
- Log every action to an in-app activity log (last 50 entries, local only) so I can debug what happened.

## Entry points
1. **Main screen (Compose):**
   - Status card: permission granted? Usage Access granted? Current state for each setting, with the enabled accessibility services listed by app label.
   - Big button: "Fix & open <target app label>"
   - "Restore everything" button
   - Target app picker (searchable list of launchable apps; default `com.google.android.apps.nbu.paisa.user`)
   - Toggles: disable dev options, disable USB debugging, disable wireless debugging, kill Play Services, launch delay slider
   - Auto mode switch
   - Activity log screen
   - Setup screen with the adb grant command (copyable) and a one-line explanation
2. **Quick Settings tile:** tap = prepare (or restore if already prepared); the label reflects the state.
3. **Static launcher shortcut** ("Pay now") and a pinned-shortcut option. Launching it runs prepare via a transparent trampoline activity, no UI flash.
4. **Broadcast receiver** for MacroDroid/Tasker. It must be exported, and a signature-level permission would block those apps, so instead require a random token extra (generated on first run, copyable from settings) and ignore broadcasts without it. Actions: `dev.aman.payready.PREPARE` and `dev.aman.payready.RESTORE`.

## Auto mode
- A foreground service (specialUse type, low-priority notification) polls `UsageStatsManager.queryEvents` every ~1 s for foreground changes.
- When the target comes to the foreground and the app isn't prepared → prepare, but skip the relaunch step (it's already open).
  - Note in the UI that auto mode may still hit the restart prompt, because GPay may check before we act. The shortcut/tile flow is the reliable path.
- When the foreground app is something else for >10 s (debounce, configurable) → restore. Treat known OTP/browser/bank-redirect packages as "still paying" (user-editable allowlist; default: SMS apps, Chrome, Custom Tabs).
- Stop the service when Auto mode is off.

## Deliverables
- A full project that builds with `./gradlew :app:assembleDebug`.
- A README with: what it does, the adb grant command, how to add the tile/shortcut, MacroDroid/Tasker broadcast usage, and caveats.
- Unit tests for the snapshot/restore logic, using a fake settings writer: double-prepare doesn't overwrite the snapshot; restore only re-enables what was on; restore is a no-op when not prepared.

## How to work
1. Check the installed Android SDK/JDK first and pick versions that build here.
2. Scaffold, implement `PayReadyController` + tests, then the UI, tile, shortcut, receiver, and finally Auto mode.
3. Run the build and the tests after each stage and fix failures before moving on.
4. If a device is attached (`adb devices`), install the debug APK, run the grant command, and verify with:
   - `adb shell settings get secure enabled_accessibility_services`
   - `adb shell settings get global adb_enabled`

   Warn me before any step that turns off ADB, since it will drop the connection.
5. Keep it small and readable. I want to be able to audit every line that touches secure settings.
