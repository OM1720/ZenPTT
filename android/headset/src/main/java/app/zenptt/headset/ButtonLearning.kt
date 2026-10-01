// Learns button press and release rules from observed key or transport events.
package app.zenptt.headset

internal data class Observation(
    val time: Long,
    val endpoint: String,
    val value: String = "",
    val keyCode: Int = 0,
    val pressed: Boolean? = null,
    val pressId: Long = 0,
)

internal enum class Edge { Down, Up, Pulse }

internal fun ButtonRule.match(event: Observation): Edge? = when (kind) {
    RuleKind.Key -> if (event.keyCode != keyCode) null else when (event.pressed) {
        true -> Edge.Down
        false -> Edge.Up
        null -> null
    }
    RuleKind.Messages -> when (event.value) { down -> Edge.Down; up -> Edge.Up; else -> null }
    RuleKind.Pulse -> if (event.value == down) Edge.Pulse else null
    RuleKind.Bit, RuleKind.ByteValue -> {
        if (event.value.length != size * 2) null else {
            val value = event.value.substring(offset * 2, offset * 2 + 2).toInt(16) and mask
            when (value) { pressedValue -> Edge.Down; releasedValue -> Edge.Up; else -> null }
        }
    }
}

internal class ButtonInput {
    var engaged = false
        private set
    private var held = false
    private var pressId: Long? = null
    private var releasedPressId = Long.MIN_VALUE
    private var lastPulse = Long.MIN_VALUE
    private val recent = ArrayDeque<Observation>()

    fun event(event: Observation, rule: ButtonRule, behavior: ButtonBehavior): Boolean? {
        if (event in recent) return null
        recent.addLast(event)
        if (recent.size > 32) recent.removeFirst()
        val edge = rule.match(event) ?: return null
        if (edge == Edge.Up) {
            if (rule.kind == RuleKind.Key) {
                // UP may arrive first, or an old callback may follow a newer press.
                releasedPressId = maxOf(releasedPressId, event.pressId)
                if (pressId != event.pressId) return null
            }
            held = false
            if (behavior == ButtonBehavior.Hold && engaged) { engaged = false; return false }
            return null
        }
        if (edge == Edge.Pulse) {
            if (lastPulse != Long.MIN_VALUE && event.time - lastPulse < PULSE_DEBOUNCE_MS) return null
            lastPulse = event.time
        } else {
            if (held && (rule.kind != RuleKind.Key || behavior == ButtonBehavior.Hold || pressId == event.pressId)) return null
            if (rule.kind == RuleKind.Key && (event.pressId <= releasedPressId ||
                    (pressId != null && event.pressId <= pressId!!))) return null
            held = true
            pressId = event.pressId
        }
        engaged = if (behavior == ButtonBehavior.Toggle) !engaged else true
        return engaged
    }

    // Keep the physical state until release so repeats cannot undo a terminal reset.
    fun reset(): Boolean {
        val previous = engaged
        engaged = false
        return previous
    }

    companion object { const val PULSE_DEBOUNCE_MS = 150L }
}

internal data class TrainingRound(val pressAt: Long, val releaseAt: Long, val endAt: Long)
internal data class LearnedCandidate(val setup: HeadsetSetup, val hold: Boolean)

internal class ButtonLearner(private val template: HeadsetSetup) {
    private val observations = mutableListOf<Observation>()
    private var bytes = 0
    var overflow = false
        private set

    fun add(event: Observation) {
        if (overflow) return
        if (event in observations.takeLast(32)) return
        bytes += event.value.length / 2 + 32
        if (observations.size >= MAX_EVENTS || bytes > MAX_BYTES) { overflow = true; return }
        observations += event
    }

    fun learn(rounds: List<TrainingRound>): List<LearnedCandidate> {
        if (overflow || rounds.size != 5) return emptyList()
        val training = rounds.take(3)
        val results = mutableListOf<LearnedCandidate>()
        observations.groupBy { it.endpoint }.forEach { (endpoint, all) ->
            val samples = all.filter { it.time < training.last().endAt }
            val rules = linkedSetOf<ButtonRule>()
            samples.filter { it.keyCode > 0 }.map { it.keyCode }.distinct().forEach {
                rules += ButtonRule(RuleKind.Key, keyCode = it)
            }
            val pressValues = samples.filter { event -> training.any { event.time in it.pressWindow() } }
                .map { it.value }.filter { it.isNotEmpty() }.distinct()
            val releaseValues = samples.filter { event -> training.any { event.time in it.releaseWindow() } }
                .map { it.value }.filter { it.isNotEmpty() }.distinct()
            if (pressValues.size > 64 || releaseValues.size > 64) { overflow = true; return emptyList() }
            pressValues.forEach { down ->
                rules += ButtonRule(RuleKind.Pulse, down = down)
                releaseValues.filter { it != down }.forEach { up -> rules += ButtonRule(RuleKind.Messages, down, up) }
            }
            // Learn state bits without interpreting unrelated counters or heartbeat fields.
            pressValues.forEach { down ->
                releaseValues.filter { it.length == down.length }.forEach { up ->
                    val pressed = down.bytes()
                    val released = up.bytes()
                    pressed.indices.forEach { offset ->
                        if (rules.size > MAX_HYPOTHESES) { overflow = true; return emptyList() }
                        val p = pressed[offset].toInt() and 255
                        val r = released[offset].toInt() and 255
                        if (p != r) {
                            rules += ButtonRule(RuleKind.ByteValue, offset = offset, pressedValue = p, releasedValue = r, size = pressed.size)
                            (0..7).forEach { bit ->
                                val mask = 1 shl bit
                                if ((p and mask) != (r and mask)) rules += ButtonRule(
                                    RuleKind.Bit, offset = offset, mask = mask, pressedValue = p and mask,
                                    releasedValue = r and mask, size = pressed.size,
                                )
                            }
                        }
                    }
                }
            }
            if (rules.size > MAX_HYPOTHESES) { overflow = true; return emptyList() }
            val passing = rules.mapNotNull { rule ->
                // The final two rounds are never used to construct a hypothesis.
                val hold = rule.kind != RuleKind.Pulse && matches(rule, all, rounds, hold = true)
                val toggle = !hold && matches(rule, all, rounds, hold = false)
                if (!hold && !toggle) null else LearnedCandidate(
                    template.copy(
                        characteristic = if (template.source == HeadsetSource.Ble) endpoint else "",
                        framing = if (template.source == HeadsetSource.Spp) {
                            if (endpoint == TOKEN_ENDPOINT) Framing.KnownTokens else Framing.Delimited
                        } else Framing.Packet,
                        rule = rule, behavior = if (hold) ButtonBehavior.Hold else ButtonBehavior.Toggle,
                    ), hold,
                )
            }
            // Equivalent encodings within one endpoint use the smallest sufficient rule.
            passing.sortedWith(compareByDescending<LearnedCandidate> { it.hold }.thenBy {
                when (it.setup.rule.kind) { RuleKind.Key -> 0; RuleKind.Messages -> 1; RuleKind.ByteValue -> 2; RuleKind.Bit -> 3; RuleKind.Pulse -> 4 }
            }).firstOrNull()?.let(results::add)
        }
        return results
    }

