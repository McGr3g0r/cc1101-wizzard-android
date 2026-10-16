@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wizzard.cc1101.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.cli.CapturedPacket
import com.wizzard.cc1101.cli.ConnState
import com.wizzard.cc1101.protocol.AnalysisResult
import com.wizzard.cc1101.protocol.FieldType
import com.wizzard.cc1101.ui.theme.*

@Composable
fun InterpretTab(vm: AppViewModel) {
    val pkt = vm.selectedPacket
    val composeId = vm.composeId
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (vm.monitorRunning) MonitorLockBanner()

        when {
            composeId != null -> ComposeEditor(vm, composeId)
            pkt != null -> {
                PacketSummary(pkt)
                ResultsSelector(vm)
                vm.selectedResult?.let { FieldsEditor(vm, it) }
            }
            else -> SectionCard("Protocol") {
                Text(
                    "Otwórz tę zakładkę klikając linię monitora (Monitor) albo wybierając " +
                        "protokół do nadania w zakładce TX.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SavedPackets(vm)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ComposeEditor(vm: AppViewModel, composeId: String) {
    val tpl = vm.composeTemplates().firstOrNull { it.id == composeId }
    val connected = vm.conn == ConnState.VERIFIED || vm.conn == ConnState.CONNECTED
    SectionCard("Nadawanie protokołu — ${tpl?.name ?: composeId}") {
        tpl?.defaults()?.forEach { f ->
            val value = vm.composeValues[f.key] ?: f.value
            OutlinedTextField(
                value = value,
                onValueChange = { vm.updateComposeField(f.key, it) },
                label = { Text(f.label) },
                singleLine = f.type != FieldType.TEXT,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (f.type == FieldType.DEC) KeyboardType.Number else KeyboardType.Ascii
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        Text("Nadanie: ×${vm.txRepeat} co ${vm.txGapMs} ms (ustaw powtórzenia/odstęp w zakładce TX / Konfiguracji)",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.transmitCompose() },
                enabled = connected && !vm.monitorRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = androidx.compose.ui.graphics.Color.Black,
                ),
            ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text("Nadaj na ${vm.selectedIface}") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.clearCompose() }) { Text("Wyczyść") }
        }
        if (!connected) {
            Text("Połącz urządzenie, aby nadawać.", fontSize = 11.sp, color = ColorError,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun PacketSummary(pkt: CapturedPacket) {
    SectionCard("Przechwycony pakiet") {
        val desc = when (pkt) {
            is CapturedPacket.Ook -> "OOK • ${pkt.us.size} impulsów • rssi=${pkt.rssi} lqi=${pkt.lqi}"
            is CapturedPacket.Fsk -> "${pkt.mod.uppercase()} • ${pkt.bytes.size} B • crc=${if (pkt.crc) 1 else 0} • rssi=${pkt.rssi}"
        }
        Text(desc, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(pkt.raw, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultsSelector(vm: AppViewModel) {
    SectionCard("Dopasowane protokoły") {
        if (vm.results.isEmpty()) {
            Text("Brak dopasowań.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        vm.results.forEachIndexed { idx, r ->
            val selected = r == vm.selectedResult
            ElevatedCard(
                onClick = { vm.selectResult(r) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (idx == 0) {
                            AssistChip(onClick = {}, label = { Text("najlepszy") },
                                modifier = Modifier.padding(end = 8.dp))
                        }
                        Text(r.protocol, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text("${(r.score * 100).toInt()}%", color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace)
                    }
                    Text(r.summary, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun FieldsEditor(vm: AppViewModel, r: AnalysisResult) {
    var saveName by remember(r) { mutableStateOf(r.analyzerId + "_" + System.currentTimeMillis() % 100000) }

    SectionCard("Pola (kody / liczniki) — ${r.protocol}") {
        r.fields.forEach { f ->
            val value = vm.editedValues[f.key] ?: f.value
            OutlinedTextField(
                value = value,
                onValueChange = { vm.updateField(f.key, it) },
                label = { Text(f.label) },
                readOnly = !f.editable,
                singleLine = f.type != FieldType.TEXT,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (f.type == FieldType.DEC) KeyboardType.Number else KeyboardType.Ascii
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.transmitEdited() },
                enabled = r.canTransmit,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = androidx.compose.ui.graphics.Color.Black),
            ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text("Nadaj") }
            Spacer(Modifier.width(8.dp))
            Text("na ${vm.selectedIface}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "OOK wysyłany jako pulses play; FSK jako 'send' w bieżącej modulacji — najpierw zastosuj pasujący profil w zakładce cc1101.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = saveName,
            onValueChange = { saveName = it },
            label = { Text("Nazwa pliku w data/") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { vm.savePacket(saveName) }) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Zapisz do data/")
        }
    }
}

@Composable
private fun SavedPackets(vm: AppViewModel) {
    SectionCard("Zapisane pakiety (data/)") {
        if (vm.savedPackets.isEmpty()) {
            Text("Brak zapisanych pakietów.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        vm.savedPackets.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(s.protocol, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.transmitSaved(s) }) { Icon(Icons.Default.Send, "nadaj", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { vm.deleteSaved(s) }) { Icon(Icons.Default.Delete, "usuń", tint = ColorError) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}
