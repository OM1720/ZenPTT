package app.zenptt.headset

import org.junit.Assert.*
import org.junit.Test

class ButtonLearningTest {
    private val rounds = buildList {
        var start = 3_000L
        ButtonLearner.HOLD_MS.forEach { hold ->
            add(TrainingRound(start, start + hold, start + hold + 3_000))
            start += hold + 3_000
        }
    }
    private val spp = HeadsetSetup(source = HeadsetSource.Spp, device = "00:11:22:33:44:55", service = SPP_SERVICE,
        framing = Framing.Delimited, rule = ButtonRule(RuleKind.Messages))
    private val ble = spp.copy(source = HeadsetSource.Ble, framing = Framing.Packet)
    private fun message(time: Long, value: String, endpoint: String = LINE_ENDPOINT) = Observation(time, endpoint, value.toByteArray().hex())
    private fun learner(template: HeadsetSetup = spp, samples: List<Observation>) = ButtonLearner(template).apply { samples.forEach(::add) }
    private fun samples(down: String = "BUTTON_DOWN", up: String = "BUTTON_UP") = rounds.flatMap {
        listOf(message(it.pressAt + 300, down), message(it.releaseAt + 400, up))
    }

    @Test fun learnsUnknownMessagesAndChecksTwoUnseenRounds() {
        val result = learner(samples = samples()).learn(rounds).single()
        assertTrue(result.hold)
        assertEquals(RuleKind.Messages, result.setup.rule.kind)
        assertEquals("BUTTON_DOWN".toByteArray().hex(), result.setup.rule.down)
        assertTrue(result.setup.isValid())
        val missingValidationPress = samples().filterNot { it.time == rounds.last().pressAt + 300 }
        assertTrue(learner(samples = missingValidationPress).learn(rounds).isEmpty())
    }

    @Test fun bm008IsLearnedWithoutAnyProtocolHintsAtEverySocketSplit() {
        val commands = "+PTT=P\r\n+PTT=R\r\n".toByteArray()
        for (split in 0..commands.size) {
            val decoded = mutableListOf<Pair<String, ByteArray>>()
            val framer = SppFramer(false) { endpoint, bytes -> decoded += endpoint to bytes }
            framer.feed(commands.copyOfRange(0, split))
            framer.feed(commands.copyOfRange(split, commands.size))
            assertEquals(listOf(LINE_ENDPOINT, LINE_ENDPOINT), decoded.map { it.first })
            val observations = rounds.flatMap { round -> listOf(
                Observation(round.pressAt + 400, decoded[0].first, decoded[0].second.hex()),
                Observation(round.releaseAt + 400, decoded[1].first, decoded[1].second.hex()),
            ) }
            val result = learner(samples = observations).learn(rounds).single()
            assertTrue(result.hold)
            assertEquals(Framing.Delimited, result.setup.framing)
        }
    }

    @Test fun knownTokensAreOnlyAnAdditionalFramingHypothesis() {
        val events = mutableListOf<Pair<String, String>>()
        val framer = SppFramer(true) { endpoint, bytes -> events += endpoint to String(bytes) }
        "+PTT=P+PTT=R".forEach { framer.feed(byteArrayOf(it.code.toByte())) }
        assertEquals(listOf(TOKEN_ENDPOINT to "+PTT=P", TOKEN_ENDPOINT to "+PTT=R"), events)
        val withoutHints = mutableListOf<ByteArray>()
        SppFramer(false) { _, bytes -> withoutHints += bytes }.feed("+PTT=P+PTT=R".toByteArray())
        assertTrue(withoutHints.isEmpty())
    }

    @Test fun oversizedFramesRecoverOnlyAtDelimiter() {
        val frames = mutableListOf<String>()
        val framer = SppFramer(false) { _, bytes -> frames += String(bytes) }
        framer.feed(ByteArray(600) { 65 })
        framer.feed("partial\nnext\u0000last\r\n".toByteArray())
        assertEquals(listOf("next", "last"), frames)
    }

