# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

PayReady is a no-root Android utility (package `dev.aman.payready`). Google Pay India refuses payments while a third-party accessibility service is enabled, so with one tap PayReady:

1. saves the current state
2. turns off accessibility, dev options and USB/wireless debugging
3. kills Play Services (`com.google.android.gms`) and the target app (Android 13 and older only; see Layout)
4. relaunches the target

A second tap restores exactly what was on before. The only privileged permission is `WRITE_SECURE_SETTINGS`, granted once over ADB:

```
adb shell pm grant dev.aman.payready android.permission.WRITE_SECURE_SETTINGS
```

The original spec is `payready-claude-code-prompt.md`. Target device: Nothing Phone, Nothing OS 5 (Android 17). Stack: Kotlin, Jetpack Compose (Material 3), single activity, minSdk 31, DataStore Preferences, Gradle Kotlin DSL with a version catalog. Hard constraints:

- no INTERNET permission
- no analytics
- no third-party SDKs beyond AndroidX/Compose
- no root, Shizuku or LSPosed

## Commands

There is no system Java, so set `JAVA_HOME` first. The SDK path is in `local.properties` (platform 35 only, which is why compileSdk/targetSdk are 35).

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug                      # build (must pass)
./gradlew :app:testDebugUnitTest                  # unit tests
./gradlew :app:testDebugUnitTest --tests '*PayReadyControllerTest'                 # one class
./gradlew :app:testDebugUnitTest --tests '*PayReadyControllerTest.restore is a no-op*'   # one test
./gradlew :app:installDebug                       # install on attached device
./gradlew :app:assembleRelease                    # minified, signed via keystore.properties (gitignored)
./gradlew :app:assembleRelease -Pr8test           # same, as dev.aman.payready.r8test, to smoke-test R8 beside the real app
adb shell settings get secure enabled_accessibility_services     # verify state on device
adb shell settings get global adb_enabled
```

Versions are pinned in `gradle/libs.versions.toml` (AGP 8.7.3, Kotlin 2.0.21, Gradle 8.10.2).

**Warn the user before any on-device step that turns off ADB. Running `prepare` does this, and it drops the adb connection.**

## Layout

- `core/` is plain Kotlin with no Android imports: `PayReadyController`, and in `Model.kt` the `Setting` enum (the only five settings ever touched) and the `SettingsWriter`, `SnapshotStore`, `Platform` and `ActionLog` interfaces. Unit tests fake these.
- `platform/` holds the Android implementations. `AndroidSettings` is the single settings-write helper. `Store` is one DataStore holding the snapshot, options, broadcast token and activity log. `AndroidPlatform` does kill, launch, notification and tile refresh.
- `App` wires these together; reach them with `context.app`. Use `app.scope` for work that must outlive the screen that started it.
- A stored snapshot is what "prepared" means; there is no separate flag to keep in sync.
- Background code can't open another app, so the tile and shortcuts go through the transparent `TrampolineActivity`, which runs `prepare` and finishes. The broadcast receiver can't do this, so its launch step may be blocked.
- `Platform.canKillOtherApps` is false on API 34+, where `killBackgroundProcesses` silently ignores other packages (confirmed on the target phone). The controller skips killing there rather than log a kill that didn't happen.
- Tested on the target phone (Android 17), 2026-11-16: turning off accessibility alone left GPay blocked, even after force-stopping GPay and Play Services from adb. With developer options and USB debugging also off, payments worked. So the dev-options and USB-debugging toggles are what make it work; don't weaken their defaults.
- `Options.targets` is an ordered list; the first is the default (tile, Pay now shortcut, broadcasts without a `target` extra). Pinned shortcuts and broadcasts pass `EXTRA_TARGET`, which goes through `Options.targetOrDefault` so an outside caller can only open an app the user added. There is one snapshot regardless of target.
- `AutoModeService` polls (no non-root API delivers foreground changes for other apps without an accessibility service). It sleeps while the screen is off, polls every 3 s when idle and every 1 s while prepared, and reads options/prepared state from `stateIn` flows rather than DataStore on each tick. Measured on the phone (debug build): about 0.1 s of CPU per minute with the screen on, about 0 with it off.
- `AutoModeService` also has an auto-restore mode (`Options.autoRestore`, on by default): `onPrepared` starts it, it only restores (never prepares), runs under the payment notification (id 1) instead of its own, and stops once not prepared. `AutoModeService.sync(context, options, prepared)` decides whether it should run; it can't start from a background broadcast, so `MainActivity.onStart` calls it too. Tested on the phone 2026-11-17: Home from GPay restored after the 10 s debounce and the service exited.
- `Snapshot.changed` records which settings `prepare` turned off (it depends on the toggles). `restore` writes back only those.

## Architecture

All behavior goes through one UI-free class, `PayReadyController`. The activity, the Quick Settings tile, the launcher shortcut (through a transparent trampoline activity) and the exported broadcast receiver all call it. The Auto-mode foreground service calls it too. Keep the logic out of these entry points so the controller stays unit-testable against a fake settings writer.

Invariants that matter (the user audits every line that touches secure settings):

- **Snapshot before mutation.** `prepare()` first persists these values:
  - `enabled_accessibility_services`
  - `accessibility_enabled`
  - `development_settings_enabled`
  - `adb_enabled`
  - `adb_wifi_enabled`
  - `isPrepared` and a timestamp

  It writes them to DataStore and waits for the write to commit before changing anything. If `isPrepared` is already true, it must not overwrite the snapshot, so a double tap can't save the "off" state.
- **Write order.** Disable in this order: `adb_wifi_enabled`, `adb_enabled`, `development_settings_enabled`. On restore, re-enable dev options before adb.
- **Restore is exact.** It runs only when `isPrepared`. It writes back only the snapshot values and never enables anything that was off. When not prepared, it does nothing.
- **Single write helper.** Every settings write goes through one helper. The helper catches `SecurityException` and returns a typed result. The UI shows the adb grant command with a copy button.
- **No silent auto-restore on startup.** If the app has been prepared for more than 30 minutes, show a banner instead.
- **Local activity log.** Keep the last 50 actions for debugging.

Other entry points:

- **Broadcast receiver.** It's exported, so MacroDroid and Tasker can reach it. Actions are `dev.aman.payready.PREPARE` and `dev.aman.payready.RESTORE`. Instead of a signature permission, it's gated by a random token extra generated on first run and ignores broadcasts without that token.
- **Auto mode.** A `specialUse` foreground service polls `UsageStatsManager.queryEvents` every ~1 s.
  - When the target comes to the foreground, it prepares without relaunching.
  - When another app stays in the foreground for more than ~10 s, it restores. Packages on a user-editable allowlist count as "still paying"; the default list is SMS apps, Chrome and Custom Tabs.
  - Auto mode is best-effort. The tile and shortcut are the reliable path.

Unit tests the spec requires, all for the snapshot/restore logic:

- double-prepare doesn't overwrite the snapshot
- restore re-enables only what was on
- restore does nothing when not prepared
