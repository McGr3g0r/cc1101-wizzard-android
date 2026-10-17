@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.wizzard.cc1101.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.R
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
                    stringResource(R.string.ip_empty_hint),
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
    SectionCard(stringResource(R.string.ip_compose_title) + " — ${tpl?.name ?: composeId}") {
        tpl?.defaults()?.forEach { f ->
            val value = vm.composeValues[f.key] ?: f.value
            ProtoFieldRow(f, value, editable = true, onChange = { vm.updateComposeField(f.key, it) })
        }
        Text(stringResource(R.string.ip_tx_prefix) + " ×${vm.txRepeat} " +
                stringResource(R.string.ip_tx_every) + " ${vm.txGapMs} ms " +
                stringResource(R.string.ip_tx_hint_suffix),
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
            ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ip_transmit_on) + " ${vm.selectedIface}") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.clearCompose() }) { Text(stringResource(R.string.btn_clear_fields)) }
        }
        if (!connected) {
            Text(stringResource(R.string.ip_connect_to_tx), fontSize = 11.sp, color = ColorError,
                modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun PacketSummary(pkt: CapturedPacket) {
    SectionCard(stringResource(R.string.ip_captured_packet)) {
        val pulsesWord = stringResource(R.string.tx_pulses_word)
        val desc = when (pkt) {
            is CapturedPacket.Ook -> "OOK • ${pkt.us.size} $pulsesWord • rssi=${pkt.rssi} lqi=${pkt.lqi}"
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
    SectionCard(stringResource(R.string.ip_matched_protocols)) {
        if (vm.results.isEmpty()) {
            Text(stringResource(R.string.ip_no_matches), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            AssistChip(onClick = {}, label = { Text(stringResource(R.string.ip_best_match)) },
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

    SectionCard(stringResource(R.string.ip_fields_title) + " — ${r.protocol}") {
        r.fields.forEach { f ->
            val value = vm.editedValues[f.key] ?: f.value
            ProtoFieldRow(f, value, editable = f.editable, onChange = { vm.updateField(f.key, it) })
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.transmitEdited() },
                enabled = r.canTransmit,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = androidx.compose.ui.graphics.Color.Black),
            ) { Icon(Icons.Default.Send, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ip_transmit)) }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ip_on_iface) + " ${vm.selectedIface}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Faithful replay of the exact captured waveform (fixed-code protocols).
        if (vm.selectedPacket is CapturedPacket.Ook) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { vm.transmitRawCapture() }) {
                Icon(Icons.Default.GraphicEq, null); Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.ip_transmit_raw))
            }
        }
        Text(
            stringResource(R.string.ip_tx_mode_hint),
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = saveName,
            onValueChange = { saveName = it },
            label = { Text(stringResource(R.string.ip_save_filename)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { vm.savePacket(saveName) }) {
            Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.ip_save_to_data))
        }
    }
}

@Composable
private fun SavedPackets(vm: AppViewModel) {
    // Folder picker: export the queued packet (.json + .txt) into the chosen tree.
    val exportTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.exportTo(uri)
    }
    SectionCard(stringResource(R.string.ip_saved_packets)) {
        if (vm.savedPackets.isEmpty()) {
            Text(stringResource(R.string.ip_no_saved), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Text(stringResource(R.string.ip_export_hint), fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        vm.savedPackets.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    Text(s.protocol, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.transmitSaved(s) }) { Icon(Icons.Default.Send, stringResource(R.string.cd_transmit), tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = { vm.pendingExport = s; exportTree.launch(null) }) { Icon(Icons.Default.FileUpload, stringResource(R.string.cd_export), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = { vm.deleteSaved(s) }) { Icon(Icons.Default.Delete, stringResource(R.string.cd_delete), tint = ColorError) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}
