/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.cli

/** Kind of a line shown in the main log, driving its colour. */
enum class LogKind { TX, RX, MONITOR, ERROR, ECHO, INFO }

/** Format a log line's epoch-millis timestamp as `HH:mm:ss.SSS` (local time). */
fun logTimestamp(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(ms))

/** One line in the main-tab log. */
data class LogLine(
    val id: Long,
    val kind: LogKind,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    /** Present only on MONITOR lines that carried a decodable capture. */
    val packet: CapturedPacket? = null,
)

/**
 * A capture emitted by the firmware `monitor`. Two shapes, matching the two
 * capture methods of the firmware:
 *  - [Ook]  : raw pulse-train (microsecond durations) for OOK/ASK
 *  - [Fsk]  : raw RX-FIFO bytes for 2-FSK / GFSK / 4-FSK / MSK
 */
sealed interface CapturedPacket {
    val iface: String
    val ts: Long
    val rssi: Int
    val lqi: Int
    val raw: String

    data class Ook(
        override val iface: String,
        override val ts: Long,
        override val rssi: Int,
        override val lqi: Int,
        val us: IntArray,
        override val raw: String,
    ) : CapturedPacket {
        override fun equals(other: Any?): Boolean =
            other is Ook && raw == other.raw && us.contentEquals(other.us)
        override fun hashCode(): Int = raw.hashCode() * 31 + us.contentHashCode()
    }

    data class Fsk(
        override val iface: String,
        override val ts: Long,
        override val rssi: Int,
        override val lqi: Int,
        val mod: String,       // "2fsk" | "gfsk" | "4fsk" | "msk"
        val crc: Boolean,
        val bytes: ByteArray,
        override val raw: String,
        /** Address byte the packet engine reported (addrchk > 0); null when off. */
        val addr: Int? = null,
        /** Whether CRC checking was enabled on the chip (crcen); null if unknown. */
        val crcEn: Boolean? = null,
    ) : CapturedPacket {
        override fun equals(other: Any?): Boolean =
            other is Fsk && raw == other.raw && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = raw.hashCode() * 31 + bytes.contentHashCode()
    }
}

/** A frame to transmit, produced by a protocol encoder after the user edits fields. */
sealed interface TxFrame {
    /** OOK pulse-train; sent via `radio <iface> pulses flush/add.../play`. */
    data class OokPulses(val us: IntArray) : TxFrame {
        override fun equals(other: Any?) = other is OokPulses && us.contentEquals(other.us)
        override fun hashCode() = us.contentHashCode()
    }
    /** Packet-mode frame; sent via `radio <iface> send <hex>` in the current modulation. */
    data class FskHex(val hex: String) : TxFrame
}

/**
 * One pass of the firmware swept-spectrum analyzer (`radio <if> sweep`) — a
 * single waterfall row. `rssiRaw[i]` is the raw byte for the point at
 * `f0Hz + i*dfHz`; convert to dBm with [dbmAt] (dBm = raw/2 − 138, 0.5 dB steps).
 */
data class SweepLine(
    val iface: String,
    val ts: Long,
    val f0Hz: Long,
    val dfHz: Long,
    val n: Int,
    val rbwHz: Long,
    val rssiRaw: IntArray,
) {
    fun dbmAt(i: Int): Float = rssiRaw[i] / 2f - 138f
    val stopHz: Long get() = f0Hz + (n - 1).toLong() * dfHz
    override fun equals(other: Any?) = other is SweepLine &&
        ts == other.ts && f0Hz == other.f0Hz && rssiRaw.contentEquals(other.rssiRaw)
    override fun hashCode() = (ts.hashCode() * 31 + f0Hz.hashCode()) * 31 + rssiRaw.contentHashCode()

    companion object {
        /** Raw byte → dBm. Range −138 .. −10.5 dBm in 0.5 dB steps. */
        fun rawToDbm(raw: Int): Float = raw / 2f - 138f
        const val DBM_MIN = -138f
        const val DBM_MAX = -10.5f
    }
}

/** Connection state for the main-tab indicator. */
enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, VERIFIED, WRONG_DEVICE, ERROR }
