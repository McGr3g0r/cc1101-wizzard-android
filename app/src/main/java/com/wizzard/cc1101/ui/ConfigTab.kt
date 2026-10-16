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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
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
        SectionCard("Ustawienia") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Znakuj pakiety pozycją GPS", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Gdy włączone, zapis odebranego pakietu dołącza współrzędne GPS do pliku.",
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
                Text("Brak uprawnienia do lokalizacji — przyznaj je, aby zapisywać GPS.",
                    fontSize = 11.sp, color = ColorError, modifier = Modifier.padding(top = 4.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            // ---- auto-reconnect ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatyczne wznawianie połączenia", style = MaterialTheme.typography.bodyMedium)
                    Text("Po nieoczekiwanym rozłączeniu aplikacja sama spróbuje połączyć się z RP2040 ponownie.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Checkbox(checked = vm.autoReconnect, onCheckedChange = { vm.updateAutoReconnect(it) })
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            // ---- colour template ----
            Text("Szablon kolorystyczny", style = MaterialTheme.typography.bodyMedium)
            Text("Zmienia paletę całej aplikacji.", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                THEME_TEMPLATES.forEach { t ->
                    Pill(t.name, selected = vm.themeId == t.id, onClick = { vm.setTheme(t.id) })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)

            // ---- TX repeat gap ----
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Odstęp między powtórzeniami (TX)", style = MaterialTheme.typography.bodyMedium)
                    Text("Pauza wstawiana między kolejnymi nadaniami pakietu.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedIconButton(onClick = { vm.updateConfigGapMs(vm.configGapMs - 50) }, enabled = vm.configGapMs > 0) {
                    Icon(Icons.Default.Remove, "mniej")
                }
                Text("${vm.configGapMs} ms",
                    Modifier.widthIn(min = 72.dp).padding(horizontal = 8.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 16.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                OutlinedIconButton(onClick = { vm.updateConfigGapMs(vm.configGapMs + 50) }, enabled = vm.configGapMs < 10000) {
                    Icon(Icons.Default.Add, "więcej")
                }
            }
        }

        // ---- USB device selection ----
        SectionCard("Urządzenie USB (115200 8N1)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wykryte adaptery", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.refreshDrivers() }) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Skanuj")
                }
            }
            if (vm.drivers.isEmpty()) {
                Text("Brak urządzeń. Podłącz RP2040 przez USB-OTG.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                vm.drivers.forEachIndexed { i, d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(vm.describe(d), Modifier.weight(1f),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Button(onClick = { vm.connect(d) }, enabled = !connected) { Text("Połącz") }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            ConnectionIndicator(vm.conn, vm.connectedName)
            Spacer(Modifier.height(6.dp))
            Row {
                OutlinedButton(onClick = { vm.verify() }, enabled = connected && !vm.monitorRunning) { Text("Sprawdź") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.disconnect() }, enabled = connected) { Text("Rozłącz") }
            }
        }

        // ---- radio interface selection ----
        SectionCard("Wybór radia (interfejs)") {
            if (vm.ifaces.isEmpty()) {
                Text("Użyj „Sprawdź”, aby pobrać listę interfejsów (radio list).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("r0", "r1", "r2", "r3").forEach {
                        Pill(it, selected = vm.selectedIface == it, onClick = { vm.selectedIface = it })
                    }
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    vm.ifaces.forEach { ifc ->
                        Pill(
                            "${ifc.name}${if (ifc.present) "" else " (brak)"}",
                            selected = vm.selectedIface == ifc.name,
                            onClick = { vm.selectedIface = ifc.name },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("aktywny: ${vm.selectedIface}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- profiles management ----
        SectionCard("Profile radiowe (edycja w zakładce cc1101)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zapisane profile", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { vm.loadProfiles() }, enabled = connected && !vm.monitorRunning) { Text("Odśwież") }
                TextButton(onClick = { vm.newProfileDraft(); vm.selectedTab = 3; vm.showSettings = false }, enabled = connected && !vm.monitorRunning) { Text("Nowy") }
            }
            if (vm.profiles.isEmpty()) {
                Text("Brak profili lub nie pobrano listy.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                vm.profiles.forEach { name ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                        TextButton(onClick = { vm.openProfile(name); vm.selectedTab = 3; vm.showSettings = false }, enabled = !vm.monitorRunning) { Text("Edytuj") }
                        TextButton(onClick = { vm.applyProfile(name) }, enabled = !vm.monitorRunning) { Text("Zastosuj") }
                        IconButton(onClick = { vm.deleteProfile(name) }, enabled = !vm.monitorRunning) {
                            Icon(Icons.Default.Delete, "usuń", tint = ColorError)
                        }
                    }
                }
            }
        }

        // ---- analyzers info ----
        SectionCard("Analizatory protokołów (wtyczki)") {
            com.wizzard.cc1101.protocol.AnalyzerRegistry.all().forEach { a ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("• ${a.displayName}", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Text(
                "Nowe implementacje dodaje się przez AnalyzerRegistry.register(...) " +
                    "(interfejs ProtocolAnalyzer).",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
