package com.wizzard.cc1101.data

import android.content.Context
import com.wizzard.cc1101.protocol.AnalysisResult
import com.wizzard.cc1101.protocol.FieldType
import com.wizzard.cc1101.protocol.ProtoField
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists edited packets (codes, counters, pulse-trains, ...) as JSON files in
 * a `data` subdirectory of the app's private storage
 * (filesDir then data, one JSON file per saved packet).
 */
class PacketStore(context: Context) {

    val dir: File = File(context.filesDir, "data").apply { mkdirs() }

    /** Optional GPS tag written alongside a saved packet when GPS is enabled. */
    data class Gps(val lat: Double, val lon: Double, val timeMs: Long, val provider: String, val accuracyM: Float?)

    data class Saved(
        val file: File,
        val name: String,
        val analyzerId: String,
        val protocol: String,
        val fields: List<ProtoField>,
        val savedAtMs: Long,
        val gps: Gps? = null,
    )

    private fun safeName(name: String): String =
        name.trim().ifEmpty { "packet" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)

    fun save(name: String, result: AnalysisResult, fields: List<ProtoField>, gps: Gps? = null): File {
        val obj = JSONObject()
        obj.put("name", name)
        obj.put("analyzerId", result.analyzerId)
        obj.put("protocol", result.protocol)
        obj.put("savedAtMs", System.currentTimeMillis())
        val arr = JSONArray()
        for (f in fields) {
            arr.put(JSONObject().apply {
                put("key", f.key)
                put("label", f.label)
                put("value", f.value)
                put("type", f.type.name)
                put("editable", f.editable)
                if (f.min != 0 || f.max != 0) { put("min", f.min); put("max", f.max) }
            })
        }
        obj.put("fields", arr)
        if (gps != null) {
            obj.put("gps", JSONObject().apply {
                put("lat", gps.lat); put("lon", gps.lon)
                put("timeMs", gps.timeMs); put("provider", gps.provider)
                gps.accuracyM?.let { put("accuracyM", it.toDouble()) }
            })
        }

        val base = safeName(name)
        val file = File(dir, "$base.json")
        file.writeText(obj.toString(2))
        // also a human-readable text sidecar (includes GPS if present)
        writeTextSidecar(File(dir, "$base.txt"), name, result.protocol, result.analyzerId, fields, gps)
        return file
    }

    private fun writeTextSidecar(
        txt: File, name: String, protocol: String, analyzerId: String,
        fields: List<ProtoField>, gps: Gps?,
    ) {
        val sb = StringBuilder()
        sb.append("name     : ").append(name).append('\n')
        sb.append("protocol : ").append(protocol).append('\n')
        sb.append("analyzer : ").append(analyzerId).append('\n')
        sb.append("savedAt  : ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())).append('\n')
        fields.forEach { sb.append(it.key).append(" = ").append(it.value).append('\n') }
        if (gps != null) {
            sb.append("gps.lat  : ").append(gps.lat).append('\n')
            sb.append("gps.lon  : ").append(gps.lon).append('\n')
            sb.append("gps.time : ").append(gps.timeMs).append('\n')
            sb.append("gps.src  : ").append(gps.provider)
            gps.accuracyM?.let { sb.append(" (±").append(it).append("m)") }
            sb.append('\n')
        }
        runCatching { txt.writeText(sb.toString()) }
    }

    fun list(): List<Saved> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { parse(it) }
            .sortedByDescending { it.savedAtMs }

    fun load(file: File): Saved? = parse(file)

    fun delete(file: File): Boolean {
        // remove the human-readable sidecar too
        File(file.parentFile, file.nameWithoutExtension + ".txt").takeIf { it.exists() }?.delete()
        return file.delete()
    }

    private fun parse(file: File): Saved? = try {
        val o = JSONObject(file.readText())
        val fields = ArrayList<ProtoField>()
        val arr = o.optJSONArray("fields") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val fo = arr.getJSONObject(i)
            fields.add(
                ProtoField(
                    key = fo.getString("key"),
                    label = fo.optString("label", fo.getString("key")),
                    value = fo.getString("value"),
                    type = runCatching { FieldType.valueOf(fo.optString("type", "TEXT")) }
                        .getOrDefault(FieldType.TEXT),
                    editable = fo.optBoolean("editable", true),
                    min = fo.optInt("min", 0),
                    max = fo.optInt("max", 0),
                )
            )
        }
        val gps = o.optJSONObject("gps")?.let { g ->
            Gps(
                lat = g.optDouble("lat"),
                lon = g.optDouble("lon"),
                timeMs = g.optLong("timeMs"),
                provider = g.optString("provider", "?"),
                accuracyM = if (g.has("accuracyM")) g.optDouble("accuracyM").toFloat() else null,
            )
        }
        Saved(
            file = file,
            name = o.optString("name", file.nameWithoutExtension),
            analyzerId = o.optString("analyzerId", ""),
            protocol = o.optString("protocol", ""),
            fields = fields,
            savedAtMs = o.optLong("savedAtMs", file.lastModified()),
            gps = gps,
        )
    } catch (e: Exception) {
        null
    }
}
