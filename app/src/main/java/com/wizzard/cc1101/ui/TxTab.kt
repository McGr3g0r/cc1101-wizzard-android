@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wizzard.cc1101.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.R
import com.wizzard.cc1101.cli.ConnState
import com.wizzard.cc1101.ui.theme.*
import java.io.File

@Composable
fun TxTab(vm: AppViewModel) {
    val connected = vm.conn == ConnState.VERIFIED || vm.conn == ConnState.CONNECTED

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.loadTxFromUri(uri)
    }
    val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.openBrowseTree(uri)
    }

    LaunchedEffect(Unit) { vm.refreshSaved(); vm.initBrowse() }

    val locked = vm.monitorRunning

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (locked) MonitorLockBanner()

        // ---- radio setup: profile + frequency ----
        SectionCard("Radio") {
            Text(stringResource(R.string.tx_profile_label), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (vm.profiles.isEmpty()) {
                    Text(stringResource(R.string.tx_no_profiles), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                vm.profiles.forEach { name ->
                    Pill(name, selected = vm.selectedProfile == name) {
                        if (!locked) { vm.openProfile(name); vm.applyProfile(name) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FrequencySelector(vm)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.tx_interface) + ": ${vm.selectedIface}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- compose & transmit a protocol from scratch ----
        SectionCard(stringResource(R.string.tx_transmit_protocol)) {
            ProtocolPicker(vm)
            val cid = vm.composeId
            if (cid != null) {
                val tpl = vm.composeTemplates().firstOrNull { it.id == cid }
                Spacer(Modifier.height(6.dp))
                tpl?.defaults()?.forEach { f ->
                    val value = vm.composeValues[f.key] ?: f.value
                    ProtoFieldRow(f, value, editable = true, onChange = { vm.updateComposeField(f.key, it) })
                }
                Spacer(Modifier.height(8.dp))
                RepeatTicker(vm.txRepeat, onChange = { vm.updateTxRepeat(it) })
                GapStepper(vm.txGapMs, onChange = { vm.updateTxGapMs(it) })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { vm.transmitCompose() },
                        enabled = connected && !locked,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.Black,
                        ),
                    ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tx_transmit_btn) + " ${if (vm.txRepeat > 1) "×${vm.txRepeat}" else ""}") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.openComposeInProtocol() }) {
                        Icon(Icons.Default.Science, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.tx_edit_in_protocol))
                    }
                }
            } else {
                Text(stringResource(R.string.tx_pick_protocol_hint),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- signal source: disk browser (File + SAF) + single-file SAF + saved packets ----
        SectionCard(stringResource(R.string.tx_signal_source)) {
            // quick File-mode roots (app data/, app-ext, device storage)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                vm.browseRoots().forEach { root ->
                    Pill(rootLabel(root), selected = false) { vm.openBrowseFileDir(root) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(vm.browsePath, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.browseUp() }) {
                    Icon(Icons.Default.ArrowUpward, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tx_up))
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { openTree.launch(null) }) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tx_choose_folder))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { openDoc.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.tx_open_sub))
                }
            }
            Text(
                stringResource(R.string.tx_saf_hint),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            vm.browseError?.let {
                Text(it, color = ColorError, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(6.dp))
            // directory listing (bounded height so the tab stays usable)
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                if (vm.browseItems.isEmpty()) {
                    Text(stringResource(R.string.tx_empty_or_no_access), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                } else {
                    vm.browseItems.forEach { item ->
                        BrowseRow(item) { vm.loadBrowseItem(item) }
                    }
                }
            }

            if (vm.savedPackets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.tx_or_from_saved), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.savedPackets.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        TextButton(onClick = { vm.loadTxFromSaved(s) }) { Text(stringResource(R.string.tx_load)) }
                    }
                }
            }
            vm.txError?.let {
                Text(it, color = ColorError, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }

        // ---- visualization + send ----
        val sig = vm.txSignal
        if (sig == null) {
            SectionCard(stringResource(R.string.tx_preview)) {
                Text(stringResource(R.string.tx_load_signal_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            SectionCard(stringResource(R.string.tx_preview) + ": ${sig.label}") {
                if (sig.pulses != null) {
                    Text("OOK • ${sig.pulses.size} " + stringResource(R.string.tx_pulses_word), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    ZoomableWaveform(sig.pulses)
                } else if (sig.hex != null) {
                    Text(stringResource(R.string.tx_fsk_packet) + " • ${sig.hex.length / 2} B", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text(sig.hex, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(10.dp))
                if (sig.pulses != null) {
                    OutlinedButton(onClick = { vm.interpretTxSignal() }) {
                        Icon(Icons.Default.Science, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_interpret))
                    }
                }

                Spacer(Modifier.height(12.dp))
                RepeatTicker(vm.txRepeat, onChange = { vm.updateTxRepeat(it) })
                GapStepper(vm.txGapMs, onChange = { vm.updateTxGapMs(it) })

                Spacer(Modifier.height(12.dp))
                var applyRadio by remember { mutableStateOf(true) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyRadio, onCheckedChange = { applyRadio = it })
                    Text(stringResource(R.string.tx_set_freq_1) +
                        " (${"%.3f".format(vm.txFreqHz / 1_000_000.0)} MHz) " +
                        stringResource(R.string.tx_set_freq_2),
                        fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { vm.sendTx(applyRadio) },
                    enabled = connected && !locked,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tx_send_btn) + " ${if (vm.txRepeat > 1) "×${vm.txRepeat}" else ""}")
                }
                if (!connected) {
                    Text(stringResource(R.string.tx_connect_to_send),
                        fontSize = 11.sp, color = ColorError, modifier = Modifier.padding(top = 4.dp))
                } else if (locked) {
                    Text(stringResource(R.string.tx_monitor_active_hint),
                        fontSize = 11.sp, color = ColorError, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun BrowseRow(item: com.wizzard.cc1101.BrowseItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.isDir) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            null,
            tint = if (item.isDir) MaterialTheme.colorScheme.primary
                   else if (item.isSub) ColorMonitor
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(item.name + if (item.isDir) "/" else "", fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.weight(1f))
        if (item.isSub) Text("Flipper", fontSize = 10.sp, color = ColorMonitor)
    }
}

@Composable
private fun ProtocolPicker(vm: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val current = vm.composeId?.let { id -> vm.composeTemplates().firstOrNull { it.id == id }?.name }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Memory, null); Spacer(Modifier.width(6.dp))
            Text(current ?: stringResource(R.string.tx_pick_protocol_dropdown))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            vm.composeTemplates().forEach { tpl ->
                DropdownMenuItem(
                    text = { Text(tpl.name) },
                    onClick = { vm.selectComposeProtocol(tpl.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun FrequencySelector(vm: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val label = vm.txFrequencies.firstOrNull { it.second == vm.txFreqHz }?.first
        ?: "%.3f MHz".format(vm.txFreqHz / 1_000_000.0)
    Text(stringResource(R.string.tx_frequency), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Tune, null); Spacer(Modifier.width(6.dp)); Text(label)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            vm.txFrequencies.forEach { (name, hz) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { vm.setTxFreq(hz); expanded = false })
            }
        }
    }
}

@Composable
private fun RepeatTicker(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.tx_repeat_label), Modifier.weight(1f), fontSize = 13.sp)
        OutlinedIconButton(onClick = { onChange(value - 1) }, enabled = value > 1) {
            Icon(Icons.Default.Remove, stringResource(R.string.cd_decrease))
        }
        Text(
            value.toString(),
            Modifier.widthIn(min = 40.dp).padding(horizontal = 8.dp),
            fontFamily = FontFamily.Monospace, fontSize = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedIconButton(onClick = { onChange(value + 1) }, enabled = value < 100) {
            Icon(Icons.Default.Add, stringResource(R.string.cd_increase))
        }
    }
}

/** Inter-frame gap: direct ms entry (1 ms precision) + 1 ms nudge buttons. */
@Composable
private fun GapStepper(valueMs: Int, onChange: (Int) -> Unit) {
    MsStepper(
        label = stringResource(R.string.tx_gap_label),
        sub = stringResource(R.string.tx_gap_sub),
        valueMs = valueMs,
        onChange = onChange,
        step = 1,
    )
}

/**
 * OOK pulse-train as a square wave. Zoom with the +/- buttons (1×–40×); when
 * zoomed in, drag horizontally to pan along the time axis (the waveform is wider
 * than the viewport and lives in a horizontal scroll container).
 */
@Composable
private fun ZoomableWaveform(us: IntArray) {
    var zoom by remember(us) { mutableStateOf(1f) }
    val scroll = rememberScrollState()
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.surfaceVariant

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Zoom ${"%.1f".format(zoom)}× — " + stringResource(R.string.tx_zoom_drag_hint), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            OutlinedIconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) }) {
                Icon(Icons.Default.ZoomOut, stringResource(R.string.cd_zoom_out))
            }
            Spacer(Modifier.width(6.dp))
            OutlinedIconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(40f) }) {
                Icon(Icons.Default.ZoomIn, stringResource(R.string.cd_zoom_in))
            }
        }
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val base = maxWidth
            // horizontalScroll handles panning; zoom is driven by the buttons so
            // the drag gesture is never consumed by a transform detector.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .horizontalScroll(scroll)
            ) {
                Canvas(Modifier.width(base * zoom).height(110.dp)) {
                    if (us.isEmpty()) return@Canvas
                    val total = us.sumOf { it.toLong() }.coerceAtLeast(1L)
                    val w = size.width
                    val top = size.height * 0.15f
                    val bottom = size.height * 0.85f
                    drawLine(grid, Offset(0f, bottom), Offset(w, bottom), strokeWidth = 1f)
                    var x = 0f
                    var high = true // firmware plays starting HIGH
                    var prevY = top
                    for (d in us) {
                        val dx = (d.toDouble() / total * w).toFloat()
                        val y = if (high) top else bottom
                        drawLine(line, Offset(x, prevY), Offset(x, y), strokeWidth = 2f)
                        drawLine(line, Offset(x, y), Offset(x + dx, y), strokeWidth = 2f)
                        x += dx
                        prevY = y
                        high = !high
                    }
                }
            }
        }
    }
}

private fun rootLabel(f: File): String = when {
    f.absolutePath.endsWith("/files/data") -> "data/"
    f.absolutePath.contains("/Android/data/") -> "app-ext"
    else -> f.name.ifEmpty { "/" }
}
