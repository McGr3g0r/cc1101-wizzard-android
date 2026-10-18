package com.wizzard.cc1101.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.R
import com.wizzard.cc1101.cli.ConnState
import com.wizzard.cc1101.ui.theme.*

@Composable
fun ConfigTab(vm: AppViewModel) {
    val connected = vm.conn == ConnState.VERIFIED || vm.conn == ConnState.CONNECTED

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.updateGpsEnabled(granted) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (vm.monitorRunning) MonitorLockBanner()

        // ---- settings ----
        SectionCard(stringResource(R.string.cfg_settings)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cfg_gps_title), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.cfg_gps_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vm.gpsEnabled,
                    onCheckedChange = { want ->
                        if (want && !vm.hasLocationPermission()) {
                            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else {
                            vm.updateGpsEnabled(want)
                        }
                    },
                )
            }
            if (vm.gpsEnabled && !vm.hasLocationPermission()) {
                Text(stringResource(R.string.cfg_gps_no_perm),
                    fontSize = 11.sp, color = ColorError, modifier = Modifier.padding(top = 4.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            // ---- auto-reconnect ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cfg_autoreconnect_title), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.cfg_autoreconnect_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(checked = vm.autoReconnect, onCheckedChange = { vm.updateAutoReconnect(it) })
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            // ---- colour template ----
            Text(stringResource(R.string.cfg_theme_title), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.cfg_theme_desc), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                THEME_TEMPLATES.forEach { t ->
                    Pill(t.name, selected = vm.themeId == t.id, onClick = { vm.setTheme(t.id) })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            // ---- TX repeat gap ----
            MsStepper(
                label = stringResource(R.string.cfg_gap_title),
                sub = stringResource(R.string.cfg_gap_desc),
                valueMs = vm.configGapMs,
                onChange = { vm.updateConfigGapMs(it) },
                step = 1,
            )
        }

        // ---- OOK monitor capture window ----
        SectionCard(stringResource(R.string.cfg_capture_title)) {
            Text(stringResource(R.string.cfg_capture_desc), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            MsStepper(
                label = stringResource(R.string.cfg_capture_min),
                sub = null,
                valueMs = vm.captureMinUs,
                onChange = { vm.updateCaptureMinUs(it) },
                step = 10, max = 2000, unit = "µs",
            )
            Spacer(Modifier.height(6.dp))
            MsStepper(
                label = stringResource(R.string.cfg_capture_gap),
                sub = null,
                valueMs = vm.captureGapUs,
                onChange = { vm.updateCaptureGapUs(it) },
                step = 100, max = 60000, unit = "µs",
            )
            Spacer(Modifier.height(6.dp))
            // RSSI gate is a negative dBm; edited here as a magnitude (shown as −dBm).
            MsStepper(
                label = stringResource(R.string.cfg_capture_rssi),
                sub = stringResource(R.string.cfg_capture_rssi_hint),
                valueMs = -vm.captureRssiGate,
                onChange = { vm.updateCaptureRssiGate(-it) },
                step = 1, max = 120, unit = "−dBm",
            )
        }

        // ---- capture coalescing ----
        SectionCard(stringResource(R.string.cfg_coalesce_title)) {
            Text(stringResource(R.string.cfg_coalesce_desc), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cfg_coalesce_enable),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = vm.coalesceEnabled, onCheckedChange = { vm.updateCoalesceEnabled(it) })
            }
            if (vm.coalesceEnabled) {
                Spacer(Modifier.height(6.dp))
                MsStepper(
                    label = stringResource(R.string.cfg_coalesce_window),
                    sub = null,
                    valueMs = vm.coalesceWindowMs,
                    onChange = { vm.updateCoalesceWindowMs(it) },
                    step = 50, max = 3000, unit = "ms",
                )
            }
        }

        // ---- OOK transmit method ----
        SectionCard(stringResource(R.string.cfg_ooktx_title)) {
            Text(stringResource(R.string.cfg_ooktx_desc), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (vm.ookTxFifo) R.string.cfg_ooktx_fifo else R.string.cfg_ooktx_gpio),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = vm.ookTxFifo, onCheckedChange = { vm.updateOokTxFifo(it) })
            }
            if (vm.ookTxFifo) {
                Spacer(Modifier.height(6.dp))
                MsStepper(
                    label = stringResource(R.string.cfg_ooktx_factor),
                    sub = stringResource(R.string.cfg_ooktx_factor_hint),
                    valueMs = vm.ookTxFifoFactor,
                    onChange = { vm.updateOokTxFifoFactor(it) },
                    step = 1, max = 32, unit = "×",
                )
            }
        }

        // ---- USB device selection ----
        SectionCard(stringResource(R.string.cfg_usb_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cfg_detected_adapters), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.refreshDrivers() }) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.cfg_scan))
                }
            }
            if (vm.drivers.isEmpty()) {
                Text(stringResource(R.string.cfg_no_devices),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                vm.drivers.forEachIndexed { i, d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.describe(d), Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Button(onClick = { vm.connect(d) }, enabled = !connected) { Text(stringResource(R.string.cfg_connect)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ConnectionIndicator(vm.conn, vm.connectedName)
            Spacer(Modifier.height(6.dp))
            Row {
                OutlinedButton(onClick = { vm.verify() }, enabled = connected && !vm.monitorRunning) { Text(stringResource(R.string.cfg_check)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.disconnect() }, enabled = connected) { Text(stringResource(R.string.cfg_disconnect)) }
            }
        }

        // ---- radio interface selection ----
        SectionCard(stringResource(R.string.cfg_radio_select)) {
            if (vm.ifaces.isEmpty()) {
                Text(stringResource(R.string.cfg_use_check_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("r0", "r1", "r2", "r3").forEach {
                        Pill(it, selected = vm.selectedIface == it, onClick = { vm.selectedIface = it })
                    }
                }
            } else {
                val absentSuffix = stringResource(R.string.cfg_iface_absent)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    vm.ifaces.forEach { ifc ->
                        Pill(
                            "${ifc.name}${if (ifc.present) "" else " $absentSuffix"}",
                            selected = vm.selectedIface == ifc.name,
                            onClick = { vm.selectedIface = ifc.name },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.cfg_active) + ": ${vm.selectedIface}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- profiles management ----
        SectionCard(stringResource(R.string.cfg_profiles_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cfg_saved_profiles), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.loadProfiles() }, enabled = connected && !vm.monitorRunning) { Text(stringResource(R.string.btn_refresh)) }
                TextButton(onClick = { vm.newProfileDraft(); vm.selectedTab = 3; vm.showSettings = false }, enabled = connected && !vm.monitorRunning) { Text(stringResource(R.string.btn_new)) }
            }
            if (vm.profiles.isEmpty()) {
                Text(stringResource(R.string.cfg_no_profiles),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                vm.profiles.forEach { name ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        TextButton(onClick = { vm.openProfile(name); vm.selectedTab = 3; vm.showSettings = false }, enabled = !vm.monitorRunning) { Text(stringResource(R.string.cfg_edit)) }
                        TextButton(onClick = { vm.applyProfile(name) }, enabled = !vm.monitorRunning) { Text(stringResource(R.string.cfg_apply)) }
                        IconButton(onClick = { vm.deleteProfile(name) }, enabled = !vm.monitorRunning) {
                            Icon(Icons.Default.Delete, stringResource(R.string.cd_delete), tint = ColorError)
                        }
                    }
                }
            }
        }

        // ---- analyzers info ----
        SectionCard(stringResource(R.string.cfg_analyzers_title)) {
            com.wizzard.cc1101.protocol.AnalyzerRegistry.all().forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("• ${a.displayName}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                stringResource(R.string.cfg_analyzers_hint),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
