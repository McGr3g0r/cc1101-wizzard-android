/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import com.wizzard.cc1101.cli.MonitorParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PktTab(vm: AppViewModel) {
    var manual by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<AppViewModel.PktEntry?>(null) }
    val tsFmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    detail?.let { PktDetailDialog(it, tsFmt) { detail = null } }

    Column(Modifier.fillMaxSize()) {

        // -------- fixed header: start/stop + count + clear --------
        if (vm.monitorRunning || vm.aprsRunning) {
            Text(
                stringResource(R.string.pkt_other_running),
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!vm.pktRunning) {
                Button(onClick = { vm.startPkt() }, enabled = !vm.monitorRunning && !vm.aprsRunning) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.pkt_start))
                }
            } else {
                Button(
                    onClick = { vm.stopPkt() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.pkt_stop))
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.clearPkt() }, enabled = vm.pktPackets.isNotEmpty()) {
                Icon(Icons.Default.Delete, null); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.pkt_clear))
            }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.pkt_count, vm.pktPackets.size),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        // -------- scrolling body: params + TX + packet list --------
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {

            item {
                CollapsibleSectionCard(
                    title = stringResource(R.string.pkt_title),
                    expanded = vm.pktParamsExpanded,
                    onToggle = { vm.pktParamsExpanded = !vm.pktParamsExpanded },
                    collapsedSummary = {
                        Text(
                            "%.3f MHz · %s · a%d".format(vm.pktFreqHz / 1_000_000.0, vm.pktMod, vm.pktAddr),
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                ) {
                    Text(stringResource(R.string.pkt_desc), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pkt_frequency), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ScrollRow {
                        vm.pktPresets.forEach { (label, hz) ->
                            Pill(label, selected = vm.pktFreqHz == hz) {
                                if (!vm.pktRunning) { vm.updatePktFreq(hz); manual = "" }
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manual,
                            onValueChange = { manual = it },
                            singleLine = true,
                            enabled = !vm.pktRunning,
                            label = { Text(stringResource(R.string.pkt_manual_mhz), fontSize = 11.sp) },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = !vm.pktRunning,
                            onClick = { manual.trim().toDoubleOrNull()?.let { vm.updatePktFreq((it * 1_000_000).toLong()) } },
                        ) { Text(stringResource(R.string.pkt_set)) }
                    }
                    Text(
                        stringResource(R.string.pkt_current_freq, vm.pktFreqHz / 1_000_000.0),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.pkt_modulation), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ScrollRow {
                        vm.pktMods.forEach { m ->
                            Pill(m.uppercase(), selected = vm.pktMod == m) {
                                if (!vm.pktRunning) vm.updatePktMod(m)
                            }
                        }
                    }

                    if (!vm.pktRunning) {
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.pkt_radio_tuning), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MsStepper(stringResource(R.string.pkt_drate), null, vm.pktDrate,
                            { vm.updatePktDrate(it) }, step = 600, max = 500000, unit = "bps")
                        MsStepper(stringResource(R.string.pkt_rxbw), null, vm.pktRxbw,
                            { vm.updatePktRxbw(it) }, step = 1000, max = 812000, unit = "Hz")
                        MsStepper(stringResource(R.string.pkt_dev), null, vm.pktDev,
                            { vm.updatePktDev(it) }, step = 500, max = 380000, unit = "Hz")

                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.pkt_addressing), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MsStepper(stringResource(R.string.pkt_addr), stringResource(R.string.pkt_addr_hint),
                            vm.pktAddr, { vm.updatePktAddr(it) }, step = 1, max = 255, unit = "")

                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.pkt_addrchk), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ScrollRow {
                            val labels = listOf(
                                R.string.pkt_chk_off, R.string.pkt_chk_addr,
                                R.string.pkt_chk_bcast, R.string.pkt_chk_bcast2,
                            )
                            labels.forEachIndexed { idx, res ->
                                Pill(stringResource(res), selected = vm.pktAddrChk == idx) {
                                    if (!vm.pktRunning) vm.updatePktAddrChk(idx)
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.pkt_crc), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                            Switch(checked = vm.pktCrc, onCheckedChange = { vm.updatePktCrc(it) })
                        }
                    }
                }
            }

            // ---- transmit ----
            item {
                SectionCard(stringResource(R.string.pkt_tx_title)) {
                    Text(stringResource(R.string.pkt_tx_desc), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = vm.pktTxDest,
                        onValueChange = { vm.pktTxDest = it.take(3) },
                        singleLine = true,
                        enabled = !vm.pktRunning && !vm.monitorRunning && !vm.aprsRunning,
                        label = { Text(stringResource(R.string.pkt_tx_dest), fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = vm.pktTxHex,
                        onValueChange = { vm.pktTxHex = it },
                        enabled = !vm.pktRunning && !vm.monitorRunning && !vm.aprsRunning,
                        label = { Text(stringResource(R.string.pkt_tx_payload), fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { vm.sendPkt() },
                        enabled = !vm.pktRunning && !vm.monitorRunning && !vm.aprsRunning && vm.pktTxHex.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Send, null); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.pkt_tx_send))
                    }
                }
            }

            if (vm.pktPackets.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.pkt_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            } else {
                items(vm.pktPackets.asReversed()) { entry ->
                    PktRow(entry, tsFmt) { detail = entry }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun PktRow(
    entry: AppViewModel.PktEntry,
    tsFmt: SimpleDateFormat,
    onClick: () -> Unit,
) {
    val isTx = entry.dir == AppViewModel.PktDir.TX
    val accent = if (isTx) com.wizzard.cc1101.ui.theme.ColorTx else MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isTx) "TX" else "RX",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = accent,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "[${tsFmt.format(Date(entry.timeMs))}]",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            val meta = buildString {
                append(entry.mod)
                entry.addr?.let { append("  a$it") }
                entry.rssi?.let { append("  ${it}dBm") }
                entry.crc?.let { append(if (it) "  crc✓" else "  crc✗") }
            }
            Text(meta, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            MonitorParser.bytesToHex(entry.bytes).ifEmpty { "(empty)" },
            fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = accent, maxLines = 2,
        )
    }
}

@Composable
private fun PktDetailDialog(
    entry: AppViewModel.PktEntry,
    tsFmt: SimpleDateFormat,
    onDismiss: () -> Unit,
) {
    val hex = MonitorParser.bytesToHex(entry.bytes)
    val ascii = entry.bytes.joinToString("") { b ->
        val c = b.toInt() and 0xFF
        if (c in 0x20..0x7E) c.toChar().toString() else "."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pkt_close)) } },
        title = { Text(stringResource(if (entry.dir == AppViewModel.PktDir.TX) R.string.pkt_detail_tx else R.string.pkt_detail_rx)) },
        text = {
            Column {
                DetailLine(stringResource(R.string.pkt_d_time), tsFmt.format(Date(entry.timeMs)))
                DetailLine(stringResource(R.string.pkt_d_mod), entry.mod)
                entry.addr?.let { DetailLine(stringResource(R.string.pkt_d_addr), it.toString()) }
                entry.rssi?.let { DetailLine(stringResource(R.string.pkt_d_rssi), "$it dBm") }
                entry.lqi?.let { DetailLine(stringResource(R.string.pkt_d_lqi), it.toString()) }
                entry.crc?.let {
                    val chk = if (entry.crcEn == false) " (${stringResource(R.string.pkt_d_crc_off)})" else ""
                    DetailLine(stringResource(R.string.pkt_d_crc), (if (it) "OK" else "FAIL") + chk)
                }
                DetailLine(stringResource(R.string.pkt_d_len), "${entry.bytes.size} B")
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.pkt_d_hex), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(hex.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.pkt_d_ascii), fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ascii.ifEmpty { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.width(96.dp), fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/** Horizontally-scrollable row for the small preset / mode chip sets. */
@Composable
private fun ScrollRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { content() }
}
