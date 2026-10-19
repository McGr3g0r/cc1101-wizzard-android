/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.R
import com.wizzard.cc1101.cli.MonitorParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AprsTab(vm: AppViewModel) {
    var manual by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<AppViewModel.AprsEntry?>(null) }
    val exportTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val e = pendingExport
        if (uri != null && e != null) vm.exportAprsPacket(e, uri)
        pendingExport = null
    }
    val tsFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Column(Modifier.fillMaxSize()) {

        // -------- fixed start/stop header (always visible) --------
        if (vm.monitorRunning) {
            Text(
                stringResource(R.string.aprs_main_running),
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!vm.aprsRunning) {
                Button(onClick = { vm.startAprs() }, enabled = !vm.monitorRunning) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.aprs_start))
                }
            } else {
                Button(
                    onClick = { vm.stopAprs() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.aprs_stop))
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.aprs_count, vm.aprsPackets.size),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        // -------- everything else scrolls together (params card + packet list) --------
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {

            item {
                CollapsibleSectionCard(
                    title = stringResource(R.string.aprs_title),
                    expanded = vm.aprsParamsExpanded,
                    onToggle = { vm.aprsParamsExpanded = !vm.aprsParamsExpanded },
                    collapsedSummary = {
                        Text(
                            "%.3f MHz · %s".format(vm.aprsFreqHz / 1_000_000.0, if (vm.aprsAfsk) "AFSK" else "FSK"),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                ) {
                    Text(stringResource(R.string.aprs_desc), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.aprs_frequency), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ScrollRow {
                        vm.aprsPresets.forEach { (label, hz) ->
                            Pill(label, selected = vm.aprsFreqHz == hz) {
                                if (!vm.aprsRunning) { vm.updateAprsFreq(hz); manual = "" }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            singleLine = true,
                            enabled = !vm.aprsRunning,
                            label = { Text(stringResource(R.string.aprs_manual_mhz), fontSize = 11.sp) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = !vm.aprsRunning,
                            onClick = { manual.trim().toDoubleOrNull()?.let { vm.updateAprsFreq((it * 1_000_000).toLong()) } },
                        ) { Text(stringResource(R.string.aprs_set)) }
                    }
                    Text(
                        stringResource(R.string.aprs_current_freq, vm.aprsFreqHz / 1_000_000.0),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.aprs_mode), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ScrollRow {
                        Pill(stringResource(R.string.aprs_afsk), selected = vm.aprsAfsk) {
                            if (!vm.aprsRunning) vm.updateAprsAfsk(true)
                        }
                        Pill(stringResource(R.string.aprs_fsk), selected = !vm.aprsAfsk) {
                            if (!vm.aprsRunning) vm.updateAprsAfsk(false)
                        }
                    }

                    if (!vm.aprsRunning) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.aprs_radio_tuning), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MsStepper(stringResource(R.string.aprs_drate), null, vm.aprsDrate,
                            { vm.updateAprsDrate(it) }, step = 600, max = 500000, unit = "bps")
                        MsStepper(stringResource(R.string.aprs_rxbw), null, vm.aprsRxbw,
                            { vm.updateAprsRxbw(it) }, step = 1000, max = 812000, unit = "Hz")
                        MsStepper(stringResource(R.string.aprs_dev), null, vm.aprsDev,
                            { vm.updateAprsDev(it) }, step = 500, max = 380000, unit = "Hz")
                    }
                }
            }

            if (vm.aprsPackets.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.aprs_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            } else {
                items(vm.aprsPackets.asReversed()) { entry ->
                    AprsPacketRow(entry, tsFmt) { pendingExport = entry; exportTree.launch(null) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun AprsPacketRow(
    entry: AppViewModel.AprsEntry,
    tsFmt: SimpleDateFormat,
    onExport: () -> Unit,
) {
    val p = entry.packet
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "[${tsFmt.format(Date(entry.timeMs))}]",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onExport, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Save, stringResource(R.string.aprs_export), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text(p.tnc2(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        Text(
            MonitorParser.bytesToHex(p.frame),
            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Horizontally-scrollable row for the small preset / mode chip sets. */
@Composable
private fun ScrollRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { content() }
}
