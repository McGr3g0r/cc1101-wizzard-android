package com.wizzard.cc1101.cli

import android.content.Context
import com.wizzard.cc1101.R

/**
 * Firmware-specific command helpers, built on top of [CliEngine]. Everything
 * here maps 1:1 onto the v2 CLI grammar (radio / profile / monitor), and keeps
 * each transmitted line within the firmware's 80-char command buffer
 * (CMD_MAX_BUFFER_LEN in settings.h).
 */
class FirmwareCli(private val engine: CliEngine, private val appContext: Context) {

    companion object {
        /**
         * Device CLI limits (firmware settings.h / Parser.h):
         *  - CMD_MAX_BUFFER_LEN = 256  → Terminal accepts < 255 chars per line
         *  - PARSER_MAX_PARAMS  = 64   → at most 64 whitespace-separated tokens
         * We stay safely under both when building `pulses add` lines.
         */
        const val MAX_CMD_LEN = 250            // chars per command line (< 255)
        const val MAX_TOKENS = 64              // parser argv capacity
        const val PULSES_PREFIX_TOKENS = 4     // "radio r0 pulses add"
        const val MAX_VALUES_PER_ADD = MAX_TOKENS - PULSES_PREFIX_TOKENS  // 60
    }

    data class RadioIface(val name: String, val present: Boolean)

    /** Parsed response of the `info` command (device / firmware identification). */
    data class DeviceInfo(
        val device: String,
        val variant: String,
        val fw: String,
        val build: Int,
        val cli: Int,
        val platform: String,
        val radios: Int,
        val interfaces: List<RadioIface>,
        val raw: String,
    ) {
        /** This is the firmware we expect to talk to. */
        val isWizzard: Boolean get() = device == "cc1101-wizzard"
    }

