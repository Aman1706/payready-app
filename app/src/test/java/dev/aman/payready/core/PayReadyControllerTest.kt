package dev.aman.payready.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val REMAPPER = "com.example.remap/.RemapService"

class PayReadyControllerTest {

    private class FakeStore : SnapshotStore {
        var snapshot: Snapshot? = null
        override suspend fun load() = snapshot
        override suspend fun save(snapshot: Snapshot) { this.snapshot = snapshot }
        override suspend fun clear() { snapshot = null }
    }

    private class FakeSettings(private val store: FakeStore) : SettingsWriter {
        val values = mutableMapOf<Setting, String?>(
            Setting.A11Y_SERVICES to REMAPPER,
            Setting.A11Y_ENABLED to "1",
            Setting.DEV_OPTIONS to "1",
            Setting.ADB to "1",
            Setting.ADB_WIFI to "0",
        )
        val writes = mutableListOf<Pair<Setting, String?>>()
        var denied = false
        var snapshotExistedAtFirstWrite: Boolean? = null

        override fun read(setting: Setting) = values[setting]
        override fun write(setting: Setting, value: String?): WriteResult {
            if (snapshotExistedAtFirstWrite == null) snapshotExistedAtFirstWrite = store.snapshot != null
            if (denied) return WriteResult.Denied
            writes += setting to value
            values[setting] = value
            return WriteResult.Ok
        }
    }

    private class FakePlatform : Platform {
        override var canKillOtherApps = true
        val killed = mutableListOf<String>()
        val launched = mutableListOf<String>()
        var notificationShown = false
        override fun kill(pkg: String) { killed += pkg }
        override fun launch(pkg: String): Boolean { launched += pkg; return true }
        override fun onPrepared(options: Options) { notificationShown = true }
        override fun onRestored() { notificationShown = false }
    }

    private val store = FakeStore()
    private val settings = FakeSettings(store)
    private val platform = FakePlatform()
    private val controller = PayReadyController(settings, store, platform, ActionLog { }, clock = { 42L })
    private val options = Options()

    @Test
    fun `prepare saves the snapshot before the first write`() = runTest {
        assertEquals(Outcome.Ok, controller.prepare(options))
        assertEquals(true, settings.snapshotExistedAtFirstWrite)
        assertEquals(REMAPPER, store.snapshot!!.values[Setting.A11Y_SERVICES])
        assertEquals(42L, store.snapshot!!.takenAt)
    }

    @Test
    fun `prepare turns everything off in order, kills and launches`() = runTest {
        controller.prepare(options)
        assertEquals(
            listOf(
                Setting.A11Y_SERVICES to "", Setting.A11Y_ENABLED to "0",
                Setting.ADB_WIFI to "0", Setting.ADB to "0", Setting.DEV_OPTIONS to "0",
            ),
            settings.writes,
        )
        assertEquals(listOf(DEFAULT_TARGET, PLAY_SERVICES), platform.killed)
        assertEquals(listOf(DEFAULT_TARGET), platform.launched)
        assertTrue(platform.notificationShown)
    }

    @Test
    fun `double prepare does not overwrite the snapshot`() = runTest {
        controller.prepare(options)
        val first = store.snapshot
        controller.prepare(options)
        assertEquals(first, store.snapshot)
        assertEquals(REMAPPER, store.snapshot!!.values[Setting.A11Y_SERVICES])
        assertEquals("1", store.snapshot!!.values[Setting.ADB])

        controller.restore()
        assertEquals(REMAPPER, settings.values[Setting.A11Y_SERVICES])
        assertEquals("1", settings.values[Setting.ADB])
    }

    @Test
    fun `restore only re-enables what was on`() = runTest {
        settings.values[Setting.ADB] = "0"
        controller.prepare(options)
        settings.writes.clear()

        assertEquals(Outcome.Ok, controller.restore())

        assertEquals(REMAPPER, settings.values[Setting.A11Y_SERVICES])
        assertEquals("1", settings.values[Setting.A11Y_ENABLED])
        assertEquals("1", settings.values[Setting.DEV_OPTIONS])
        assertEquals("0", settings.values[Setting.ADB])
        assertEquals("0", settings.values[Setting.ADB_WIFI])
        assertFalse(settings.writes.any { it.first == Setting.ADB || it.first == Setting.ADB_WIFI })
        assertNull(store.snapshot)
        assertFalse(platform.notificationShown)
    }

    @Test
    fun `restore brings dev options back before adb`() = runTest {
        controller.prepare(options)
        settings.writes.clear()
        controller.restore()
        val order = settings.writes.map { it.first }
        assertTrue(order.indexOf(Setting.DEV_OPTIONS) < order.indexOf(Setting.ADB))
    }

    @Test
    fun `restore is a no-op when not prepared`() = runTest {
        val before = settings.values.toMap()
        assertEquals(Outcome.NotPrepared, controller.restore())
        assertTrue(settings.writes.isEmpty())
        assertEquals(before, settings.values)
    }

    @Test
    fun `a setting whose toggle is off is never written`() = runTest {
        controller.prepare(options.copy(disableUsbDebugging = false, killPlayServices = false))
        settings.values[Setting.ADB] = "0" // changed by the user while in payment mode
        controller.restore()
        assertFalse(settings.writes.any { it.first == Setting.ADB })
        assertEquals("0", settings.values[Setting.ADB])
        assertEquals(listOf(DEFAULT_TARGET), platform.killed)
    }

    @Test
    fun `missing permission is reported and leaves nothing prepared`() = runTest {
        settings.denied = true
        assertEquals(Outcome.PermissionDenied, controller.prepare(options))
        assertNull(store.snapshot)
        assertTrue(platform.killed.isEmpty())
        assertTrue(platform.launched.isEmpty())
    }

    @Test
    fun `prepare opens the requested target, and restore works the same`() = runTest {
        val bank = "com.example.bank"
        controller.prepare(options.copy(targets = listOf(DEFAULT_TARGET, bank)), target = bank)
        assertEquals(listOf(bank), platform.launched)
        assertEquals(listOf(bank, PLAY_SERVICES), platform.killed)
        assertEquals(Outcome.Ok, controller.restore())
        assertEquals(REMAPPER, settings.values[Setting.A11Y_SERVICES])
    }

    @Test
    fun `an unknown requested target falls back to the default`() {
        val o = options.copy(targets = listOf("a", "b"))
        assertEquals("b", o.targetOrDefault("b"))
        assertEquals("a", o.targetOrDefault("evil.app"))
        assertEquals("a", o.targetOrDefault(null))
        assertEquals(DEFAULT_TARGET, options.copy(targets = emptyList()).defaultTarget)
    }

    @Test
    fun `nothing is killed when Android does not allow it`() = runTest {
        platform.canKillOtherApps = false
        assertEquals(Outcome.Ok, controller.prepare(options))
        assertTrue(platform.killed.isEmpty())
        assertEquals(listOf(DEFAULT_TARGET), platform.launched)
    }

    @Test
    fun `prepare without launch does not open the target`() = runTest {
        controller.prepare(options, launch = false)
        assertTrue(platform.launched.isEmpty())
        assertNotNull(store.snapshot)
    }
}
