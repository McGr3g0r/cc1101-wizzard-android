package com.wizzard.cc1101.cli

/** Kind of a line shown in the main log, driving its colour. */
enum class LogKind { TX, RX, MONITOR, ERROR, ECHO, INFO }

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

/** Connection state for the main-tab indicator. */
enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, VERIFIED, WRONG_DEVICE, ERROR }