    private fun matches(rule: ButtonRule, events: List<Observation>, rounds: List<TrainingRound>, hold: Boolean): Boolean {
        var down: Observation? = null
        val presses = mutableListOf<Long>()
        val releases = mutableListOf<Long>()
        events.forEach { event ->
            when (rule.match(event)) {
                Edge.Down -> {
                    if (down == null || (!hold && rule.kind == RuleKind.Key && down!!.pressId != event.pressId)) {
                        down = event
                        presses += event.time
                    }
                }
                Edge.Up -> {
                    if (down != null) {
                        if (rule.kind == RuleKind.Key && down!!.pressId != event.pressId) return false
                        if (hold && event.time - down!!.time < MIN_HOLD_MS) return false
                        releases += event.time
                        down = null
                    } else if (rule.kind == RuleKind.Key) return false
                }
                Edge.Pulse -> presses.add(event.time)
                null -> Unit
            }
        }
        if (presses.size != rounds.size || (hold && (releases.size != rounds.size || down != null))) return false
        if (hold) {
            val durations = presses.indices.map { releases[it] - presses[it] }
            // A periodic telemetry bit can accidentally fall inside every reaction window.
            // It must also follow the deliberately different requested hold durations.
            if (durations.max() - durations.min() < MIN_DURATION_VARIATION_MS) return false
        } else if (rule.kind != RuleKind.Pulse) {
            if (releases.isEmpty()) {
                if (rule.kind != RuleKind.Key) return false
            } else if (releases.size != rounds.size || presses.indices.any {
                    releases[it] - presses[it] !in 0 until MIN_HOLD_MS
                }) return false
        }
        return rounds.indices.all { index ->
            presses[index] in rounds[index].pressWindow() && (!hold || releases[index] in rounds[index].releaseWindow())
        }
    }

    companion object {
        const val MAX_SOURCE_MS = 120_000L
        const val MAX_EVENTS = 2_048
        const val MAX_BYTES = 256 * 1_024
        const val MAX_HYPOTHESES = 4_096
        const val REACTION_MS = 2_000L
        const val MIN_HOLD_MS = 1_500L
        const val MIN_DURATION_VARIATION_MS = 1_000L
        const val QUIET_MS = 3_000L
        val HOLD_MS = listOf(4_000L, 6_000L, 4_000L, 5_000L, 4_000L)
    }
}

internal fun TrainingRound.pressWindow() = pressAt..pressAt + ButtonLearner.REACTION_MS
internal fun TrainingRound.releaseWindow() = releaseAt..releaseAt + ButtonLearner.REACTION_MS
internal const val LINE_ENDPOINT = "delimited"
internal const val TOKEN_ENDPOINT = "known_tokens"

// Socket read boundaries are deliberately ignored. Oversized frames are discarded until a delimiter.
internal class SppFramer(private val hints: Boolean, private val emit: (String, ByteArray) -> Unit) {
    private val line = ArrayList<Byte>()
    private var discard = false
    private var tokenBuffer = ""
    fun feed(bytes: ByteArray) {
        bytes.forEach { byte ->
            if (byte.toInt() in listOf(0, 10, 13)) {
                if (!discard && line.isNotEmpty()) emit(LINE_ENDPOINT, line.toByteArray())
                line.clear()
                discard = false
            } else if (!discard) {
                line += byte
                if (line.size > MAX_MESSAGE_BYTES) { discard = true; line.clear() }
            }
        }
        if (hints) {
            tokenBuffer += String(bytes, Charsets.ISO_8859_1)
            while (true) {
                val found = TOKENS.map { it to tokenBuffer.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second } ?: break
                emit(TOKEN_ENDPOINT, found.first.toByteArray())
                tokenBuffer = tokenBuffer.substring(found.second + found.first.length)
            }
            tokenBuffer = tokenBuffer.takeLast(16)
        }
    }
    private companion object { val TOKENS = listOf("+PTT=P", "+PTT=R", "+PTTS=P", "+PTTS=R") }
}
