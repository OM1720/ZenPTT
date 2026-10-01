// Defines and validates persisted headset sources, button rules, and settings.
package app.zenptt.headset

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.UUID

@Serializable enum class HeadsetSource { Media, Hid, Spp, Ble }
@Serializable enum class ButtonBehavior { Hold, Toggle }
@Serializable enum class RuleKind { Key, Messages, Bit, ByteValue, Pulse }
@Serializable enum class Framing { Packet, Delimited, KnownTokens }

@Serializable
data class ButtonRule(
    val kind: RuleKind,
    val down: String = "",
    val up: String = "",
    val keyCode: Int = 0,
    val offset: Int = 0,
    val mask: Int = 255,
    val pressedValue: Int = 0,
    val releasedValue: Int = 0,
    val size: Int = 0,
)

@Serializable
data class HeadsetSetup(
    val version: Int = 1,
    val source: HeadsetSource,
    val device: String = "",
    val autoSelect: Boolean = false,
    val service: String = "",
    val characteristic: String = "",
    val framing: Framing = Framing.Packet,
    val rule: ButtonRule,
    val behavior: ButtonBehavior = ButtonBehavior.Hold,
) {
    fun isValid(): Boolean {
        if (version != 1 || device.length > 512 || service.length > 64 || characteristic.length > 128) return false
        if (source == HeadsetSource.Hid && device.isBlank()) return false
        if (source in setOf(HeadsetSource.Spp, HeadsetSource.Ble)) {
            if ((!autoSelect && !device.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) ||
                runCatching { UUID.fromString(service) }.isFailure) return false
            if (source == HeadsetSource.Ble && runCatching { UUID.fromString(characteristic) }.isFailure) return false
        }
        if (autoSelect && source != HeadsetSource.Spp) return false
        if (source == HeadsetSource.Spp && framing == Framing.Packet) return false
        if (source != HeadsetSource.Spp && framing != Framing.Packet) return false
        return when (rule.kind) {
            RuleKind.Key -> source in setOf(HeadsetSource.Media, HeadsetSource.Hid) && rule.keyCode > 0 &&
                (source != HeadsetSource.Media || rule.keyCode in MEDIA_KEYS)
            RuleKind.Messages -> validHex(rule.down) && validHex(rule.up) && rule.down != rule.up &&
                source in setOf(HeadsetSource.Spp, HeadsetSource.Ble)
            RuleKind.Pulse -> behavior == ButtonBehavior.Toggle && validHex(rule.down) &&
                source in setOf(HeadsetSource.Spp, HeadsetSource.Ble)
            RuleKind.Bit, RuleKind.ByteValue -> source in setOf(HeadsetSource.Spp, HeadsetSource.Ble) &&
                rule.size in 1..MAX_MESSAGE_BYTES && rule.offset in 0 until rule.size &&
                rule.mask in 1..255 && rule.pressedValue in 0..255 && rule.releasedValue in 0..255 &&
                rule.pressedValue != rule.releasedValue &&
                (rule.pressedValue and rule.mask) == rule.pressedValue &&
                (rule.releasedValue and rule.mask) == rule.releasedValue &&
                (if (rule.kind == RuleKind.Bit) Integer.bitCount(rule.mask) == 1 else rule.mask == 255)
        }
    }

    companion object {
        fun media(keyCode: Int, behavior: ButtonBehavior) = HeadsetSetup(
            source = HeadsetSource.Media, rule = ButtonRule(RuleKind.Key, keyCode = keyCode), behavior = behavior,
        )
        // The default is ordinary data consumed by the same controller as learned setups.
        fun factory() = HeadsetSetup(
            source = HeadsetSource.Spp, autoSelect = true, service = SPP_SERVICE,
            framing = Framing.KnownTokens,
            rule = ButtonRule(RuleKind.Messages, down = "+PTT=P".toByteArray().hex(), up = "+PTT=R".toByteArray().hex()),
        )
    }
}

@Serializable
data class HeadsetSettings(
    val enabled: Boolean = false,
    val setup: HeadsetSetup = HeadsetSetup.factory(),
    val valid: Boolean = true,
)

object HeadsetSettingsCodec {
    private val json = Json { encodeDefaults = true }
    fun encode(value: HeadsetSettings): String = json.encodeToString(value)
    fun decode(value: String): HeadsetSettings = runCatching {
        require(json.parseToJsonElement(value).jsonObject.keys.containsAll(listOf("enabled", "setup", "valid")))
        json.decodeFromString<HeadsetSettings>(value).also { require(it.valid && it.setup.isValid()) }
    }.getOrElse { HeadsetSettings(valid = false) }
}

const val SPP_SERVICE = "00001101-0000-1000-8000-00805f9b34fb"
val MEDIA_KEYS = setOf(79, 85, 126, 127)
internal const val MAX_MESSAGE_BYTES = 256
internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun String.bytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun validHex(value: String) = value.length in 2..MAX_MESSAGE_BYTES * 2 &&
    value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' }
