package com.daniel.carscanner.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Console / Tests / Live view guard. If one of these fails, a risky command could slip through. */
class SafetyTest {

    @Test fun readsAreNormal() {
        listOf("0100", "010C", "03", "07", "0A", "0902", "22F190", "2101", "1902FF", "3E00", "1003", "ATRV", "ATSH7E0", "ATDP")
            .forEach { assertEquals(it, Risk.Normal, Safety.classify(it)) }
    }

    @Test fun actuatorsAndCodeClearsNeedConfirmation() {
        listOf("2F 01 23 03 FF", "3001", "04", "14FFFFFF", "14 FF FF FF")
            .forEach { assertEquals(it, Risk.Actuator, Safety.classify(it)) }
    }

    @Test fun moduleChangingServicesAreDangerous() {
        listOf("1101", "11 01", "2701", "2E F1 90 00", "3101FF00", "3B01", "3D", "3400", "8502", "8701", "2803")
            .forEach { assertEquals(it, Risk.Dangerous, Safety.classify(it)) }
    }

    @Test fun programmingSessionsAreDangerous() {
        assertEquals(Risk.Dangerous, Safety.classify("1002"))
        assertEquals(Risk.Dangerous, Safety.classify("10 85"))   // KWP2000 programming mode
        assertEquals(Risk.Normal, Safety.classify("1003"))      // extended session, used by tests
    }

    @Test fun adapterRewritesAreDangerous() {
        listOf("ATPP 0C SV 23", "AT PP 0C ON", "STPX H:7E0, D:1101", "stsbr 115200")
            .forEach { assertEquals(it, Risk.Dangerous, Safety.classify(it)) }
    }

    @Test fun hiddenLineBreaksCannotSmuggleCommands() {
        assertEquals(Risk.Dangerous, Safety.classify("0100\r1101"))
        assertEquals(Risk.Dangerous, Safety.classify("0100\n1101"))
        assertFalse(Safety.isSendable("0100\r1101"))
        assertFalse(Safety.isSendable("ATSH7E0\rATPP0CSV23"))
        assertTrue(Safety.isSendable("ATSH 7E0"))
        assertFalse(Safety.isSendable(""))
    }

    @Test fun tabsAndOddSpacingDontHideTheServiceId() {
        assertEquals(Risk.Dangerous, Safety.classify("11\t01"))
        assertEquals(Risk.Dangerous, Safety.classify(" 1 1 0 1 "))
    }

    @Test fun headers() {
        listOf("7E0", "7e0", "750", "DA10F1", "18DA10F1", "18 DA 10 F1").forEach { assertTrue(it, Safety.isValidHeader(it)) }
        listOf("", "7E", "7E0\rATPP", "ZZZ", "7E00").forEach { assertFalse(it, Safety.isValidHeader(it)) }
    }

    @Test fun repeatingRequestsMustOnlyRead() {
        assertEquals(null, Safety.readRequestProblem("750", "2101"))
        assertEquals(null, Safety.readRequestProblem("", "22 F1 90"))
        listOf("04", "1101", "2F010303", "ATZ", "STPX", "0100\r1101").forEach {
            assertTrue(it, Safety.readRequestProblem("750", it) != null)
        }
        assertTrue(Safety.readRequestProblem("75", "2101") != null)
    }

    @Test fun testEditorChecks() {
        assertEquals(null, Safety.testProblem("7E0", listOf("3003FF", "3000"), 10))
        assertTrue(Safety.testProblem("7E", listOf("3003FF"), 10) != null)
        assertTrue(Safety.testProblem("7E0", listOf("3003FF\r1101"), 10) != null)
        assertTrue(Safety.testProblem("7E0", listOf("3003FF"), 0) != null)
        assertTrue(Safety.testProblem("7E0", listOf("3003FF"), 121) != null)
    }
}
