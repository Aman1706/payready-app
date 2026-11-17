# PayReady

Google Pay India (and some bank apps) refuse to pay while a third-party accessibility service is on, and keep refusing after you turn it off. PayReady clears the block with one tap and puts everything back afterwards. It needs no root.

One tap on **Fix & open**:

1. saves the current accessibility and debugging settings
2. turns off accessibility services, wireless debugging, USB debugging and developer options
3. on Android 13 and older, stops Google Play Services and the payment app, so they drop their cached state
4. opens the payment app

One tap on **Restore** writes back exactly what was saved. It never turns on anything that was off before.

PayReady has no internet permission, no analytics and no third-party SDKs.

## Build and install

You need JDK 17 and the Android SDK (platform 35).

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release build

```
./gradlew :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk
```

Release builds are minified and signed with `keystore/payready-release.jks`, using the passwords in `keystore.properties`. Neither file is in source control. Back both up: an update must be signed with the same key, or Android makes you uninstall first, which loses the permission grant and your settings. Without `keystore.properties`, the release APK is unsigned.

## Grant the permission (once)

PayReady needs `WRITE_SECURE_SETTINGS`, which Android only grants over adb:

```
adb shell pm grant dev.aman.payready android.permission.WRITE_SECURE_SETTINGS
```

The grant survives reboots and app updates. Uninstalling the app removes it. The Setup screen in the app shows the same command with a copy button.

Fixing turns off USB debugging, so the adb connection drops until you restore.

## Target apps

Add any number of apps (GPay, bank apps) under **Target apps**. Each gets its own **Fix & open** button. The first one is the default, used by the tile, the Pay now shortcut and broadcasts without a target. Tap **Pin** on any of them for a home-screen shortcut that fixes and opens that app. Restore is the same whichever app you fixed for.

## Ways to run it

- **App:** the big **Fix & open** button, and **Restore everything**.
- **Quick Settings tile:** edit your Quick Settings and drag in the **Pay now** tile. Tap it to fix and open the app. While payment mode is on, the tile reads **Restore**.
- **Launcher shortcut:** long-press the PayReady icon and choose **Pay now** (default app), or tap **Pin** next to a target app.
- **Notification:** while payment mode is on, tap the notification or its **Restore** action.
- **Auto mode:** fixes when any target app opens and restores about 10 seconds after you leave it. It needs Usage Access. SMS apps and Chrome count as "still paying"; you can edit that list. It checks which app is in front every 3 seconds (every second while payment mode is on) and does nothing while the screen is off.

## MacroDroid and Tasker

Send a broadcast with the token as a string extra. Copy the token from **Copy the broadcast token** in the app. Broadcasts without the right token are ignored.

| Field   | Value                                                         |
|---------|---------------------------------------------------------------|
| Action  | `dev.aman.payready.PREPARE` or `dev.aman.payready.RESTORE`    |
| Package | `dev.aman.payready`                                           |
| Extra   | `token` = your token (string)                                 |
| Extra (optional) | `target` = package to open, e.g. `com.example.bank`. Must be in your target list; otherwise the default is used. |

From adb:

```
adb shell am broadcast -a dev.aman.payready.PREPARE -p dev.aman.payready --es token YOUR_TOKEN
```

Android doesn't let a background broadcast open another app, so `PREPARE` changes the settings and kills the apps but may not open the payment app. Add a "launch app" step to your macro about a second after the broadcast.

## Caveats

- **Auto mode can be too late.** The payment app may check before PayReady acts, so you can still see the restart prompt. The tile and the shortcut are the reliable way.
- **Android 14+ can't stop other apps.** `killBackgroundProcesses` only works on the caller's own app since Android 14, and force-stop needs a system permission adb can't grant. On newer phones PayReady skips this step. If GPay still says "restart your phone", restart once while in payment mode.
- **Wireless debugging may not come back on its own.** Android can refuse to re-enable it unless you're on a Wi-Fi network you've approved before.
- **Accessibility services restart, they don't resume.** Your remapper is re-enabled on restore, but it starts fresh.
- **If you forget to restore,** the app shows a banner when you next open it after 30 minutes. It doesn't restore silently.
- **The activity log** keeps the last 50 actions on the device, so you can see what was changed and when.

## Tests

```
./gradlew :app:testDebugUnitTest
```

The tests cover the save and restore logic in `PayReadyController` with a fake settings writer.
