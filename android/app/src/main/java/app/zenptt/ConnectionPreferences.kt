// Persists server/channel choices, history, audio policy, and hardware PTT settings.
package app.zenptt

import app.zenptt.headset.*

import android.content.Context

val DEFAULT_SERVER_ADDRESS: String = BuildConfig.DEFAULT_SERVER_ADDRESS
private const val V070_DEFAULT_POWER_SAVE_TIMEOUT_MINUTES = 10
private const val V070_DEFAULT_BM008_ENABLED = false
const val DEFAULT_POWER_SAVE_TIMEOUT_MINUTES = 10
const val DEFAULT_BM008_ENABLED = false
const val MIN_POWER_SAVE_TIMEOUT_MINUTES = 1
const val MAX_POWER_SAVE_TIMEOUT_MINUTES = 1_440
const val MAX_FREQUENCY_CHOICES = 5
private const val MAX_RECENT_FREQUENCIES = 4
private const val LEGACY_ECHO_CHANNEL = "ECHO2"

interface ConnectionPreferences {
    fun load(): String
    fun save(value: String)
    fun loadLastChannel(): String? = ECHO_CHANNEL
    fun saveLastChannel(value: String) = Unit
    fun loadFrequencyChoices(): List<String> =
        frequencyChoices(loadLastChannel()?.let(::listOf).orEmpty())
    fun loadPowerSaveTimeoutMinutes(): Int = DEFAULT_POWER_SAVE_TIMEOUT_MINUTES
    fun savePowerSaveTimeoutMinutes(value: Int) = Unit
    fun loadHeadsetSettings(): HeadsetSettings = HeadsetSettings()
    fun saveHeadsetSettings(value: HeadsetSettings): Boolean = true
}

