package dev.aman.payready.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import dev.aman.payready.app
import dev.aman.payready.core.Setting

const val GRANT_COMMAND = "adb shell pm grant dev.aman.payready android.permission.WRITE_SECURE_SETTINGS"

/** What the status card shows. Read-only. */
data class DeviceStatus(
    val canWriteSettings: Boolean,
    val usageAccess: Boolean,
    val notifications: Boolean,
    val accessibilityServices: List<String>,
    val devOptions: Boolean,
    val usbDebugging: Boolean,
    val wirelessDebugging: Boolean,
)

fun readDeviceStatus(context: Context): DeviceStatus {
    val settings = context.app.settings
    val appOps = context.getSystemService(AppOpsManager::class.java)
    return DeviceStatus(
        canWriteSettings = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED,
        usageAccess = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
        ) == AppOpsManager.MODE_ALLOWED,
        notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        accessibilityServices = settings.read(Setting.A11Y_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }
            .map { ComponentName.unflattenFromString(it)?.packageName ?: it }
            .distinct()
            .map { appLabel(context, it) },
        devOptions = settings.read(Setting.DEV_OPTIONS) == "1",
        usbDebugging = settings.read(Setting.ADB) == "1",
        wirelessDebugging = settings.read(Setting.ADB_WIFI) == "1",
    )
}

fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)
