package dev.aman.payready.platform

import android.content.ContentResolver
import android.provider.Settings
import dev.aman.payready.core.Setting
import dev.aman.payready.core.SettingsWriter
import dev.aman.payready.core.WriteResult

/**
 * The only code in the app that writes system settings.
 * Every write needs WRITE_SECURE_SETTINGS; without it the write is reported as Denied.
 */
class AndroidSettings(private val resolver: ContentResolver) : SettingsWriter {

    override fun read(setting: Setting): String? =
        if (setting.global) Settings.Global.getString(resolver, setting.key)
        else Settings.Secure.getString(resolver, setting.key)

    override fun write(setting: Setting, value: String?): WriteResult = try {
        val written =
            if (setting.global) Settings.Global.putString(resolver, setting.key, value)
            else Settings.Secure.putString(resolver, setting.key, value)
        if (written) WriteResult.Ok else WriteResult.Failed("the system rejected the write")
    } catch (e: SecurityException) {
        WriteResult.Denied
    }
}