class SharedPreferencesConnectionPreferences(
    context: Context,
    private val legacyServerAddress: String = BuildConfig.LEGACY_SERVER_ADDRESS,
    private val preSnapshotServerAddress: String = BuildConfig.PRE_SNAPSHOT_SERVER_ADDRESS,
) : ConnectionPreferences {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    init {
        migrate()
    }

    override fun load(): String = preferences.getString(SERVER_ADDRESS_KEY, null)
        ?.takeIf { it.isNotBlank() && it != legacyServerAddress }
        ?: DEFAULT_SERVER_ADDRESS

    override fun save(value: String) {
        preferences.edit().putString(SERVER_ADDRESS_KEY, value).apply()
    }

    override fun loadLastChannel(): String? =
        preferences.getString(LAST_CHANNEL_KEY, ECHO_CHANNEL)?.takeIf(String::isNotBlank)

    override fun saveLastChannel(value: String) {
        val channel = InputValidator.channelCode(value) ?: return
        val ordinary = loadFrequencyChoices().filterNot(::isEchoChannel)
        val updated = if (isEchoChannel(channel)) {
            ordinary
        } else {
            listOf(channel) + ordinary.filterNot { it == channel }
        }.take(MAX_RECENT_FREQUENCIES)
        preferences.edit()
            .putString(LAST_CHANNEL_KEY, channel)
            .putString(RECENT_FREQUENCIES_KEY, updated.joinToString(","))
            .apply()
    }

    override fun loadFrequencyChoices(): List<String> =
        frequencyChoices(
            preferences.getString(RECENT_FREQUENCIES_KEY, null)
                ?.split(',')
                .orEmpty(),
        )

    override fun loadPowerSaveTimeoutMinutes(): Int =
        preferences.getInt(POWER_SAVE_TIMEOUT_KEY, DEFAULT_POWER_SAVE_TIMEOUT_MINUTES)
            .takeIf { it in MIN_POWER_SAVE_TIMEOUT_MINUTES..MAX_POWER_SAVE_TIMEOUT_MINUTES }
            ?: DEFAULT_POWER_SAVE_TIMEOUT_MINUTES

    override fun savePowerSaveTimeoutMinutes(value: Int) {
        preferences.edit().putInt(POWER_SAVE_TIMEOUT_KEY, value).apply()
    }

    override fun loadHeadsetSettings(): HeadsetSettings {
        if (preferences.contains(HEADSET_SETTINGS_KEY)) {
            return HeadsetSettingsCodec.decode(stringOrNull(HEADSET_SETTINGS_KEY).orEmpty())
        }
        return loadLegacyHeadsetSettings()
    }

    private fun loadLegacyHeadsetSettings(): HeadsetSettings {
        val enabled = booleanOrNull(PTT_ENABLED_KEY) ?: false
        if (!preferences.contains(PTT_KEY_CODE_KEY) && !preferences.contains(PTT_BEHAVIOR_KEY)) {
            return HeadsetSettings(enabled = enabled)
        }
        val code = intOrNull(PTT_KEY_CODE_KEY)
        val behavior = ButtonBehavior.entries.firstOrNull { it.name == stringOrNull(PTT_BEHAVIOR_KEY) }
        return if (code in MEDIA_KEYS && behavior != null) {
            HeadsetSettings(enabled, HeadsetSetup.media(requireNotNull(code), behavior))
        } else {
            HeadsetSettings(enabled = false, valid = false)
        }
    }

    override fun saveHeadsetSettings(value: HeadsetSettings): Boolean {
        require(!value.valid || value.setup.isValid())
        val previous = stringOrNull(HEADSET_SETTINGS_KEY)
        val safe = if (value.valid) value else value.copy(enabled = false)
        if (preferences.edit().putString(HEADSET_SETTINGS_KEY, HeadsetSettingsCodec.encode(safe)).commit()) return true
        // SharedPreferences updates memory even when its disk commit fails.
        val restore = preferences.edit()
        if (previous == null) restore.remove(HEADSET_SETTINGS_KEY) else restore.putString(HEADSET_SETTINGS_KEY, previous)
        restore.commit()
        return false
    }

    private fun migrate() {
        val storedServer = stringOrNull(SERVER_ADDRESS_KEY)
        val previousServerDefault = if (preferences.contains(DEFAULT_SERVER_SNAPSHOT_KEY)) {
            stringOrNull(DEFAULT_SERVER_SNAPSHOT_KEY) ?: preSnapshotServerAddress
        } else {
            preSnapshotServerAddress
        }
        val storedPower = intOrNull(POWER_SAVE_TIMEOUT_KEY)
        val previousPowerDefault = if (preferences.contains(DEFAULT_POWER_SNAPSHOT_KEY)) {
            intOrNull(DEFAULT_POWER_SNAPSHOT_KEY) ?: V070_DEFAULT_POWER_SAVE_TIMEOUT_MINUTES
        } else {
            V070_DEFAULT_POWER_SAVE_TIMEOUT_MINUTES
        }
        val storedBm008 = booleanOrNull(BM008_ENABLED_KEY)
        val previousBm008Default = if (preferences.contains(DEFAULT_BM008_SNAPSHOT_KEY)) {
            booleanOrNull(DEFAULT_BM008_SNAPSHOT_KEY) ?: V070_DEFAULT_BM008_ENABLED
        } else {
            V070_DEFAULT_BM008_ENABLED
        }
        val storedSelection = InputValidator.channelCode(
            stringOrNull(LAST_CHANNEL_KEY).orEmpty(),
        )
        val selected = if (storedSelection == LEGACY_ECHO_CHANNEL) {
            ECHO_CHANNEL
        } else {
            storedSelection ?: ECHO_CHANNEL
        }
        val recent = if (preferences.contains(RECENT_FREQUENCIES_KEY)) {
            stringOrNull(RECENT_FREQUENCIES_KEY)?.split(',').orEmpty()
        } else {
            listOf(selected)
        }

        val editor = preferences.edit()
            .remove(LEGACY_SERVICE_SOUNDS_ENABLED_KEY)
            .putString(DEFAULT_SERVER_SNAPSHOT_KEY, DEFAULT_SERVER_ADDRESS)
            .putInt(DEFAULT_POWER_SNAPSHOT_KEY, DEFAULT_POWER_SAVE_TIMEOUT_MINUTES)
            .putBoolean(DEFAULT_BM008_SNAPSHOT_KEY, DEFAULT_BM008_ENABLED)
            .putString(LAST_CHANNEL_KEY, selected)
            .putString(
                RECENT_FREQUENCIES_KEY,
                frequencyChoices(
                    recent.map { if (it == LEGACY_ECHO_CHANNEL) ECHO_CHANNEL else it },
                ).filterNot(::isEchoChannel).joinToString(","),
            )

        if (!preferences.contains(PTT_ENABLED_KEY)) {
            editor.putBoolean(PTT_ENABLED_KEY, storedBm008 == true)
        }

        if (
            storedServer.isNullOrBlank() ||
            InputValidator.serverAddress(storedServer) == null ||
            storedServer == legacyServerAddress ||
            storedServer == previousServerDefault
        ) {
            editor.putString(SERVER_ADDRESS_KEY, DEFAULT_SERVER_ADDRESS)
        }
        if (
            storedPower == null ||
            storedPower !in MIN_POWER_SAVE_TIMEOUT_MINUTES..MAX_POWER_SAVE_TIMEOUT_MINUTES ||
            storedPower == previousPowerDefault
        ) {
            editor.putInt(POWER_SAVE_TIMEOUT_KEY, DEFAULT_POWER_SAVE_TIMEOUT_MINUTES)
        }
        if (storedBm008 == null || storedBm008 == previousBm008Default) {
            editor.putBoolean(BM008_ENABLED_KEY, DEFAULT_BM008_ENABLED)
        }
        editor.commit()
        if (!preferences.contains(HEADSET_SETTINGS_KEY)) {
            saveHeadsetSettings(loadLegacyHeadsetSettings())
        }
    }

    private fun stringOrNull(key: String): String? =
        runCatching { preferences.getString(key, null) }.getOrNull()

    private fun intOrNull(key: String): Int? =
        runCatching { preferences.getInt(key, 0) }.getOrNull()
            ?.takeIf { preferences.contains(key) }

    private fun booleanOrNull(key: String): Boolean? =
        runCatching { preferences.getBoolean(key, false) }.getOrNull()
            ?.takeIf { preferences.contains(key) }

    private companion object {
        const val PREFERENCES_NAME = "zenptt"
        const val HEADSET_SETTINGS_KEY = "headset_settings_v1"
        const val SERVER_ADDRESS_KEY = "server_address"
        const val POWER_SAVE_TIMEOUT_KEY = "power_save_timeout_minutes"
        const val BM008_ENABLED_KEY = "bm008_enabled"
        const val PTT_ENABLED_KEY = "ptt_button_enabled"
        const val PTT_KEY_CODE_KEY = "ptt_button_key_code"
        const val PTT_BEHAVIOR_KEY = "ptt_button_behavior"
        const val LEGACY_SERVICE_SOUNDS_ENABLED_KEY = "service_sounds_enabled"
        const val LAST_CHANNEL_KEY = "last_channel"
        const val RECENT_FREQUENCIES_KEY = "recent_frequencies"
        const val DEFAULT_SERVER_SNAPSHOT_KEY = "default_server_address"
        const val DEFAULT_POWER_SNAPSHOT_KEY = "default_power_save_timeout_minutes"
        const val DEFAULT_BM008_SNAPSHOT_KEY = "default_bm008_enabled"
    }
}

internal fun frequencyChoices(values: List<String>): List<String> {
    val ordinary = values.asSequence()
        .mapNotNull(InputValidator::channelCode)
        .filterNot(::isEchoChannel)
        .distinct()
        .take(MAX_RECENT_FREQUENCIES)
        .toList()
    return ordinary + ECHO_CHANNEL
}
