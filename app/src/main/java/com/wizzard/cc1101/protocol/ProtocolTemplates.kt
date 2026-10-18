/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.protocol

import com.wizzard.cc1101.cli.TxFrame

/**
 * Templates for *composing* a protocol frame from scratch in the TX / Protocol
 * tab (as opposed to decoding a captured one). Each template lists the editable
 * fields pre-initialised with sensible defaults, and reuses [SavedEncoder] to
 * turn the (edited) fields into a transmittable [TxFrame].
 */
object ProtocolTemplates {

    data class Template(
        val id: String,
        val name: String,
        /** Fresh default field set each call (so edits never mutate the template). */
        val defaults: () -> List<ProtoField>,
    )

    val templates: List<Template> = listOf(
        Template("ev1527", "EV1527 / PT2262 (24-bit OOK)") {
            listOf(
                ProtoField("addr", "Address (20-bit code)", "0x12345", FieldType.HEX),
                ProtoField("data", "Data (4-bit)", "1010", FieldType.BITS),
                ProtoField("te", "Base time Te [us]", "350", FieldType.DEC),
            )
        },
        Template("pt2240", "PT2240 (24-bit OOK)") {
            listOf(
                ProtoField("code", "Code (24-bit)", "0x123456", FieldType.HEX),
                ProtoField("te", "Base time Te [us]", "270", FieldType.DEC),
            )
        },
        Template("hcs200", "HCS200 / KeeLoq (66-bit OOK)") { hcsDefaults() },
        Template("hcs300", "HCS300 / KeeLoq (66-bit OOK)") { hcsDefaults() },
        Template("nice_flors", "Nice Flor-S (52-bit OOK)") {
            listOf(
                ProtoField("serial", "Serial (28-bit)", "0x0000001", FieldType.HEX),
                ProtoField("counter", "Counter (16-bit)", "1", FieldType.DEC),
                ProtoField("btn", "Button (4-bit)", "0x1", FieldType.HEX),
                ProtoField("flags", "Flags (4-bit)", "0x0", FieldType.HEX),
                ProtoField("te", "Base time Te [us]", "500", FieldType.DEC),
            )
        },
        Template("somfy", "Somfy RTS (OOK, Manchester)") {
            listOf(
                ProtoField("code0", "Code0 (32-bit)", "0x00000000", FieldType.HEX),
                ProtoField("code1", "Code1 (32-bit)", "0x00000000", FieldType.HEX),
                ProtoField("code3", "Code3 (24-bit)", "0x000000", FieldType.HEX),
                ProtoField("next_code", "Next code (0-12)", "0", FieldType.TICKER, min = 0, max = 12),
                ProtoField("te", "Half-bit Te [us]", "640", FieldType.DEC),
                ProtoField("len", "Frame bits", "81", FieldType.DEC, editable = false),
            )
        },
        Template("db200", "DB200 (4-bit OOK)") {
            listOf(
                ProtoField("code", "Code (4-bit)", "0x5", FieldType.HEX),
                ProtoField("te", "Base time Te [us]", "333", FieldType.DEC),
            )
        },
        Template("manchester", "Manchester (bazowy, OOK)") {
            listOf(
                ProtoField("bits", "Bits", "10101010", FieldType.BITS),
                ProtoField("te", "Half-bit Te [us]", "320", FieldType.DEC),
            )
        },
        Template("generic_fsk", "Generic FSK (hex)") {
            listOf(
                ProtoField("hex", "Payload (hex)", "a1b2c3d4", FieldType.HEX),
            )
        },
        // ---- weather sensors (spoof a reading) ----
        Template("nexus", "Nexus (weather, 36-bit OOK)") { weatherDefaults(idDefault = "0x4B") },
        Template("acurite609", "Acurite 609TXC (weather)") { weatherDefaults(idDefault = "0xA3", withChannel = false) },
        Template("lacrosse_tx141", "LaCrosse TX141TH (weather)") { weatherDefaults(idDefault = "0x7C") },
        Template("oregon_v21", "Oregon Scientific v2.1 (weather)") {
            listOf(
                ProtoField("id", "Sensor id", "0x1D20", FieldType.HEX),
                ProtoField("rolling", "Rolling id (8-bit)", "0x2F", FieldType.HEX),
                ProtoField("channel", "Channel (1..3)", "1", FieldType.TICKER, min = 1, max = 3),
                ProtoField("battery", "Battery OK (1/0)", "1", FieldType.TICKER, min = 0, max = 1),
                ProtoField("temp", "Temperature [°C]", "21.5", FieldType.DEC),
                ProtoField("humidity", "Humidity [%]", "48", FieldType.TICKER, min = 0, max = 100),
                ProtoField("te", "Base time Te [us]", "488", FieldType.DEC),
            )
        },
    )

    private fun weatherDefaults(idDefault: String, withChannel: Boolean = true): List<ProtoField> = buildList {
        add(ProtoField("id", "Sensor id", idDefault, FieldType.HEX))
        if (withChannel) add(ProtoField("channel", "Channel (1..3)", "1", FieldType.TICKER, min = 1, max = 3))
        add(ProtoField("battery", "Battery OK (1/0)", "1", FieldType.TICKER, min = 0, max = 1))
        add(ProtoField("temp", "Temperature [°C]", "21.5", FieldType.DEC))
        add(ProtoField("humidity", "Humidity [%]", "48", FieldType.TICKER, min = 0, max = 100))
        add(ProtoField("te", "Base time Te [us]", "500", FieldType.DEC))
    }

    private fun hcsDefaults(): List<ProtoField> = listOf(
        ProtoField("enc", "Hopping code (32-bit, encrypted)", "0x00000000", FieldType.HEX),
        ProtoField("serial", "Serial (28-bit)", "0x0000001", FieldType.HEX),
        ProtoField("btn", "Button (4-bit)", "0x1", FieldType.HEX),
        ProtoField("vlow", "Battery low (0/1)", "0", FieldType.DEC),
        ProtoField("rpt", "Repeat (0/1)", "0", FieldType.DEC),
        ProtoField("te", "Base time Te [us]", "400", FieldType.DEC),
    )

    fun byId(id: String): Template? = templates.firstOrNull { it.id == id }

    fun encode(id: String, fields: List<ProtoField>): TxFrame? = SavedEncoder.encode(id, fields)
}
