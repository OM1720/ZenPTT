package app.zenptt.headset

import org.junit.Assert.*
import org.junit.Test

class HeadsetSettingsTest {
    @Test fun factoryIsAnOrdinaryValidPersistedSetup() {
        val settings = HeadsetSettings()
        assertFalse(settings.enabled)
        assertTrue(settings.setup.isValid())
        assertEquals(HeadsetSource.Spp, settings.setup.source)
        assertEquals(settings, HeadsetSettingsCodec.decode(HeadsetSettingsCodec.encode(settings)))
        assertEquals(settings.setup, HeadsetSettingsCodec.decode(HeadsetSettingsCodec.encode(settings.copy(enabled = true))).setup)
    }

    @Test fun everySourceRoundTripsAndCorruptionDisablesWithoutFallbackActivation() {
        val examples = listOf(
            HeadsetSetup.factory(), HeadsetSetup.media(85, ButtonBehavior.Toggle),
            HeadsetSetup(source = HeadsetSource.Hid, device = "external-keyboard", rule = ButtonRule(RuleKind.Key, keyCode = 131)),
            HeadsetSetup(source = HeadsetSource.Ble, device = "AA:BB:CC:DD:EE:FF", service = SPP_SERVICE,
                characteristic = SPP_SERVICE, rule = ButtonRule(RuleKind.Bit, size = 1, mask = 1, pressedValue = 1)),
        )
        examples.forEach {
            assertTrue(it.isValid())
            val saved = HeadsetSettings(true, it)
            assertEquals(saved, HeadsetSettingsCodec.decode(HeadsetSettingsCodec.encode(saved)))
        }
        listOf("broken", "{}", HeadsetSettingsCodec.encode(HeadsetSettings(true, HeadsetSetup.factory().copy(version = 2)))).forEach {
            val invalid = HeadsetSettingsCodec.decode(it)
            assertFalse(invalid.enabled)
            assertFalse(invalid.valid)
        }
    }

    @Test fun rejectsUnsupportedMediaKeysAndMalformedRules() {
        assertFalse(HeadsetSetup.media(24, ButtonBehavior.Hold).isValid())
        assertFalse(HeadsetSetup.factory().copy(rule = ButtonRule(RuleKind.Messages, "gg", "01")).isValid())
        assertFalse(HeadsetSetup.factory().copy(device = "", autoSelect = false).isValid())
        assertFalse(HeadsetSetup.factory().copy(rule = ButtonRule(RuleKind.Pulse, down = "01")).isValid())
        assertFalse(HeadsetSetup.factory().copy(rule = ButtonRule(RuleKind.Bit, size = 1, mask = 1, pressedValue = 2)).isValid())
        assertFalse(HeadsetSetup.factory().copy(rule = ButtonRule(RuleKind.ByteValue, size = 1, mask = 3, pressedValue = 1)).isValid())
    }
}
