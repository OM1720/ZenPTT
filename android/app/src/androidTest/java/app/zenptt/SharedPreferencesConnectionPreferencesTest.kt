package app.zenptt

import app.zenptt.headset.*

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesConnectionPreferencesTest {
    @Test
    fun migratesTheExistingMediaAssignmentIntoAnExplicitSetup() = withPreferences { context, preferences ->
        preferences.edit().putBoolean("ptt_button_enabled", true).putInt("ptt_button_key_code", 85)
            .putString("ptt_button_behavior", "Toggle").commit()
        val expected = HeadsetSettings(true, HeadsetSetup.media(85, ButtonBehavior.Toggle))
        assertEquals(expected, SharedPreferencesConnectionPreferences(context).loadHeadsetSettings())
        preferences.edit().putBoolean("bm008_enabled", false).putInt("ptt_button_key_code", 24).commit()
        assertEquals(expected, SharedPreferencesConnectionPreferences(context).loadHeadsetSettings())
    }
    @Test
    fun migratesHardwareSelectionAndPreservesAssignedButtonAcrossReload() = withPreferences { context, preferences ->
        preferences.edit().putBoolean("bm008_enabled", true).commit()
        val store = SharedPreferencesConnectionPreferences(context)
        assertEquals(HeadsetSettings(enabled = true), store.loadHeadsetSettings())
        val assigned = HeadsetSettings(true, HeadsetSetup.media(85, ButtonBehavior.Toggle))
        store.saveHeadsetSettings(assigned)
        assertEquals(assigned, SharedPreferencesConnectionPreferences(context).loadHeadsetSettings())
        store.saveHeadsetSettings(assigned.copy(enabled = false))
        assertEquals(assigned.copy(enabled = false), store.loadHeadsetSettings())
        store.saveHeadsetSettings(HeadsetSettings())
        assertEquals(HeadsetSettings(), store.loadHeadsetSettings())
        store.saveHeadsetSettings(assigned)
        assertEquals(assigned, store.loadHeadsetSettings())
    }

    @Test
    fun corruptAssignmentStaysDisabledAndCannotFallBackToBm008() = withPreferences { context, preferences ->
        preferences.edit().putBoolean("ptt_button_enabled", true)
            .putInt("ptt_button_key_code", 24).putString("ptt_button_behavior", "Hold").commit()
        val store = SharedPreferencesConnectionPreferences(context)
        val invalid = store.loadHeadsetSettings()
        assertFalse(invalid.valid)
        assertFalse(invalid.enabled)
        store.saveHeadsetSettings(HeadsetSettings())
        store.saveHeadsetSettings(invalid)
        assertEquals(invalid, SharedPreferencesConnectionPreferences(context).loadHeadsetSettings())
    }

    @Test
    fun firstLaunchUsesEchoAndCurrentDefaults() = withPreferences { context, preferences ->
        val store = SharedPreferencesConnectionPreferences(context)

        assertEquals(DEFAULT_SERVER_ADDRESS, store.load())
        assertEquals(DEFAULT_POWER_SAVE_TIMEOUT_MINUTES, store.loadPowerSaveTimeoutMinutes())
        assertEquals(ECHO_CHANNEL, store.loadLastChannel())
        assertEquals(DEFAULT_BM008_ENABLED, store.loadHeadsetSettings().enabled)
        assertEquals(listOf(ECHO_CHANNEL), store.loadFrequencyChoices())
        assertCurrentDefaults(preferences)
    }

    @Test
    fun migratesLegacyDefaultsWithoutSnapshots() = withPreferences { context, preferences ->
        preferences.edit()
            .putString("server_address", "wss://previous-default.example")
            .putInt("power_save_timeout_minutes", 10)
            .putBoolean("bm008_enabled", false)
            .commit()

        val store = SharedPreferencesConnectionPreferences(
            context,
            preSnapshotServerAddress = "wss://previous-default.example",
        )

        assertEquals(DEFAULT_SERVER_ADDRESS, store.load())
        assertEquals(DEFAULT_POWER_SAVE_TIMEOUT_MINUTES, store.loadPowerSaveTimeoutMinutes())
        assertEquals(DEFAULT_BM008_ENABLED, store.loadHeadsetSettings().enabled)
        assertCurrentDefaults(preferences)
    }

    @Test
    fun migratesLegacyLocalDefault() = withPreferences { context, preferences ->
        preferences.edit().putString("server_address", "ws://192.0.2.139:8000").commit()

        val store = SharedPreferencesConnectionPreferences(
            context,
            legacyServerAddress = "ws://192.0.2.139:8000",
        )

        assertEquals(DEFAULT_SERVER_ADDRESS, store.load())
        assertCurrentDefaults(preferences)
    }

    @Test
    fun migratesSavedDefaultsFromSnapshots() = withPreferences { context, preferences ->
        preferences.edit()
            .putString("server_address", "wss://previous-default.example")
            .putString("default_server_address", "wss://previous-default.example")
            .putInt("power_save_timeout_minutes", 30)
            .putInt("default_power_save_timeout_minutes", 30)
            .putBoolean("bm008_enabled", true)
            .putBoolean("default_bm008_enabled", true)
            .commit()

        val store = SharedPreferencesConnectionPreferences(context)

        assertEquals(DEFAULT_SERVER_ADDRESS, store.load())
        assertEquals(DEFAULT_POWER_SAVE_TIMEOUT_MINUTES, store.loadPowerSaveTimeoutMinutes())
        assertEquals(true, store.loadHeadsetSettings().enabled)
        assertCurrentDefaults(preferences)
    }

    @Test
    fun preservesCustomValuesAndFourRecentFrequencies() = withPreferences { context, preferences ->
        preferences.edit()
            .putString("server_address", "wss://custom.example")
            .putString("default_server_address", "wss://previous-default.example")
            .putInt("power_save_timeout_minutes", 60)
            .putInt("default_power_save_timeout_minutes", 30)
            .putBoolean("bm008_enabled", false)
            .putBoolean("default_bm008_enabled", true)
            .putBoolean("service_sounds_enabled", false)
            .commit()

        val store = SharedPreferencesConnectionPreferences(context)
        listOf("ROOM1", "ROOM2", "ROOM3", "ROOM4", "ROOM5").forEach(store::saveLastChannel)
        store.saveLastChannel("ROOM3")
        store.saveLastChannel(ECHO_CHANNEL)
        val restored = SharedPreferencesConnectionPreferences(context)

        assertEquals("wss://custom.example", restored.load())
        assertEquals(60, restored.loadPowerSaveTimeoutMinutes())
        assertEquals(false, restored.loadHeadsetSettings().enabled)
        assertFalse(preferences.contains("service_sounds_enabled"))
        assertEquals(ECHO_CHANNEL, restored.loadLastChannel())
        assertEquals(
            listOf("ROOM3", "ROOM5", "ROOM4", "ROOM2", ECHO_CHANNEL),
            restored.loadFrequencyChoices(),
        )
        assertCurrentDefaults(preferences)
    }

    @Test
    fun migratesLegacyEchoSelectionWithoutKeepingItInHistory() =
        withPreferences { context, preferences ->
            preferences.edit()
                .putString("last_channel", "ECHO2")
                .putString("recent_frequencies", "ROOM2,ECHO2,ROOM1")
                .commit()

            val store = SharedPreferencesConnectionPreferences(context)

            assertEquals(ECHO_CHANNEL, store.loadLastChannel())
            assertEquals(listOf("ROOM2", "ROOM1", ECHO_CHANNEL), store.loadFrequencyChoices())
        }

    @Test
    fun preservesDottedFrequency() = withPreferences { context, _ ->
        val store = SharedPreferencesConnectionPreferences(context)

        store.saveLastChannel("room.1")

        val restored = SharedPreferencesConnectionPreferences(context)
        assertEquals("ROOM.1", restored.loadLastChannel())
        assertEquals(listOf("ROOM.1", ECHO_CHANNEL), restored.loadFrequencyChoices())
    }

    @Test
    fun replacesInvalidStoredTypesAndValues() = withPreferences { context, preferences ->
        preferences.edit()
            .putString("server_address", "not a server")
            .putString("power_save_timeout_minutes", "broken")
            .putString("bm008_enabled", "broken")
            .putString("last_channel", "invalid channel!")
            .putString("recent_frequencies", "ROOM1,invalid room,ROOM1,ECHO")
            .commit()

        val store = SharedPreferencesConnectionPreferences(context)

        assertEquals(DEFAULT_SERVER_ADDRESS, store.load())
        assertEquals(DEFAULT_POWER_SAVE_TIMEOUT_MINUTES, store.loadPowerSaveTimeoutMinutes())
        assertEquals(DEFAULT_BM008_ENABLED, store.loadHeadsetSettings().enabled)
        assertEquals(ECHO_CHANNEL, store.loadLastChannel())
        assertEquals(listOf("ROOM1", ECHO_CHANNEL), store.loadFrequencyChoices())
        assertCurrentDefaults(preferences)
    }

    private fun withPreferences(block: (Context, SharedPreferences) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("zenptt", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            block(context, preferences)
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private fun assertCurrentDefaults(preferences: SharedPreferences) {
        assertEquals(DEFAULT_SERVER_ADDRESS, preferences.getString("default_server_address", null))
        assertEquals(
            DEFAULT_POWER_SAVE_TIMEOUT_MINUTES,
            preferences.getInt("default_power_save_timeout_minutes", 0),
        )
        assertEquals(DEFAULT_BM008_ENABLED, preferences.getBoolean("default_bm008_enabled", true))
    }
}
