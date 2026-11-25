/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.R
import com.wizzard.cc1101.cli.SweepLine
import java.io.ByteArrayOutputStream

// ---- dBm → colour ramp (black → blue → cyan → green → yellow → red) --------
private fun dbmArgb(dbm: Float): Int {
    val t = ((dbm - SweepLine.DBM_MIN) / (SweepLine.DBM_MAX - SweepLine.DBM_MIN)).coerceIn(0f, 1f)
    // control stops
    val stops = intArrayOf(
        0xFF000010.toInt(), 0xFF00008B.toInt(), 0xFF00B2CA.toInt(),
        0xFF00C000.toInt(), 0xFFFFE000.toInt(), 0xFFFF2020.toInt(),
    )
    val seg = (t * (stops.size - 1))
    val i = seg.toInt().coerceIn(0, stops.size - 2)
    val f = (seg - i).coerceIn(0f, 1f)
    val a = stops[i]; val b = stops[i + 1]
    fun lerp(sh: Int): Int {
        val ca = (a shr sh) and 0xFF; val cb = (b shr sh) and 0xFF
        return (ca + ((cb - ca) * f)).toInt() and 0xFF
    }
    return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
}

/** Build an ARGB bitmap of the waterfall: one pixel row per pass, newest at top. */
private fun waterfallBitmap(rows: List<SweepLine>): Bitmap? {
    if (rows.isEmpty()) return null
    val w = rows.maxOf { it.n }.coerceAtLeast(1)
    val h = rows.size
    val px = IntArray(w * h)
    for (y in 0 until h) {
        val row = rows[h - 1 - y]   // newest first
        for (x in 0 until w) {
            val c = if (x < row.n) dbmArgb(row.dbmAt(x)) else 0xFF000010.toInt()
            px[y * w + x] = c
        }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}

/** Render a shareable PNG: title + waterfall + frequency axis. Returns PNG bytes. */
private fun waterfallPng(rows: List<SweepLine>): ByteArray? {
    val wf = waterfallBitmap(rows) ?: return null
    val first = rows.last()
    val outW = 1024
    val top = 40; val bottom = 48
    val plotH = 520
    val out = Bitmap.createBitmap(outW, top + plotH + bottom, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(0xFF101014.toInt())
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    // waterfall scaled to the plot area (nearest-neighbour keeps cells crisp)
    val srcRect = android.graphics.Rect(0, 0, wf.width, wf.height)
    val dstRect = android.graphics.Rect(0, top, outW, top + plotH)
    val wfPaint = android.graphics.Paint().apply { isFilterBitmap = false }
    c.drawBitmap(wf, srcRect, dstRect, wfPaint)
    // title
    paint.color = 0xFFFFFFFF.toInt(); paint.textSize = 24f
    val f0 = first.f0Hz / 1e6; val f1 = first.stopHz / 1e6
    c.drawText("CC1101 sweep  %.3f – %.3f MHz  rbw=%d Hz  n=%d".format(f0, f1, first.rbwHz, first.n), 10f, 28f, paint)
    // frequency axis
    paint.textSize = 20f; paint.color = 0xFFB0B0B0.toInt()
    val labels = 5
    for (i in 0 until labels) {
        val frac = i.toFloat() / (labels - 1)
        val hz = first.f0Hz + (frac * (first.stopHz - first.f0Hz)).toLong()
        val x = frac * outW
        val txt = "%.3f".format(hz / 1e6)
        val tw = paint.measureText(txt)
        val tx = (x - tw / 2).coerceIn(0f, outW - tw)
        c.drawText(txt, tx, (top + plotH + 24).toFloat(), paint)
        paint.color = 0x40FFFFFF
        c.drawLine(x, top.toFloat(), x, (top + plotH).toFloat(), paint)
        paint.color = 0xFFB0B0B0.toInt()
    }
    val bos = ByteArrayOutputStream()
    out.compress(Bitmap.CompressFormat.PNG, 100, bos)
    return bos.toByteArray()
}

@Composable
fun SweepTab(vm: AppViewModel) {
    val rows = vm.sweepRows
    // Unkeyed: these mirror the fields the user types; presets set them explicitly.
    // Keying on the VM value would reset the field mid-typing.
    var startTxt by remember { mutableStateOf("%.4f".format(vm.sweepStartHz / 1e6)) }
    var stopTxt by remember { mutableStateOf("%.4f".format(vm.sweepStopHz / 1e6)) }
    var stepTxt by remember { mutableStateOf((vm.sweepStepHz / 1000).toString()) }
    var rxbwMenu by remember { mutableStateOf(false) }

    val saveTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val png = waterfallPng(vm.sweepRows.toList())
            if (png != null) vm.saveSweepImage(uri, png)
        }
    }

    Column(Modifier.fillMaxSize()) {

        if (vm.monitorRunning || vm.aprsRunning || vm.pktRunning) {
            Text(
                stringResource(R.string.sweep_other_running),
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // ---- fixed header: start/stop + clear + screenshot ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!vm.sweepRunning) {
                Button(
                    onClick = { vm.startSweep() },
                    enabled = !vm.monitorRunning && !vm.aprsRunning && !vm.pktRunning,
                ) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sweep_start))
                }
            } else {
                Button(
                    onClick = { vm.stopSweep() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.sweep_stop))
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.clearSweep() }, enabled = rows.isNotEmpty()) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.sweep_clear))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { saveTree.launch(null) }, enabled = rows.isNotEmpty()) {
                Icon(Icons.Default.Image, null); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.sweep_screenshot))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        // ---- params (collapsible) ----
        CollapsibleSectionCard(
            title = stringResource(R.string.sweep_title),
            expanded = vm.sweepParamsExpanded,
            onToggle = { vm.sweepParamsExpanded = !vm.sweepParamsExpanded },
            collapsedSummary = {
                Text(
                    "%.3f–%.3f MHz".format(vm.sweepStartHz / 1e6, vm.sweepStopHz / 1e6),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Text(stringResource(R.string.sweep_desc), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                vm.sweepPresets.forEach { p ->
                    val sel = vm.sweepStartHz == p.startHz && vm.sweepStopHz == p.stopHz
                    Pill(p.label, selected = sel) {
                        if (!vm.sweepRunning) {
                            vm.applySweepPreset(p)
                            startTxt = "%.4f".format(p.startHz / 1e6)
                            stopTxt = "%.4f".format(p.stopHz / 1e6)
                            stepTxt = (p.stepHz / 1000).toString()
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                MhzField(stringResource(R.string.sweep_start_mhz), startTxt, !vm.sweepRunning, Modifier.weight(1f)) {
                    startTxt = it; it.toDoubleOrNull()?.let { v -> vm.updateSweepStart((v * 1e6).toLong()) }
                }
                Spacer(Modifier.width(8.dp))
                MhzField(stringResource(R.string.sweep_stop_mhz), stopTxt, !vm.sweepRunning, Modifier.weight(1f)) {
                    stopTxt = it; it.toDoubleOrNull()?.let { v -> vm.updateSweepStop((v * 1e6).toLong()) }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MhzField(stringResource(R.string.sweep_step_khz), stepTxt, !vm.sweepRunning, Modifier.weight(1f)) {
                    stepTxt = it; it.toDoubleOrNull()?.let { v -> vm.updateSweepStep((v * 1000).toLong()) }
                }
                Spacer(Modifier.width(8.dp))
                // RX filter bandwidth dropdown
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { if (!vm.sweepRunning) rxbwMenu = true }, enabled = !vm.sweepRunning,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(
                            if (vm.sweepRxbwHz == 0L) stringResource(R.string.sweep_rxbw_auto)
                            else "%d kHz".format(vm.sweepRxbwHz / 1000),
                            fontSize = 12.sp, maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = rxbwMenu, onDismissRequest = { rxbwMenu = false }) {
                        vm.sweepRxbwOptions.forEach { bw ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (bw == 0L) stringResource(R.string.sweep_rxbw_auto) else "%d Hz".format(bw))
                                },
                                onClick = { vm.updateSweepRxbw(bw); rxbwMenu = false },
                            )
                        }
                    }
                }
            }

            // live validation / point count
            val err = vm.sweepRangeError()
            if (err != null) {
                Text(err, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            } else {
                Text(
                    stringResource(R.string.sweep_points, vm.sweepPointCount),
                    color = MaterialTheme.colorScheme.primary, fontSize = 11.sp,
                )
            }
        }

        // ---- waterfall ----
        val bmp = remember(rows.size, rows.lastOrNull()?.ts) {
            waterfallBitmap(rows.toList())?.asImageBitmap()
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            if (bmp == null) {
                Text(
                    stringResource(R.string.sweep_waiting),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    drawImage(
                        image = bmp,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        dstOffset = IntOffset(0, 0),
                        filterQuality = FilterQuality.None,
                    )
                }
            }
        }

        // frequency axis + dBm legend
        if (rows.isNotEmpty()) {
            val r = rows.last()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text("%.3f".format(r.f0Hz / 1e6), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("%.3f MHz".format((r.f0Hz + r.stopHz) / 2e6), fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text("%.3f".format(r.stopHz / 1e6), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DbmLegend(Modifier.fillMaxWidth().height(14.dp).padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun MhzField(
    label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, enabled = enabled,
        label = { Text(label, fontSize = 11.sp) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** Horizontal dBm colour scale (−138 … −10.5 dBm). */
@Composable
private fun DbmLegend(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val steps = 64
        val w = size.width / steps
        for (i in 0 until steps) {
            val dbm = SweepLine.DBM_MIN + (SweepLine.DBM_MAX - SweepLine.DBM_MIN) * i / (steps - 1)
            drawRectColor(dbmArgb(dbm), i * w, 0f, w + 1f, size.height)
        }
    }
}

private fun DrawScope.drawRectColor(argb: Int, x: Float, y: Float, w: Float, h: Float) {
    drawRect(color = Color(argb), topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(w, h))
}
