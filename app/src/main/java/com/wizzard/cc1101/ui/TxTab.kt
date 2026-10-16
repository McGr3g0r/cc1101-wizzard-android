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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
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
            Text("Profil", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                if (vm.profiles.isEmpty()) {
                    Text("— brak profili (pobierz w Konfiguracji)", fontSize = 12.sp,
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
            Text("interfejs: ${vm.selectedIface}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- compose & transmit a protocol from scratch ----
        SectionCard("Nadaj protokół") {
            ProtocolPicker(vm)
            val cid = vm.composeId
            if (cid != null) {
                val tpl = vm.composeTemplates().firstOrNull { it.id == cid }
                Spacer(Modifier.height(6.dp))
                tpl?.defaults()?.forEach { f ->
                    val value = vm.composeValues[f.key] ?: f.value
                    OutlinedTextField(
                        value = value,
                        onValueChange = { vm.updateComposeField(f.key, it) },
                        label = { Text(f.label) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    )
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
                    ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text("Nadaj ${if (vm.txRepeat > 1) "×${vm.txRepeat}" else ""}") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { vm.openComposeInProtocol() }) {
                        Icon(Icons.Default.Science, null); Spacer(Modifier.width(6.dp)); Text("Edytuj w Protocol")
                    }
                }
            } else {
                Text("Wybierz protokół, aby pojawiły się pola do wypełnienia.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- signal source: disk browser (File + SAF) + single-file SAF + saved packets ----
        SectionCard("Źródło sygnału") {
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
                    Icon(Icons.Default.ArrowUpward, null); Spacer(Modifier.width(4.dp)); Text("W górę")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { openTree.launch(null) }) {
                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(4.dp)); Text("Wybierz folder…")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { openDoc.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(4.dp)); Text("Otwórz .sub…")
                }
            }
            Text(
                "Na Androidzie 11+ pliki .sub w katalogach współdzielonych (np. Pobrane) " +
                    "są widoczne dopiero po „Wybierz folder…”.",
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
                    Text("Pusto lub brak dostępu.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                } else {
                    vm.browseItems.forEach { item ->
                        BrowseRow(item) { vm.loadBrowseItem(item) }
                    }
                }
            }

            if (vm.savedPackets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("…lub z zapisanych pakietów (data/):", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                vm.savedPackets.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        TextButton(onClick = { vm.loadTxFromSaved(s) }) { Text("Załaduj") }
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
            SectionCard("Podgląd") {
                Text("Załaduj sygnał, aby zobaczyć podgląd i nadać.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            SectionCard("Podgląd: ${sig.label}") {
                if (sig.pulses != null) {
                    Text("OOK • ${sig.pulses.size} impulsów", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    ZoomableWaveform(sig.pulses)
                } else if (sig.hex != null) {
                    Text("Pakiet (FSK) • ${sig.hex.length / 2} B", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Text(sig.hex, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(10.dp))
                if (sig.pulses != null) {
                    OutlinedButton(onClick = { vm.interpretTxSignal() }) {
                        Icon(Icons.Default.Science, null); Spacer(Modifier.width(6.dp)); Text("Interpretuj")
                    }
                }

                Spacer(Modifier.height(12.dp))
                RepeatTicker(vm.txRepeat, onChange = { vm.updateTxRepeat(it) })
                GapStepper(vm.txGapMs, onChange = { vm.updateTxGapMs(it) })

                Spacer(Modifier.height(12.dp))
                var applyRadio by remember { mutableStateOf(true) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyRadio, onCheckedChange = { applyRadio = it })
                    Text("Ustaw częstotliwość (${"%.3f".format(vm.txFreqHz / 1_000_000.0)} MHz) przed wysłaniem",
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
                    Text("Wyślij ${if (vm.txRepeat > 1) "×${vm.txRepeat}" else ""}")
                }
                if (!connected) {
                    Text("Połącz i zweryfikuj urządzenie, aby nadawać.",
                        fontSize = 11.sp, color = ColorError, modifier = Modifier.padding(top = 4.dp))
                } else if (locked) {
                    Text("Monitor aktywny — zatrzymaj go, aby nadawać.",
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
            Text(current ?: "Wybierz protokół…")
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
    Text("Częstotliwość", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text("Powtórzenia (1–100)", Modifier.weight(1f), fontSize = 13.sp)
        OutlinedIconButton(onClick = { onChange(value - 1) }, enabled = value > 1) {
            Icon(Icons.Default.Remove, "mniej")
        }
        Text(
            value.toString(),
            Modifier.widthIn(min = 40.dp).padding(horizontal = 8.dp),
            fontFamily = FontFamily.Monospace, fontSize = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedIconButton(onClick = { onChange(value + 1) }, enabled = value < 100) {
            Icon(Icons.Default.Add, "więcej")
        }
    }
}

/** Inter-frame gap stepper (±50 ms). Default value comes from Konfiguracja. */
@Composable
private fun GapStepper(valueMs: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Odstęp między pakietami", fontSize = 13.sp)
            Text("domyślnie z Konfiguracji", fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedIconButton(onClick = { onChange(valueMs - 50) }, enabled = valueMs > 0) {
            Icon(Icons.Default.Remove, "mniej")
        }
        Text(
            "$valueMs ms",
            Modifier.widthIn(min = 64.dp).padding(horizontal = 8.dp),
            fontFamily = FontFamily.Monospace, fontSize = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        OutlinedIconButton(onClick = { onChange(valueMs + 50) }, enabled = valueMs < 10000) {
            Icon(Icons.Default.Add, "więcej")
        }
    }
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
            Text("Zoom ${"%.1f".format(zoom)}× — przesuwaj palcem w poziomie", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            OutlinedIconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) }) {
                Icon(Icons.Default.ZoomOut, "pomniejsz")
            }
            Spacer(Modifier.width(6.dp))
            OutlinedIconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(40f) }) {
                Icon(Icons.Default.ZoomIn, "powiększ")
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
