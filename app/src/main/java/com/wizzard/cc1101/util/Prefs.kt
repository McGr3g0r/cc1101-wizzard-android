/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.util

import android.content.Context

/** Small typed wrapper over SharedPreferences for app settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cc1101_wizzard", Context.MODE_PRIVATE)

    var gpsEnabled: Boolean
        get() = sp.getBoolean(KEY_GPS, false)
        set(v) { sp.edit().putBoolean(KEY_GPS, v).apply() }

    /**
     * Extra gap [ms] inserted between repeated transmissions, on top of the frame's
     * own trailing guard. Default 0: an encoded OOK frame already ends with its
     * inter-frame guard (~15.6 ms for HCS), so repeats come ~15.6 ms apart — the
     * spacing a real remote uses and what the working raw replay has. A large extra
     * gap (the old 200 ms default) pushed repeats too far apart for receivers that
     * expect consecutive frames.
     */
    var txGapMs: Int
        get() = sp.getInt(KEY_TX_GAP, 0)
        set(v) { sp.edit().putInt(KEY_TX_GAP, v.coerceIn(0, 10000)).apply() }

    /** Selected colour-template id. */
    var themeId: String
        get() = sp.getString(KEY_THEME, "graphite") ?: "graphite"
        set(v) { sp.edit().putString(KEY_THEME, v).apply() }

    /** Re-establish the USB link automatically after an unexpected drop. */
    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_AUTORECONNECT, false)
        set(v) { sp.edit().putBoolean(KEY_AUTORECONNECT, v).apply() }

    /**
     * Transmit OOK via the firmware's hardware-clocked FIFO (`pulses playfifo`,
     * quantised to the frame's averaged Te) instead of the GDO0 bit-bang
     * (`pulses play`). Off keeps the original path.
     */
    var ookTxFifo: Boolean
        get() = sp.getBoolean(KEY_OOK_TX_FIFO, false)
        set(v) { sp.edit().putBoolean(KEY_OOK_TX_FIFO, v).apply() }

    /**
     * FIFO oversampling factor (1..32): FIFO quantum = Te / factor. Higher values
     * quantise pulses onto a finer grid (less timing distortion on jittery raw
     * captures) at the cost of more FIFO bytes. Default 8 (the firmware's own
     * guideline of ~1/8 of the shortest pulse).
     */
    var ookTxFifoFactor: Int
        get() = sp.getInt(KEY_OOK_TX_FIFO_FACTOR, 8)
        set(v) { sp.edit().putInt(KEY_OOK_TX_FIFO_FACTOR, v.coerceIn(1, 32)).apply() }

    /** OOK monitor capture: minimum pulse length [us] (runts shorter are trimmed). */
    var captureMinUs: Int
        get() = sp.getInt(KEY_CAP_MIN, 100)
        set(v) { sp.edit().putInt(KEY_CAP_MIN, v.coerceIn(0, 2000)).apply() }

    /**
     * OOK monitor capture: inter-frame gap [us] that ends a frame (0 = firmware default).
     *
     * Default is the firmware's own default (15000). A value near a protocol's
     * header gap splits one logical frame across two captures - e.g. HCS/KeeLoq's
     * header gap is ~10 ticks ~= 4000 us at Te=400, so the old 4000 default could
     * cut an HCS frame in two and defeat the decoder. Keep this comfortably above
     * the longest intra-frame LOW of the protocols you capture.
     */
    var captureGapUs: Int
        get() = sp.getInt(KEY_CAP_GAP, 15000)
        set(v) { sp.edit().putInt(KEY_CAP_GAP, v.coerceIn(0, 60000)).apply() }

    /**
     * OOK monitor carrier-sense gate [dBm]. The firmware only captures when RSSI
     * is at/above this (default -85). Lower it in a quiet RF environment to catch
     * weaker remotes; raise it where the noise floor is high. Sent as the 8th arg
     * of `monitor start` (older firmware without it ignores the extra token).
     */
    var captureRssiGate: Int
        get() = sp.getInt(KEY_CAP_RSSI, -85)
        set(v) { sp.edit().putInt(KEY_CAP_RSSI, v.coerceIn(-120, 0)).apply() }

    /**
     * Coalesce consecutive monitor captures that arrive close in time into one
     * analysis (repeats of the same transmission, or a frame split across
     * captures). Off = each capture is analysed on its own, as before.
     */
    var coalesceEnabled: Boolean
        get() = sp.getBoolean(KEY_COALESCE_ON, true)
        set(v) { sp.edit().putBoolean(KEY_COALESCE_ON, v).apply() }

    /**
     * Time window [ms] for [coalesceEnabled]: a new capture joins the current
     * group while it arrives within this of the previous one; a longer gap starts
     * a new group ("too far apart" = a different transmission). Seeded a little
     * above the firmware's own ~300 ms capture pacing so one button-press burst
     * groups together.
     */
    var coalesceWindowMs: Int
        get() = sp.getInt(KEY_COALESCE_MS, 500)
        set(v) { sp.edit().putInt(KEY_COALESCE_MS, v.coerceIn(50, 3000)).apply() }

    companion object {
        private const val KEY_GPS = "gps_enabled"
        private const val KEY_TX_GAP = "tx_gap_ms"
        private const val KEY_THEME = "theme_id"
        private const val KEY_AUTORECONNECT = "auto_reconnect"
        private const val KEY_OOK_TX_FIFO = "ook_tx_fifo"
        private const val KEY_OOK_TX_FIFO_FACTOR = "ook_tx_fifo_factor"
        private const val KEY_CAP_MIN = "capture_min_us"
        private const val KEY_CAP_GAP = "capture_gap_us"
        private const val KEY_CAP_RSSI = "capture_rssi_gate"
        private const val KEY_COALESCE_ON = "coalesce_enabled"
        private const val KEY_COALESCE_MS = "coalesce_window_ms"
    }
}
