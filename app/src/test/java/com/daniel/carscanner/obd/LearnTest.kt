package com.daniel.carscanner.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic setup: finding the bit that follows what you did. */
class LearnTest {

    @Test fun findsDoorBit() {
        // Byte 2 bit 5 goes 0 -> 1 when the door opens. Byte 3 is a counter that keeps changing.
        val off = listOf(listOf(0x61, 0x80, 0x00, 0x10), listOf(0x61, 0x80, 0x00, 0x11), listOf(0x61, 0x80, 0x00, 0x12))
        val on = listOf(listOf(0x61, 0x80, 0x20, 0x13), listOf(0x61, 0x80, 0x20, 0x14))
        val back = listOf(listOf(0x61, 0x80, 0x00, 0x15), listOf(0x61, 0x80, 0x00, 0x16))
        val found = Learn.candidates(off, on, back, blinks = false)
        assertEquals(listOf(FoundBit(2, 5, invert = false)), found)
    }

    @Test fun findsInvertedBit() {
        // Some cars report 1 = closed: the bit drops to 0 when the door opens.
        val off = listOf(listOf(0x61, 0x01), listOf(0x61, 0x01), listOf(0x61, 0x01))
        val on = listOf(listOf(0x61, 0x00), listOf(0x61, 0x00))
        val back = listOf(listOf(0x61, 0x01), listOf(0x61, 0x01))
        assertEquals(listOf(FoundBit(1, 0, invert = true)), Learn.candidates(off, on, back, blinks = false))
    }

    @Test fun ignoresBitThatDidNotComeBack() {
        val off = listOf(listOf(0x61, 0x00), listOf(0x61, 0x00), listOf(0x61, 0x00))
        val on = listOf(listOf(0x61, 0x04), listOf(0x61, 0x04))
        val back = listOf(listOf(0x61, 0x04), listOf(0x61, 0x04))   // stayed on: not this part
        assertTrue(Learn.candidates(off, on, back, blinks = false).isEmpty())
    }

    @Test fun blinkingSignalOnlyNeedsToFlashOnce() {
        val off = listOf(listOf(0x61, 0x00), listOf(0x61, 0x00), listOf(0x61, 0x00))
        val on = listOf(listOf(0x61, 0x08), listOf(0x61, 0x00), listOf(0x61, 0x08), listOf(0x61, 0x00), listOf(0x61, 0x00))
        val back = listOf(listOf(0x61, 0x00), listOf(0x61, 0x00))
        assertEquals(listOf(FoundBit(1, 3, invert = false)), Learn.candidates(off, on, back, blinks = true))
        assertTrue(Learn.candidates(off, on, back, blinks = false).isEmpty())
    }

    @Test fun payloadStripsSubAddressByte() {
        assertEquals(listOf(0x61, 0x80, 0x12), Learn.payload(listOf("618012"), "2180"))
        assertEquals(listOf(0x61, 0x80, 0x12), Learn.payload(listOf("40618012"), "2180"))   // Toyota body network
        assertEquals(listOf(0x62, 0x01, 0x23, 0xAA), Learn.payload(listOf("620123AA"), "220123"))
        assertNull(Learn.payload(listOf("7F2131"), "2180"))
        assertNull(Learn.payload(listOf("NO DATA"), "2180"))
    }

    @Test fun recognisesUnsupportedService() {
        assertTrue(Learn.serviceUnsupported(listOf("7F2111"), "2101"))
        assertTrue(!Learn.serviceUnsupported(listOf("7F2131"), "2101"))
    }

    @Test fun learnRequestsOnlyRead() {
        (Learn.kwpRequests + Learn.udsRequests).forEach { assertEquals(it, null, Safety.readRequestProblem("750", it)) }
    }

    @Test fun sourcesRoundTrip() {
        val list = listOf(DataSource(ModuleAddress(0x750, 0x758, 0x40), "2180"), DataSource(ModuleAddress(0x7C0, 0x7C8), "2101"))
        assertEquals(list, Learn.sourcesFromJson(Learn.sourcesToJson(list)))
    }
}
