/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.protocol

import com.wizzard.cc1101.cli.CapturedPacket

/**
 * Analyses a whole [CaptureCoalescer.Group] instead of a single capture.
 *
 * Two complementary strategies, in order:
 *
 *  1. **Decode-and-vote** (always): run every capture through [AnalyzerRegistry],
 *     group the results by analyzer, keep the best-scoring one per analyzer and
 *     reconcile its field values by majority vote across the repeats. Consistent
 *     repeats both correct single-capture bit errors and raise confidence. This
 *     reuses the per-frame decoders untouched, so it is always safe.
 *
 *  2. **Merged-pulse fallback** (OOK groups with ≥2 captures): rebuild one pulse
 *     train from the captures, re-inserting the inter-capture gap the firmware
 *     dropped when it ended each capture, and run the analyzers on that. This
 *     recovers a single logical frame that was split across captures — e.g. an
 *     HCS/KeeLoq frame cut at its header gap by an over-tight `maxGapUs`, where
 *     the preamble lands in one capture and the data bits in the next and neither
 *     decodes alone. Results from this pass are merged with the voted results,
 *     keeping the higher score per analyzer, so it only ever helps.
 *
 * Both return the same `List<AnalysisResult>` the single-capture path produces,
 * so the UI is unchanged.
 */
object CoalescedAnalysis {

    /**
     * @param gapUs the monitor's configured inter-frame gap (Prefs.captureGapUs);
     *   used as the best estimate of the LOW that was lost between split captures.
     */
    fun analyze(group: CaptureCoalescer.Group, gapUs: Int): List<AnalysisResult> {
        val caps = group.captures
        if (caps.isEmpty()) return emptyList()
        if (caps.size == 1) return AnalyzerRegistry.analyze(caps[0])

        val voted = voteAcrossCaptures(caps)

        val merged = if (group.kind == CaptureCoalescer.Kind.OOK)
            mergedPulseResults(caps, gapUs)
        else
            emptyList()

        return unionByAnalyzer(voted, merged).sortedByDescending { it.score }
    }

    // ---- strategy 1: decode every capture, vote per analyzer ----------------

    private fun voteAcrossCaptures(caps: List<CapturedPacket>): List<AnalysisResult> {
        // analyzerId -> every result that analyzer produced across the captures
        val byId = LinkedHashMap<String, MutableList<AnalysisResult>>()
        for (c in caps) {
            for (r in AnalyzerRegistry.analyze(c)) {
                byId.getOrPut(r.analyzerId) { ArrayList() }.add(r)
            }
        }

        val out = ArrayList<AnalysisResult>(byId.size)
        for ((_, results) in byId) {
            val rep = results.maxByOrNull { it.score } ?: continue
            val support = results.size                       // captures that yielded this analyzer

            // majority-vote each field value across the captures that produced it
            var agreeSum = 0.0; var agreeN = 0
            val votedFields = rep.fields.map { f ->
                val values = results.mapNotNull { r -> r.fields.firstOrNull { it.key == f.key }?.value }
                if (values.isEmpty()) return@map f
                val (best, count) = majority(values)
                agreeSum += count.toDouble() / values.size; agreeN++
                if (best == f.value) f else f.copy(value = best)
            }

            // confidence boost: how consistently the repeats agree, scaled by how
            // many of the group's captures backed this analyzer at all.
            val fieldAgreement = if (agreeN > 0) agreeSum / agreeN else 0.0
            val coverage = support.toDouble().coerceAtMost(caps.size.toDouble()) / caps.size
            val quality = fieldAgreement * coverage
            val score = Scoring.score(rep.score, quality)

            out.add(
                rep.copy(
                    score = score,
                    fields = votedFields,
                    summary = annotate(rep.summary, support),
                )
            )
        }
        return out
    }

    // ---- strategy 2: rebuild one train from the split captures ---------------

    private fun mergedPulseResults(caps: List<CapturedPacket>, gapUs: Int): List<AnalysisResult> {
        val ook = caps.filterIsInstance<CapturedPacket.Ook>().filter { it.us.isNotEmpty() }
        if (ook.size < 2) return emptyList()
        val best = ook.maxByOrNull { it.rssi } ?: ook.first()
        val shortUs = OokDecoding.estimateTiming(ook.first().us).shortUs.takeIf { it > 0 } ?: 300

        // The gap the firmware dropped when it split the frame is unknown, and
        // different decoders want a different size: the PWM frame-splitter wants a
        // clear inter-frame separator, while the KeeLoq scanner wants its ~10-tick
        // header gap. Try both and keep whatever decodes best per analyzer.
        val candidateGaps = linkedSetOf(
            maxOf(gapUs, shortUs * 8, 12000),   // boundary separator (generic PWM)
            shortUs * 10,                        // header-sized (HCS/KeeLoq)
        )

        val collected = ArrayList<AnalysisResult>()
        for (gap in candidateGaps) {
            val merged = mergePulses(ook, gap) ?: continue
            val pkt = CapturedPacket.Ook(best.iface, best.ts, best.rssi, best.lqi, merged, "merged:${ook.size}")
            for (r in AnalyzerRegistry.analyze(pkt))
                collected.add(r.copy(summary = r.summary + " [merged ${ook.size}]"))
        }
        return collected
    }

    /**
     * Concatenate capture pulse-trains into one, re-inserting the inter-capture
     * LOW the firmware dropped when it ended each capture.
     *
     * Pulses alternate HIGH,LOW,… from index 0, so every capture must begin on an
     * even (HIGH) index. Captures normally end on a HIGH (the terminating LOW gap
     * is never recorded) = odd length; any capture that ends on a LOW (even
     * length) has its trailing LOW dropped so it ends on a HIGH too. Each
     * normalised capture (odd) plus one inserted LOW gap spans an even count, so
     * the next capture again starts on a HIGH — no fabricated HIGH, so bit
     * alignment is preserved exactly (an earlier version inserted a filler HIGH
     * and shifted every decoded field by one bit).
     */
    fun mergePulses(caps: List<CapturedPacket.Ook>, gapUs: Int): IntArray? {
        val gap = gapUs.coerceAtLeast(1)
        val out = ArrayList<Int>()
        for (c in caps) {
            if (c.us.isEmpty()) continue
            // normalise this capture to end on a HIGH (odd length)
            val endsOnHigh = c.us.size % 2 == 1
            val take = if (endsOnHigh) c.us.size else c.us.size - 1
            if (take <= 0) continue
            if (out.isNotEmpty()) out.add(gap)   // single LOW separator (out.size is even here)
            for (i in 0 until take) out.add(c.us[i])
        }
        return if (out.isEmpty()) null else out.toIntArray()
    }

    // ---- helpers -------------------------------------------------------------

    private fun unionByAnalyzer(
        a: List<AnalysisResult>, b: List<AnalysisResult>,
    ): List<AnalysisResult> {
        val best = LinkedHashMap<String, AnalysisResult>()
        for (r in a) best[r.analyzerId] = r
        for (r in b) {
            val cur = best[r.analyzerId]
            if (cur == null || r.score > cur.score) best[r.analyzerId] = r
        }
        return best.values.toList()
    }

    /** Most frequent value and its count (first-seen wins ties). */
    private fun majority(values: List<String>): Pair<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for (v in values) counts[v] = (counts[v] ?: 0) + 1
        val top = counts.maxByOrNull { it.value }!!
        return top.key to top.value
    }

    private fun annotate(summary: String, repeats: Int): String =
        if (repeats > 1) "$summary ×$repeats" else summary
}
