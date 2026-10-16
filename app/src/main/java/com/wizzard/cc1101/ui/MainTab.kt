package com.wizzard.cc1101.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.cli.ConnState
import com.wizzard.cc1101.cli.LogKind
import com.wizzard.cc1101.cli.LogLine
import com.wizzard.cc1101.ui.theme.*

@Composable
fun MainTab(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {

        // ---- connection controls ----
        SectionCard("Połączenie") {
            ConnectionIndicator(vm.conn, vm.connectedName)
            vm.deviceInfo?.let { di ->
                Text(
                    "${di.device} ${di.variant} • fw ${di.fw} (build ${di.build}) • ${di.platform} • cli ${di.cli} • radia: ${di.radios}",
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
                ) { Icon(Icons.Default.Usb, null); Spacer(Modifier.width(6.dp)); Text("Połącz") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { vm.disconnect() },
                    enabled = connected,
                ) { Icon(Icons.Default.LinkOff, null); Spacer(Modifier.width(6.dp)); Text("Rozłącz") }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    onClick = { vm.verify() },
                    enabled = vm.conn != ConnState.DISCONNECTED && !vm.monitorRunning,
                ) {
                    Icon(Icons.Default.Verified, null); Spacer(Modifier.width(6.dp))
                    Text("Sprawdź urządzenie")
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
                    "profil: " + (vm.selectedProfile ?: vm.profiles.firstOrNull() ?: "— (wybierz w cc1101)"),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- log ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Logi (monitor + CLI)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.clearLogs() }) { Text("Wyczyść") }
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
    // auto-scroll to the newest line
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            items(lines, key = { it.id }) { line ->
                val color = when (line.kind) {
                    LogKind.TX -> ColorTx
                    LogKind.RX -> ColorRxCli
                    LogKind.MONITOR -> ColorMonitor
                    LogKind.ERROR -> ColorError
                    LogKind.ECHO -> ColorEcho
                    LogKind.INFO -> ColorInfo
                }
                val clickable = line.kind == LogKind.MONITOR && line.packet != null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (clickable) Modifier.clickable { onMonitorClick(line.packet!!) }
                            else Modifier
                        )
                        .padding(vertical = 1.dp),
                ) {
                    Text(
                        text = (if (clickable) "⇲ " else "") + line.text,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
