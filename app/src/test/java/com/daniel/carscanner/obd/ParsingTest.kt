package com.daniel.carscanner.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun vinValidation() {
        assertTrue(Vin.isValid("JTHHA5BC0H5000000"))
        assertFalse(Vin.isValid("JTHHA5BC0H500000"))    // 16 characters
        assertFalse(Vin.isValid("JTHHA5BC0H500000O"))   // O is never used
        assertFalse(Vin.isValid("jthha5bc0h5000000"))   // must be uppercase
        assertFalse(Vin.isValid("JTHHA5BC0H50000É0"))
    }

    @Test fun pidFormulas() {
        fun d(code: Int, vararg b: Int) = Pids.byCode.getValue(code).decode(b.toList())
        assertEquals(1726.0, d(0x0C, 0x1A, 0xF8), 0.001)          // rpm
        assertEquals(212.0, d(0x05, 140), 0.001)                    // 100 °C coolant
        assertEquals(0.0, d(0x06, 128), 0.001)                      // fuel trim 0%
        assertEquals(14.2, d(0x42, 0x37, 0x78), 0.001)             // 14.2 V
        assertEquals(1.0, d(0x24, 0x80, 0x00), 0.001)              // lambda 1.00
        assertEquals(10.0, d(0x0E, 148), 0.001)                    // 10° advance
    }
}
