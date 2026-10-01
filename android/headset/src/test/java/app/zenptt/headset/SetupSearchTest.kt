package app.zenptt.headset

import org.junit.Assert.*
import org.junit.Test

class SetupSearchTest {
    private val bluetooth = HeadsetDevice("headset", "Headset", address = "00:11:22:33:44:55", spp = true, ble = true)
    private val hold = LearnedCandidate(HeadsetSetup.factory(), true)

    @Test fun unsuccessfulSourcesAdvanceFromSppToBleToMedia() {
        val search = SetupSearch(bluetooth)
        for (source in listOf(HeadsetSource.Spp, HeadsetSource.Ble, HeadsetSource.Media)) {
            assertEquals(source, search.nextSource)
            assertEquals(source, search.next()?.source)
            assertNull(search.accept(emptyList()))
        }
        assertNull(search.nextSource)
        assertNull(search.next())
    }

    @Test fun acceptedSppStopsBeforeOpeningBleOrMedia() {
        val search = SetupSearch(bluetooth)
        search.next()
        assertEquals(hold, search.accept(listOf(hold)))
        assertNull(search.nextSource)
        assertNull(search.next())
    }

    @Test fun firstValidToggleAlsoStopsButHoldWinsWithinTheSameSource() {
        val toggle = LearnedCandidate(hold.setup.copy(behavior = ButtonBehavior.Toggle), false)
        val toggleSearch = SetupSearch(bluetooth)
        toggleSearch.next()
        assertEquals(toggle, toggleSearch.accept(listOf(toggle)))
        assertNull(toggleSearch.next())
        val holdSearch = SetupSearch(bluetooth)
        holdSearch.next()
        assertEquals(hold, holdSearch.accept(listOf(toggle, hold)))
        assertNull(holdSearch.next())
    }

    @Test fun invalidCandidateDoesNotStopFallbackAndBleCanStopBeforeMedia() {
        val search = SetupSearch(bluetooth)
        search.next()
        assertNull(search.accept(listOf(hold.copy(setup = hold.setup.copy(service = "invalid")))))
        val ble = search.next()!!.copy(service = "0000fff0-0000-1000-8000-00805f9b34fb",
            characteristic = "0000fff1-0000-1000-8000-00805f9b34fb", rule = hold.setup.rule)
        val accepted = LearnedCandidate(ble, true)
        assertEquals(accepted, search.accept(listOf(accepted)))
        assertNull(search.next())
    }

    @Test fun skipsUnavailableTransportsAndKeepsHidBoundToSelectedInput() {
        val bleOnly = SetupSearch(bluetooth.copy(spp = false))
        assertEquals(HeadsetSource.Ble, bleOnly.next()?.source)
        assertEquals(HeadsetSource.Media, bleOnly.next()?.source)
        val mediaOnly = SetupSearch(bluetooth.copy(spp = false, ble = false))
        assertEquals(HeadsetSource.Media, mediaOnly.next()?.source)
        assertNull(mediaOnly.next())
        val hid = SetupSearch(HeadsetDevice("input", "Input", descriptor = "external-input"))
        val setup = hid.next()!!
        assertEquals(HeadsetSource.Hid, setup.source)
        assertEquals("external-input", setup.device)
        assertNull(hid.next())
    }
}
