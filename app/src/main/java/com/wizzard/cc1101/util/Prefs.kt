package com.wizzard.cc1101.util

import android.content.Context

/** Small typed wrapper over SharedPreferences for app settings. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cc1101_wizzard", Context.MODE_PRIVATE)

    var gpsEnabled: Boolean
        get() = sp.getBoolean(KEY_GPS, false)
        set(v) { sp.edit().putBoolean(KEY_GPS, v).apply() }

    /** Gap in milliseconds inserted between repeated transmissions (TX tab). */
    var txGapMs: Int
        get() = sp.getInt(KEY_TX_GAP, 200)
        set(v) { sp.edit().putInt(KEY_TX_GAP, v.coerceIn(0, 10000)).apply() }

    /** Selected colour-template id. */
    var themeId: String
        get() = sp.getString(KEY_THEME, "graphite") ?: "graphite"
        set(v) { sp.edit().putString(KEY_THEME, v).apply() }

    /** Re-establish the USB link automatically after an unexpected drop. */
    var autoReconnect: Boolean
        get() = sp.getBoolean(KEY_AUTORECONNECT, false)
        set(v) { sp.edit().putBoolean(KEY_AUTORECONNECT, v).apply() }

    /** OOK monitor capture: minimum pulse length [us] (runts shorter are trimmed). */
    var captureMinUs: Int
        get() = sp.getInt(KEY_CAP_MIN, 100)
        set(v) { sp.edit().putInt(KEY_CAP_MIN, v.coerceIn(0, 2000)).apply() }

    /** OOK monitor capture: inter-frame gap [us] that ends a frame (0 = firmware default). */
    var captureGapUs: Int
        get() = sp.getInt(KEY_CAP_GAP, 4000)
        set(v) { sp.edit().putInt(KEY_CAP_GAP, v.coerceIn(0, 60000)).apply() }

    companion object {
        private const val KEY_GPS = "gps_enabled"
        private const val KEY_TX_GAP = "tx_gap_ms"
        private const val KEY_THEME = "theme_id"
        private const val KEY_AUTORECONNECT = "auto_reconnect"
        private const val KEY_CAP_MIN = "capture_min_us"
        private const val KEY_CAP_GAP = "capture_gap_us"
    }
}
