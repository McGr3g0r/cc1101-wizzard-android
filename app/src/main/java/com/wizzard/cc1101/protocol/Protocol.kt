package com.wizzard.cc1101.protocol

import com.wizzard.cc1101.cli.CapturedPacket
import com.wizzard.cc1101.cli.TxFrame

/** Field types that drive how a value is rendered / edited in the UI. */
enum class FieldType { HEX, DEC, BITS, TEXT }

/**
 * One editable (or read-only) field extracted from a packet: an address/code,
 * a rolling counter, a raw byte string, a timing, etc.
 */
data class ProtoField(
    val key: String,
    val label: String,
    val value: String,
    val type: FieldType,
    val editable: Boolean = true,
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
