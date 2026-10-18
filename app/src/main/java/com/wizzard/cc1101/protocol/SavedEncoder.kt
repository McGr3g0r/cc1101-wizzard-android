/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.protocol

import com.wizzard.cc1101.cli.MonitorParser
import com.wizzard.cc1101.cli.TxFrame

/**
 * Rebuilds a transmittable [TxFrame] from the (edited) fields of a saved packet,
 * keyed by analyzer id. Mirrors the encoders in [Analyzers] but works purely
 * from stored field values, so saved packets can be re-sent without the
 * original capture.
 */
object SavedEncoder {

    fun encode(analyzerId: String, fields: List<ProtoField>): TxFrame? {
        fun v(key: String) = fields.firstOrNull { it.key == key }?.value
        return when (analyzerId) {
            "ev1527" -> {
                val addr = v("addr")?.let { parseLong(it) } ?: return null
                val data = v("data")?.filter { it == '0' || it == '1' }?.take(4)?.padEnd(4, '0') ?: "0000"
                val te = v("te")?.toIntOrNull() ?: 350
                val bits = OokDecoding.longToBits(addr and 0xFFFFF, 20) + data
                TxFrame.OokPulses(OokDecoding.encodePwm(bits, te))
            }
            "pt2240" -> {
                val code = v("code")?.let { parseLong(it) } ?: return null
                val te = v("te")?.toIntOrNull() ?: 270
                val bits = OokDecoding.longToBits(code and 0xFFFFFF, 24)
                val arr = OokDecoding.encodePwm(bits, te, appendSync = false).toMutableList()
                arr.add(te); arr.add(te * 7)
                TxFrame.OokPulses(arr.toIntArray())
            }
            "hcs200", "hcs300" -> hcsEncode(fields, fields.firstOrNull { it.key == "te" }?.value?.toIntOrNull() ?: 400)
            "nice_flors" -> niceFlorSEncode(fields, fields.firstOrNull { it.key == "te" }?.value?.toIntOrNull() ?: 500)
            "somfy" -> somfyEncode(fields, fields.firstOrNull { it.key == "te" }?.value?.toIntOrNull() ?: 640)
            "db200" -> db200Encode(fields, fields.firstOrNull { it.key == "te" }?.value?.toIntOrNull() ?: 333)
            "manchester" -> {
                val b = v("bits")?.filter { it == '0' || it == '1' } ?: return null
                val te = v("te")?.toIntOrNull() ?: 320
                if (b.isEmpty()) null else TxFrame.OokPulses(OokDecoding.encodeManchester(b, te))
            }
            "generic_ook" -> {
                val csv = v("us") ?: return null
                val arr = csv.split(Regex("[,\\s]+")).mapNotNull { it.trim().toIntOrNull() }
                if (arr.isEmpty()) null else TxFrame.OokPulses(arr.toIntArray())
            }
            "generic_fsk" -> {
                val hex = v("hex")?.filter { !it.isWhitespace() } ?: return null
                if (hex.length >= 2 && hex.length % 2 == 0) TxFrame.FskHex(hex.lowercase()) else null
            }
            "rolling_fsk" -> {
                val idv = v("id")?.filter { !it.isWhitespace() } ?: return null
                val cnt = v("counter")?.toIntOrNull() ?: return null
                val idb = MonitorParser.hexToBytes(idv)
                val out = ByteArray(idb.size + 2)
                System.arraycopy(idb, 0, out, 0, idb.size)
                out[idb.size] = (cnt and 0xFF).toByte()
                out[idb.size + 1] = ((cnt shr 8) and 0xFF).toByte()
                TxFrame.FskHex(MonitorParser.bytesToHex(out))
            }
            else -> null
        }
    }

    private fun parseLong(s: String): Long? {
        val t = s.trim()
        return try {
            if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16) else t.toLong()
        } catch (e: Exception) { null }
    }
}
