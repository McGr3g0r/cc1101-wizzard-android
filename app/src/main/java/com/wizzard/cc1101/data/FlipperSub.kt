/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.data

import com.wizzard.cc1101.protocol.OokDecoding

/**
 * Parser for Flipper Zero SubGHz `.sub` files.
 *
 * Supported:
 *  - RAW files  (`Protocol: RAW`): one or more `RAW_Data:` lines of signed
 *    microsecond durations (positive = carrier on, negative = off). Mapped to a
 *    pulse-train for replay via the firmware's `pulses` command.
 *  - Keyed files (e.g. `Protocol: Princeton`): `Bit`, `Key`, `TE` fields; a
 *    pulse-train is synthesised (PWM) from the key bits and TE so the sample can
 *    still be interpreted and transmitted.
 *
 * Header fields parsed: Filetype, Version, Frequency (Hz), Preset, Protocol.
 */
data class FlipperSample(
    val name: String,
    val filetype: String,
    val version: String,
    val frequencyHz: Long,
    val preset: String,
    val protocol: String,
    /** Signed RAW durations exactly as in the file (RAW files only). */
    val rawSigned: IntArray?,
    /** Keyed-file metadata. */
    val keyHex: String?,
    val bitCount: Int?,
    val teUs: Int?,
    val bits: String?,
) {
    val isRaw: Boolean get() = protocol.equals("RAW", true) && rawSigned != null

    /** True when the preset is an OOK/ASK preset (replayable as pulses). */
    val isOok: Boolean get() = preset.contains("Ook", true) || preset.contains("ASK", true) ||
        protocol.equals("RAW", true)

    /**
     * Pulse-train (absolute microsecond durations) for the firmware `pulses`
     * command, or null if nothing usable could be derived.
     */
    fun toPulses(): IntArray? {
        rawSigned?.let { arr ->
            val abs = arr.map { kotlin.math.abs(it) }.filter { it in 1..60000 }
            return if (abs.isEmpty()) null else abs.toIntArray()
        }
        if (bits != null && teUs != null && bits.isNotEmpty()) {
            return OokDecoding.encodePwm(bits, teUs)
        }
        return null
    }

    fun summary(): String = buildString {
        append(protocol)
        if (frequencyHz > 0) append(" • ").append("%.5f MHz".format(frequencyHz / 1_000_000.0))
        if (preset.isNotEmpty()) append(" • ").append(preset.removePrefix("FuriHalSubGhzPreset"))
        rawSigned?.let { append(" • ").append(it.size).append(" edges") }
        bitCount?.let { append(" • ").append(it).append(" bit") }
    }

    override fun equals(other: Any?) = other is FlipperSample && name == other.name && protocol == other.protocol
    override fun hashCode() = name.hashCode() * 31 + protocol.hashCode()

    companion object {
        /** Recognise a Flipper .sub by its header. */
        fun looksLikeFlipper(text: String): Boolean =
            text.lineSequence().take(3).any { it.startsWith("Filetype:") && it.contains("Flipper", true) }

        fun parse(name: String, text: String): FlipperSample? {
            var filetype = ""; var version = ""; var preset = ""; var protocol = ""
            var frequency = 0L
            var bit: Int? = null; var te: Int? = null; var key: String? = null
            val raw = ArrayList<Int>()

            for (line in text.lineSequence()) {
                val l = line.trim()
                if (l.isEmpty()) continue
                val colon = l.indexOf(':')
                if (colon <= 0) continue
                val tag = l.substring(0, colon).trim()
                val value = l.substring(colon + 1).trim()
                when (tag) {
                    "Filetype" -> filetype = value
                    "Version" -> version = value
                    "Frequency" -> frequency = value.toLongOrNull() ?: 0L
                    "Preset" -> preset = value
                    "Protocol" -> protocol = value
                    "Bit" -> bit = value.toIntOrNull()
                    "TE" -> te = value.toIntOrNull()
                    "Key" -> key = value
                    "RAW_Data" -> value.split(Regex("\\s+")).forEach {
                        it.toIntOrNull()?.let { v -> raw.add(v) }
                    }
                }
            }
            if (filetype.isEmpty() && protocol.isEmpty() && raw.isEmpty()) return null

            // derive bits from a keyed file (MSB-first over the whole key, trimmed to Bit count)
            var bits: String? = null
            if (key != null && bit != null) {
                val bytes = key.split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16) }
                val sb = StringBuilder()
                for (b in bytes) for (i in 7 downTo 0) sb.append(if ((b shr i) and 1 == 1) '1' else '0')
                bits = if (sb.length >= bit) sb.substring(sb.length - bit) else sb.toString()
            }

            return FlipperSample(
                name = name,
                filetype = filetype,
                version = version,
                frequencyHz = frequency,
                preset = preset,
                protocol = protocol,
                rawSigned = if (raw.isNotEmpty()) raw.toIntArray() else null,
                keyHex = key,
                bitCount = bit,
                teUs = te,
                bits = bits,
            )
        }
    }
}
