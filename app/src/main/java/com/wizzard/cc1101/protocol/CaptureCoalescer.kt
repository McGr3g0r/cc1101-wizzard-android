package com.wizzard.cc1101.protocol

import com.wizzard.cc1101.cli.CapturedPacket

/**
 * Groups consecutive monitor captures that belong to the same transmission.
 *
 * The firmware emits one aligned frame per capture and paces captures ~300 ms
 * apart, so a single button-press burst (or a frame the firmware split at an
 * over-tight `maxGapUs`) arrives as a short run of captures close in time. This
 * class buckets that run into a [Group]; a capture that arrives more than
 * [windowMs] after the previous one — or on a different interface, or of a
 * different kind (OOK vs FSK) — starts a new group ("too far apart" = a
 * different signal).
 *
 * It is a pure, allocation-light state machine: no threads, no timers. The
 * ViewModel calls [offer] for every capture as it arrives, and [groupFor] when
 * the user taps a log line, to analyse the whole group that line belongs to.
 * When [enabled] is false every capture becomes its own singleton group, so
 * callers get exactly the pre-coalescing behaviour.
 */
class CaptureCoalescer(
    var enabled: Boolean = true,
    var windowMs: Int = 500,
) {
    enum class Kind { OOK, FSK }

    class Group(
        val id: Long,
        val iface: String,
        val kind: Kind,
        var firstMs: Long,
        var lastMs: Long,
    ) {
        val captures = ArrayList<CapturedPacket>()
        val size: Int get() = captures.size
    }

    private var nextId = 1L
    private var current: Group? = null

    /** Closed + current groups, newest last, bounded so memory can't grow unbounded. */
    private val history = ArrayDeque<Group>()
    private var trackedCaptures = 0

    private fun kindOf(p: CapturedPacket) =
        if (p is CapturedPacket.Ook) Kind.OOK else Kind.FSK

    /**
     * Record a capture under its arrival time [nowMs] (host clock). Returns the
     * group it joined (which may be a brand-new one). The returned group is live:
     * later captures of the same burst may still be appended to it.
     */
    fun offer(packet: CapturedPacket, nowMs: Long): Group {
        val kind = kindOf(packet)
        val cur = current
        val joins = enabled && cur != null &&
            cur.iface == packet.iface &&
            cur.kind == kind &&
            (nowMs - cur.lastMs) <= windowMs

        val g = if (joins) {
            cur!!
        } else {
            Group(nextId++, packet.iface, kind, nowMs, nowMs).also {
                current = it
                history.addLast(it)
            }
        }
        g.captures.add(packet)
        g.lastMs = nowMs
        trackedCaptures++
        trim()
        return g
    }

    /**
     * The group a previously-offered [packet] belongs to, or null if it has been
     * evicted from the bounded history. Identity match: the same object that was
     * attached to the tapped log line.
     */
    fun groupFor(packet: CapturedPacket): Group? {
        for (i in history.indices.reversed()) {
            val g = history[i]
            if (g.captures.any { it === packet }) return g
        }
        return null
    }

    /** Forget everything (e.g. when the monitor stops or the log is cleared). */
    fun reset() {
        current = null
        history.clear()
        trackedCaptures = 0
    }

    private fun trim() {
        while (trackedCaptures > MAX_TRACKED && history.size > 1) {
            val dropped = history.removeFirst()
            trackedCaptures -= dropped.size
            if (dropped === current) current = null
        }
    }

    companion object {
        /** Upper bound on captures kept for tap-to-analyse lookup. */
        const val MAX_TRACKED = 300
    }
}
