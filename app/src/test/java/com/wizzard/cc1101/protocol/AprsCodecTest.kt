/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APRS (Bell-202 AFSK 1200 → AX.25) decoder tests.
 *
 * [realCaptureDecodes] is the important one: `aprs_real_burst.csv` is a GDO0
 * half-period pulse-train derived from a real off-air SDR recording (gqrx, a
 * test beacon on 432.5 MHz), i.e. the kind of input the firmware's `direct`
 * capture produces — jittery, not synthetic. It guards the bit-clock recovery
 * in [AprsCodec.demodAfsk]: the earlier fixed-grid demod slipped bits over the
 * ~960-bit packet and failed the FCS; the transition-resynchronised PLL decodes
 * it. If that regresses, this test goes red.
 */
class AprsCodecTest {

    private val expected = "AAAA0T-9>APZK5,WIDE1-1:!4850.90N/00216.25ETUV K1 TEST"

    private fun fixture(name: String): IntArray =
        javaClass.getResourceAsStream("/$name")!!.bufferedReader().readText()
            .trim().split(",").map { it.trim().toInt() }.toIntArray()

    @Test
    fun realCaptureDecodes() {
        val us = fixture("aprs_real_burst.csv")
        val packets = AprsCodec.decodeFromPulses(us)
        assertTrue("expected at least one FCS-valid APRS frame", packets.isNotEmpty())
        assertEquals(expected, packets.first().tnc2())
    }

    @Test
    fun roundTripEncodeDecode() {
        val us = AprsCodec.encodeToPulses(
            "AAAA0T-9", "APZK5", listOf("WIDE1-1"), "!4850.90N/00216.25ETUV K1 TEST"
        )
        val packets = AprsCodec.decodeFromPulses(us)
        assertTrue("round-trip produced no frame", packets.isNotEmpty())
        assertEquals(expected, packets.first().tnc2())
    }
}
