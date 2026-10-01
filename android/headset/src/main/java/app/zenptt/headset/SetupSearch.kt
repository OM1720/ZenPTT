// Orders headset input methods and selects the first verified button rule.
package app.zenptt.headset

// Prefer direct background connections over shared media keys and foreground-only HID.
internal class SetupSearch(device: HeadsetDevice) {
    private val remaining = ArrayDeque<HeadsetSetup>().apply {
        if (device.descriptor.isNotEmpty()) {
            add(HeadsetSetup(source = HeadsetSource.Hid, device = device.descriptor, rule = ButtonRule(RuleKind.Key)))
        } else {
            if (device.spp) add(HeadsetSetup(source = HeadsetSource.Spp, device = device.address,
                service = SPP_SERVICE, framing = Framing.Delimited, rule = ButtonRule(RuleKind.Messages)))
            if (device.ble) add(HeadsetSetup(source = HeadsetSource.Ble, device = device.address,
                rule = ButtonRule(RuleKind.Messages)))
            add(HeadsetSetup(source = HeadsetSource.Media, rule = ButtonRule(RuleKind.Key)))
        }
    }

    val nextSource: HeadsetSource? get() = remaining.firstOrNull()?.source
    fun next(): HeadsetSetup? = remaining.removeFirstOrNull()

    fun accept(candidates: List<LearnedCandidate>): LearnedCandidate? {
        val best = candidates.filter { it.setup.isValid() }.sortedByDescending { it.hold }.firstOrNull()
        if (best != null) remaining.clear()
        return best
    }
}
