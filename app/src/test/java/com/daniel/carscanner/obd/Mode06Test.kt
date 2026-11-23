package com.daniel.carscanner.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mode 06 self-test results: parsing, scaling, pass/fail. */
class Mode06Test {

    @Test fun readsSupportedMonitors() {
        // 46 00 then bitmap C0 00 00 01: monitors 01, 02 and 20 (more follow)
        assertEquals(setOf(0x01, 0x02, 0x20), Mode06.supportedMids(0x00, "4600C0000001"))
        assertEquals(setOf(0x21), Mode06.supportedMids(0x20, "462080000000"))
        assertTrue(Mode06.supportedMids(0x00, "7F0612").isEmpty())
    }

    @Test fun parsesOxygenSensorResult() {
        // MID 01, TID 01 (rich-to-lean voltage), UASID 0A (0.122 mV), value 0x0E5A, min 0x0D00, max 0x1000
        val t = Mode06.parse(listOf("4601010A0E5A0D001000")).single()
        assertEquals("Oxygen sensor, bank 1 sensor 1", t.monitor)
        assertEquals("Rich-to-lean switch voltage", t.test)
        assertEquals("mV", t.unit)
        assertEquals(0x0E5A * 0.122, t.value, 0.0001)
        assertTrue(t.passed)
    }

    @Test fun parsesSeveralTestsInOneReply() {
        val reply = "4621" + "8510" + "0040" + "0000" + "0080" + // catalyst bank 1, manufacturer test 85? (MID 21 TID 85)
            "21" + "86" + "10" + "0090" + "0000" + "0080"         // second test fails (0x90 > 0x80)
        val tests = Mode06.parse(listOf(reply))
        assertEquals(2, tests.size)
        assertEquals("Catalytic converter, bank 1", tests[0].monitor)
        assertTrue(tests[0].passed)
        assertFalse(tests[1].passed)
    }

    @Test fun signedUnitsCompareCorrectly() {
        // UASID 0x83 (signed 0.01): value -2.00 (FF38), min -5.00 (FE0C), max +5.00 (01F4)
        val t = MonitorTest(0x81, 0x80, 0x83, 0xFF38, 0xFE0C, 0x01F4)
        assertEquals(-2.0, t.value, 0.0001)
        assertTrue(t.passed)
    }

    @Test fun misfireZeroIsNotNearLimit() {
        // Misfire cylinder 1, count 0, allowed 0..50: perfect, not "near a limit"
        val t = MonitorTest(0xA2, 0x0B, 0x24, 0, 0, 50)
        assertTrue(t.passed)
        assertFalse(t.nearLimit)
        assertEquals("Misfire, cylinder 1", t.monitor)
        // 48 of 50 is near the limit
        assertTrue(MonitorTest(0xA2, 0x0B, 0x24, 48, 0, 50).nearLimit)
    }

    @Test fun scalesTemperatureWithOffset() {
        assertEquals(60.0, Mode06.scale(0x16, 1000), 0.0001)   // 1000 * 0.1 - 40
    }
}