    /**
     * Identify the connected device via the `info` command (JSON form). This is
     * the reliable way to confirm an RP2040 running cc1101-wizzard is attached
     * right after the USB connection opens. Returns null if the response is not
     * recognisable as this firmware.
     */
    suspend fun identify(): DeviceInfo? {
        val res = engine.send("info", timeoutMs = 3000)
        val jsonLine = res.lines.firstOrNull { it.trim().startsWith("{") && it.contains("\"device\"") }
            ?: return null
        return try {
            val o = org.json.JSONObject(jsonLine.trim())
            val ifs = ArrayList<RadioIface>()
            o.optJSONArray("interfaces")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val io = arr.getJSONObject(i)
                    ifs.add(RadioIface(io.optString("name", "r$i"), io.optBoolean("present")))
                }
            }
            DeviceInfo(
                device = o.optString("device"),
                variant = o.optString("variant"),
                fw = o.optString("fw"),
                build = o.optInt("build"),
                cli = o.optInt("cli"),
                platform = o.optString("platform"),
                radios = o.optInt("radios"),
                interfaces = ifs,
                raw = jsonLine.trim(),
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verify we are talking to this firmware. Prefers the `info` command (device
     * identification); falls back to `radio list` for older builds that lack it.
     */
    suspend fun verify(): Pair<Boolean, List<RadioIface>> {
        identify()?.let { return it.isWizzard to it.interfaces }

        // fallback for firmware without the `info` command
        val res = engine.send("radio list", timeoutMs = 3000)
        val ifaces = res.lines
            .map { it.trim() }
            .filter { it.startsWith("- r") && it.contains("present=") }
            .map {
                val name = it.removePrefix("- ").substringBefore(' ').trim()
                val present = it.substringAfter("present=").trim() == "yes"
                RadioIface(name, present)
            }
        val looksRight = res.ok && (ifaces.isNotEmpty() || res.lines.any { it.contains("interfaces:") })
        return looksRight to ifaces
    }

    /** Full parameter dump for an interface, parsed into a map (key -> value string). */
    suspend fun info(iface: String): Map<String, String> {
        val res = engine.send("radio $iface info", timeoutMs = 4000)
        val map = LinkedHashMap<String, String>()
        for (raw in res.lines) {
            val l = raw.trim()
            val eq = l.indexOf('=')
            if (eq > 0) {
                val key = l.substring(0, eq).trim()
                var value = l.substring(eq + 1).trim()
                // strip a trailing unit / "(n)" annotation
                value = value.substringBefore(" (").trim()
                if (key.isNotEmpty()) map[key] = value
            }
        }
        return map
    }

    suspend fun setParam(iface: String, name: String, value: String): CliEngine.CmdResult =
        engine.send("radio $iface set $name $value")

    suspend fun setMod(iface: String, mod: String): CliEngine.CmdResult =
        engine.send("radio $iface set mod $mod")

    suspend fun rx(iface: String) = engine.send("radio $iface rx")
    suspend fun idle(iface: String) = engine.send("radio $iface idle")
    suspend fun reset(iface: String) = engine.send("radio $iface reset")

    // ---- profiles (LittleFS, firmware side) -----------------------------

    suspend fun profileList(): List<String> {
        val res = engine.send("profile list")
        return res.lines.map { it.trim() }
            .filter { it.isNotEmpty() && it != "profiles:" }
    }

    suspend fun profileShow(name: String): List<String> {
        val res = engine.send("profile show $name")
        return res.lines.map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun profileNew(name: String) = engine.send("profile new $name")
    suspend fun profileDel(name: String) = engine.send("profile del $name")

    suspend fun profileAddLine(name: String, line: String) =
        engine.send("profile add $name $line")

    /** Recreate a profile from a list of radio sub-command lines. */
    suspend fun profileWrite(name: String, lines: List<String>): Boolean {
        if (!profileNew(name).ok) return false
        for (l in lines) {
            val trimmed = l.trim()
            if (trimmed.isEmpty()) continue
            if (!profileAddLine(name, trimmed).ok) return false
        }
        return true
    }

    suspend fun profileApply(name: String, iface: String) =
        engine.send("profile apply $name $iface")

    // ---- monitor --------------------------------------------------------

    /**
     * Start the monitor. [minUs]/[maxGapUs] (both > 0) are the OOK capture
     * window parameters added in firmware build 2 — the shortest pulse kept and
     * the inter-frame gap that ends a frame. [rssiGate] (< 0 dBm) is the OOK
     * carrier-sense gate added later: the firmware only captures when RSSI is at
     * or above it. Appended as
     * `monitor start <if> <profile> json <minUs> <maxGapUs> [rssiGate]`; each
     * trailing argument is only sent when the ones before it are present, so they
     * fall back to the firmware defaults on older builds (which simply ignore an
     * extra token they don't parse).
     */
    suspend fun monitorStart(
        iface: String, profile: String, json: Boolean = true,
        minUs: Int = 0, maxGapUs: Int = 0, rssiGate: Int = 0,
    ): CliEngine.CmdResult {
        val fmt = if (json) "json" else "hex"
        val window = if (minUs > 0 && maxGapUs > 0) " $minUs $maxGapUs" else ""
        // rssiGate only makes sense once the window args are present (it's the 8th token)
        val gate = if (window.isNotEmpty() && rssiGate < 0) " $rssiGate" else ""
        return engine.send("monitor start $iface $profile $fmt$window$gate", timeoutMs = 5000)
    }

    suspend fun monitorStop() = engine.send("monitor stop")
    suspend fun monitorStatus() = engine.send("monitor status")

    // ---- transmit -------------------------------------------------------

    /** Packet-mode send in the currently configured modulation. */
    suspend fun sendHex(iface: String, hex: String): CliEngine.CmdResult {
        val cmd = "radio $iface send ${hex.lowercase()}"
        if (cmd.length > MAX_CMD_LEN) {
            return CliEngine.CmdResult(cmd, false,
                listOf(appContext.getString(R.string.fw_packet_too_long, cmd.length, MAX_CMD_LEN)))
        }
        return engine.send(cmd)
    }

    /**
     * Raw OOK transmit: flush, add the pulse-train split into chunks that respect
     * BOTH the device command-line length (MAX_CMD_LEN) and the parser token
     * limit (MAX_VALUES_PER_ADD values per `pulses add`), then play.
     *
     * [repeat] and [gapUs] use the firmware's on-device repeat/gap
     * (`pulses play <off> <n> [repeat] [gapUs]`), so the inter-frame timing is
     * generated precisely on the RP2040 in a single command. Returns true if
     * every step reported OK.
     */
    suspend fun sendPulses(iface: String, us: IntArray, repeat: Int = 1, gapUs: Long = 0): Boolean {
        val count = flushAndAdd(iface, us) ?: return false
        val rep = repeat.coerceAtLeast(1)
        val play = if (rep > 1 || gapUs > 0)
            "radio $iface pulses play 0 $count $rep $gapUs"
        else
            "radio $iface pulses play 0 $count"
        // playing repeat×frames can take a while on-device; allow a generous timeout
        val timeout = 4000L + rep * 1500L
        return engine.send(play, timeoutMs = timeout).ok
    }

    /**
     * Hardware-clocked OOK transmit via the firmware's `pulses playfifo` (build 12+):
     * each stored pulse is quantised to `round(duration / unitUs)` bits and streamed
     * through the TX FIFO at the radio's bit-rate generator, instead of bit-banging
     * GDO0. Bit timing is then jitter-free, and for a frame rebuilt from a decode
     * (pulses are exact multiples of Te) choosing `unitUs = Te` reproduces it
     * losslessly; for a raw capture the same choice snaps per-pulse jitter onto the
     * Te grid.
     *
     * The FIFO clocks each bit at the radio's data rate. Firmware build 12a+
     * derives that from `unitUs` itself (DRATE = round(1e6/unitUs), clamped to the
     * CC1101 range) and restores the profile's rate after the burst, so this must
     * NOT set `drate` separately: doing so would make the firmware save and then
     * "restore" our value instead of the profile's, clobbering the profile's data
     * rate. Returns true if every step reported OK.
     */
    suspend fun sendPulsesFifo(
        iface: String, us: IntArray, unitUs: Int, repeat: Int = 1, gapUs: Long = 0,
    ): Boolean {
        if (unitUs < 1) return false
        val count = flushAndAdd(iface, us) ?: return false
        val rep = repeat.coerceAtLeast(1)
        val play = "radio $iface pulses playfifo 0 $count $unitUs $rep $gapUs"
        val timeout = 4000L + rep * 1500L
        return engine.send(play, timeoutMs = timeout).ok
    }

    /**
     * `pulses flush` then the train as one or more `pulses add` commands, each
     * split to respect BOTH the device command-line length (MAX_CMD_LEN) and the
     * parser token limit (MAX_VALUES_PER_ADD). Returns the number of values stored,
     * or null if any command failed.
     */
    private suspend fun flushAndAdd(iface: String, us: IntArray): Int? {
        if (us.isEmpty()) return null
        if (!engine.send("radio $iface pulses flush").ok) return null

        val prefix = "radio $iface pulses add"
        val sb = StringBuilder(prefix)
        var valuesInChunk = 0
        var count = 0
        for (v in us) {
            val token = " $v"
            val tooLong = sb.length + token.length > MAX_CMD_LEN
            val tooMany = valuesInChunk >= MAX_VALUES_PER_ADD
            if (tooLong || tooMany) {
                if (!engine.send(sb.toString()).ok) return null
                sb.setLength(0); sb.append(prefix); valuesInChunk = 0
            }
            sb.append(token); valuesInChunk++; count++
        }
        if (valuesInChunk > 0) {
            if (!engine.send(sb.toString()).ok) return null
        }
        return count
    }
}
