/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.R
import com.wizzard.cc1101.cli.ConnState
import com.wizzard.cc1101.cli.LogKind
import com.wizzard.cc1101.cli.LogLine
import com.wizzard.cc1101.ui.theme.*

@Composable
fun MainTab(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {

        // ---- connection controls (collapsible) ----
        // Collapse state lives in the ViewModel so it survives tab switches.
        CollapsibleSectionCard(
            title = stringResource(R.string.sec_connection),
            expanded = vm.connExpanded,
            onToggle = { vm.connExpanded = !vm.connExpanded },
            collapsedSummary = { ConnectionIndicator(vm.conn, null) },
        ) {
            ConnectionIndicator(vm.conn, vm.connectedName)
            vm.deviceInfo?.let { di ->
                val radios = stringResource(R.string.radios_label)
                Text(
                    "${di.device} ${di.variant} • fw ${di.fw} (build ${di.build}) • ${di.platform} • cli ${di.cli} • $radios: ${di.radios}",
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val connected = vm.conn == ConnState.CONNECTED ||
                    vm.conn == ConnState.VERIFIED || vm.conn == ConnState.WRONG_DEVICE
                Button(
                    onClick = { vm.connectFirstAndVerify() },
                    enabled = !connected,
                ) { Icon(Icons.Default.Usb, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_connect)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { vm.disconnect() },
                    enabled = connected,
                ) { Icon(Icons.Default.LinkOff, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_disconnect)) }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    onClick = { vm.verify() },
                    enabled = vm.conn != ConnState.DISCONNECTED && !vm.monitorRunning,
                ) {
                    Icon(Icons.Default.Verified, null); Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_verify_device))
                }
            }
        }

        // ---- monitor controls ----
        SectionCard("Monitor") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val canMon = vm.conn == ConnState.VERIFIED || vm.conn == ConnState.CONNECTED
                if (!vm.monitorRunning) {
                    Button(
                        onClick = {
                            val p = vm.selectedProfile ?: vm.profiles.firstOrNull()
                            if (p != null) vm.startMonitor(p)
                        },
                        enabled = canMon && (vm.selectedProfile != null || vm.profiles.isNotEmpty()),
                    ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Start") }
                } else {
                    Button(
                        onClick = { vm.stopMonitor() },
                        colors = ButtonDefaults.buttonColors(containerColor = ColorError),
                    ) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(6.dp)); Text("Stop") }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.profile_label) + " " +
                        (vm.selectedProfile ?: vm.profiles.firstOrNull() ?: stringResource(R.string.profile_none_hint)),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // RSSI gate, settable right before Start (hidden while capturing, when
            // commands are locked out anyway). Shares state with Settings, so a
            // change here persists and shows there too. Negative dBm edited as a
            // magnitude shown as "−dBm".
            if (!vm.monitorRunning) {
                Spacer(Modifier.height(8.dp))
                MsStepper(
                    label = stringResource(R.string.cfg_capture_rssi),
                    sub = stringResource(R.string.mon_rssi_sub),
                    valueMs = -vm.captureRssiGate,
                    onChange = { vm.updateCaptureRssiGate(-it) },
                    step = 1, max = 120, unit = "−dBm",
                )
            }
        }

        // ---- log ----
        val saveLogTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) vm.saveLogsTo(uri)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.log_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { saveLogTree.launch(null) }, enabled = vm.logs.isNotEmpty()) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.log_save))
            }
            TextButton(onClick = { vm.clearLogs() }) { Text(stringResource(R.string.btn_clear)) }
        }
        LogView(
            lines = vm.logs,
            onMonitorClick = { vm.interpret(it) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun LogView(
    lines: List<LogLine>,
    onMonitorClick: (com.wizzard.cc1101.cli.CapturedPacket) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Work on an immutable snapshot of the log for this frame: the live list is
    // appended from the serial thread, so indexing it directly during layout can
    // race. toList() also subscribes us to changes, so new lines still recompose.
    val snapshot = lines.toList()

    // auto-scroll to the newest line
    LaunchedEffect(snapshot.size) {
        if (snapshot.isNotEmpty()) listState.scrollToItem(snapshot.size - 1)
    }

    // Group the log into "signal blocks": a new block starts at each monitor
    // capture; the lines that follow it (decode output, command traffic) are its
    // continuation and share the block's shade. Adjacent blocks alternate a
    // background tint so captures are easy to tell apart and tap.
    val blockOf = IntArray(snapshot.size)
    run {
        var block = 0
        for (i in snapshot.indices) {
            if (snapshot[i].kind == LogKind.MONITOR) block++
            blockOf[i] = block
        }
    }
    val stripeA = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val stripeB = Color.Transparent
    val divider = MaterialTheme.colorScheme.outline

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(snapshot, key = { _, l -> l.id }) { index, line ->
                val color = when (line.kind) {
                    LogKind.TX -> ColorTx
                    LogKind.RX -> ColorRxCli
                    LogKind.MONITOR -> ColorMonitor
                    LogKind.ERROR -> ColorError
                    LogKind.ECHO -> ColorEcho
                    LogKind.INFO -> ColorInfo
                }
                val clickable = line.kind == LogKind.MONITOR && line.packet != null
                // A new signal block begins at a monitor line whose block index
                // differs from the previous line's: draw a divider above it.
                val blockStart = index > 0 && blockOf[index] != blockOf[index - 1]
                if (blockStart) {
                    HorizontalDivider(thickness = 1.dp, color = divider)
                }
                val rowBg = if (blockOf[index] % 2 == 1) stripeA else stripeB
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .then(
                            if (clickable) Modifier.clickable { onMonitorClick(line.packet!!) }
                            else Modifier
                        )
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "[${com.wizzard.cc1101.cli.logTimestamp(line.timestampMs)}] " +
                            (if (clickable) "⇲ " else "") + line.text,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
