package com.daniel.carscanner.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reply parsing: codes, multi-frame replies and VIN names. */
class ParsingTest {

    @Test fun decodesTroubleCodeLetters() {
        assertEquals("P0301", DtcDecoder.decode("0301"))
        assertEquals("C0035", DtcDecoder.decode("4035"))
        assertEquals("B1234", DtcDecoder.decode("9234"))
        assertEquals("U0100", DtcDecoder.decode("C100"))
        assertNull(DtcDecoder.decode("0000"))
        assertNull(DtcDecoder.decode("ZZZZ"))
    }

    @Test fun parsesCanCodeReply() {
        // 43, count 02, P0301, P0420
        assertEquals(listOf("P0301", "P0420"), DtcDecoder.parse("43 02 03 01 04 20", "43", isCan = true))
    }

    @Test fun parsesOlderProtocolCodeReply() {
        assertEquals(listOf("P0171", "P0174"), DtcDecoder.parse("43 01 71 01 74 00 00", "43", isCan = false))
    }

    @Test fun ignoresNoData() {
        assertEquals(emptyList<String>(), DtcDecoder.parse("NO DATA", "43", isCan = true))
    }

    @Test fun stitchesMultiFrameReplies() {
        val reply = "014\n0: 49 02 01 31 47 31\n1: 4A 43 35 34 34 34 52\n2: 37 32 35 32 33 36 37"
        assertEquals(listOf("490201314731" + "4A433534343452" + "37323532333637"), Hex.dataLines(reply))
    }

    @Test fun hexHelpers() {
        assertEquals(listOf(0x1A, 0xF8), Hex.bytes("1AF8"))
        assertEquals(listOf(0x1A), Hex.bytes("1AF"))
    }

    @Test fun vinNames() {
        assertEquals("2017 Lexus", Vin.describe("JTHHA5BC0H5000000"))
        assertNull(Vin.describe("TOO-SHORT"))
    }
}
