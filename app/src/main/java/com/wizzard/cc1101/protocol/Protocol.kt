package com.wizzard.cc1101.protocol

import com.wizzard.cc1101.cli.CapturedPacket
import com.wizzard.cc1101.cli.TxFrame

/**
 * Dynamic confidence scoring. Instead of a hand-tuned constant per protocol, a
 * score is a structural prior ([base], reflecting how distinctive the framing is
 * and therefore how unlikely a chance match) blended with the measured [quality]
 * of THIS decode (0..1): timing jitter against the Te grid, whether the frame was
 * closed by a separator, and whether the exact bit count was recovered.
 *
 * score = base + (1 - base) * quality
 *
 * So two protocols that both pass their gate are ranked by how well the captured
 * pulses actually fit each one, not by a fixed number; and a clean match of a
 * distinctive protocol still outranks a clean match of a generic one.
 */
object Scoring {
    /** Blend a specificity prior with a measured fit quality into a 0..1 score. */
    fun score(base: Float, quality: Double): Float =
        (base + (1f - base) * quality.toFloat().coerceIn(0f, 1f)).coerceIn(0f, 1f)

    /**
     * Fit quality from measurable signals.
     * @param meanTickErr mean fractional-tick jitter of the decoded pulses (0 best, 0.5 worst)
     * @param separator   true if the frame ended on the expected inter-frame gap
     * @param exactBits   true if the exact expected bit count was decoded (no padding/emulation)
     */
    fun quality(meanTickErr: Double, separator: Boolean, exactBits: Boolean): Double {
        val grid = (1.0 - 2.0 * meanTickErr).coerceIn(0.0, 1.0)   // 0 err -> 1.0, 0.5 err -> 0.0
        val sep = if (separator) 1.0 else 0.5
        val exact = if (exactBits) 1.0 else 0.6
        return (0.70 * grid + 0.15 * sep + 0.15 * exact).coerceIn(0.0, 1.0)
    }
}

/** Field types that drive how a value is rendered / edited in the UI. */
enum class FieldType { HEX, DEC, BITS, TEXT, TICKER }

/**
 * One editable (or read-only) field extracted from a packet: an address/code,
 * a rolling counter, a raw byte string, a timing, etc.
 *
 * For [FieldType.TICKER] the value is an integer edited with +/- steppers bound
 * to [min]..[max]; [min]/[max] are ignored for the other types.
 */
data class ProtoField(
    val key: String,
    val label: String,
    val value: String,
    val type: FieldType,
    val editable: Boolean = true,
    val min: Int = 0,
    val max: Int = 0,
)

/**
 * The result of one analyzer matching one packet.
 *
 * [score] in 0..1 expresses confidence; the registry shows the highest-scoring
 * result as the best match. [encode] rebuilds a transmittable frame from the
 * (possibly user-edited) fields, or returns null if the edits are invalid.
 */
data class AnalysisResult(
    val analyzerId: String,
    val protocol: String,
    val score: Float,
    val summary: String,
    val fields: List<ProtoField>,
    val canTransmit: Boolean,
    val encode: (List<ProtoField>) -> TxFrame?,
)

/**
 * Pluggable protocol analyzer. Add a new decoder by implementing this interface
 * and registering it in [AnalyzerRegistry]. Each analyzer inspects a decoded
 * capture and either claims it (returns a result with a confidence score) or
 * declines (returns null).
 */
interface ProtocolAnalyzer {
    val id: String
    val displayName: String

    /** Return a result if this analyzer recognises the packet, else null. */
    fun analyze(packet: CapturedPacket): AnalysisResult?
}

/**
 * Holds the registered analyzers and runs a packet through all of them.
 * Register custom implementations via [register] (e.g. at app start).
 */
object AnalyzerRegistry {

    private val analyzers = mutableListOf<ProtocolAnalyzer>()

    init {
        // built-in analyzers; order is irrelevant, ranking is by score
        register(Ev1527Analyzer())
        register(Pt2240Analyzer())
        register(Hcs200Analyzer())
        register(Hcs300Analyzer())
        register(NiceFlorSAnalyzer())
        register(SomfyAnalyzer())
        register(Db200Analyzer())
        register(ManchesterAnalyzer())
        register(GenericOokAnalyzer())
        register(GenericFskAnalyzer())
        register(RollingCodeFskAnalyzer())
    }

    fun register(a: ProtocolAnalyzer) {
        if (analyzers.none { it.id == a.id }) analyzers.add(a)
    }

    fun unregister(id: String) {
        analyzers.removeAll { it.id == id }
    }

    fun all(): List<ProtocolAnalyzer> = analyzers.toList()

    /** Run every analyzer; return matches sorted best-first. */
    fun analyze(packet: CapturedPacket): List<AnalysisResult> =
        analyzers.mapNotNull { runCatching { it.analyze(packet) }.getOrNull() }
            .sortedByDescending { it.score }

    fun best(packet: CapturedPacket): AnalysisResult? = analyze(packet).firstOrNull()
}