    @Test fun learnsBleBitDespiteHeartbeatAndChangingCounters() {
        val observations = mutableListOf(Observation(0, "buttons", "8000"))
        rounds.forEachIndexed { index, round ->
            val heartbeat = if (index % 2 == 0) 128 else 0
            observations += Observation(round.pressAt + 300, "buttons", byteArrayOf((heartbeat or 1).toByte(), index.toByte()).hex())
            observations += Observation(round.pressAt + 2_500, "buttons", byteArrayOf((heartbeat xor 128 or 1).toByte(), (index + 1).toByte()).hex())
            observations += Observation(round.releaseAt + 300, "buttons", byteArrayOf(heartbeat.toByte(), (index + 2).toByte()).hex())
            observations += Observation(round.releaseAt + 2_500, "buttons", byteArrayOf((heartbeat xor 128).toByte(), (index + 3).toByte()).hex())
        }
        val result = learner(ble, observations).learn(rounds).single()
        assertTrue(result.hold)
        assertEquals(RuleKind.Bit, result.setup.rule.kind)
        assertEquals(0, result.setup.rule.offset)
        assertEquals(1, result.setup.rule.mask)
    }

    @Test fun backgroundCounterIsNotAPttButton() {
        val background = (0..(rounds.last().endAt / 250).toInt()).map {
            Observation(it * 250L, "counter", byteArrayOf(it.toByte()).hex())
        }
        val results = learner(ble, background).learn(rounds)
        assertTrue("Unexpected background hypothesis: $results", results.isEmpty())
    }

    @Test fun baselineAndExtraPressesRejectOtherwisePlausibleMessages() {
        val events = listOf(message(100, "BUTTON_DOWN"), message(500, "BUTTON_UP")) + samples()
        assertTrue(learner(samples = events).learn(rounds).isEmpty())
    }

    @Test fun isolatedPulsesOnlyOfferToggle() {
        val events = rounds.map { message(it.pressAt + 400, "CLICK") }
        val result = learner(samples = events).learn(rounds).single()
        assertFalse(result.hold)
        assertEquals(ButtonBehavior.Toggle, result.setup.behavior)
        assertEquals(RuleKind.Pulse, result.setup.rule.kind)
    }

    @Test fun pulsesDuringHoldingAreNotSingleClicks() {
        val events = rounds.flatMap { listOf(message(it.pressAt + 200, "CLICK"), message(it.pressAt + 2_500, "CLICK")) }
        assertTrue(learner(samples = events).learn(rounds).isEmpty())
    }

    @Test fun androidHoldRequiresMatchingIdentitiesAndOrder() {
        val events = rounds.flatMap { listOf(
            Observation(it.pressAt + 300, "key:85", keyCode = 85, pressed = true, pressId = it.pressAt),
            Observation(it.releaseAt + 300, "key:85", keyCode = 85, pressed = false, pressId = it.pressAt),
        ) }
        val template = HeadsetSetup.media(85, ButtonBehavior.Hold)
        assertTrue(learner(template, events).learn(rounds).single().hold)
        val reversed = events.map { it.copy(pressed = it.pressed != true) }
        assertTrue(learner(template, reversed).learn(rounds).isEmpty())
        val wrongIdentity = events.map { if (it.pressed == false) it.copy(pressId = 1) else it }
        assertTrue(learner(template, wrongIdentity).learn(rounds).isEmpty())
    }

    @Test fun duplicatesDoNotInvalidateTraining() {
        val events = samples().flatMap { listOf(it, it) }
        assertTrue(learner(samples = events).learn(rounds).single().hold)
    }

    @Test fun incompleteTrainingAndLimitsCannotProduceASetup() {
        val engine = learner(samples = samples())
        assertTrue(engine.learn(rounds.take(3)).isEmpty())
        repeat(ButtonLearner.MAX_EVENTS + 1) { engine.add(message(100_000L + it, "NOISE")) }
        assertTrue(engine.overflow)
        assertTrue(engine.learn(rounds).isEmpty())
        val bytes = learner(samples = emptyList())
        repeat(1_100) { bytes.add(Observation(it.toLong(), "data", "ff".repeat(256))) }
        assertTrue(bytes.overflow)
    }
}
