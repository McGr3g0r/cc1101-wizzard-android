package com.wizzard.cc1101.ui

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

private val MODS = listOf("2fsk", "gfsk", "ook", "4fsk", "msk")
private val QUICK_PARAMS = listOf("freq", "drate", "dev", "rxbw", "chan", "pa")

@Composable
fun Cc1101Tab(vm: AppViewModel) {
    val connected = vm.conn == ConnState.VERIFIED || vm.conn == ConnState.CONNECTED

    LaunchedEffect(vm.conn, vm.selectedIface) {
        if (connected) { vm.loadInfo(); vm.loadProfiles() }
    }

    val locked = vm.monitorRunning

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (locked) MonitorLockBanner()

        if (!connected) {
            SectionCard("cc1101") {
                Text(stringResource(R.string.cc_connect_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- modulation ----
        SectionCard(stringResource(R.string.cc_modulation) + " (${vm.selectedIface})") {
            val current = vm.radioInfo["mod"]
            Row(Modifier.fillMaxWidth()) {
                MODS.forEach { m ->
                    Pill(m, selected = current == m, onClick = { if (!locked) vm.setMod(m) })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.cc_current) + ": ${current ?: "—"}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- quick params ----
        SectionCard(stringResource(R.string.cc_quick_params)) {
            QUICK_PARAMS.forEach { p -> ParamRow(vm, p) }
            Spacer(Modifier.height(8.dp))
            GenericParamRow(vm)
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { vm.radioAction("rx") }, enabled = !locked) { Text("RX") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { vm.radioAction("idle") }, enabled = !locked) { Text("IDLE") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { vm.radioAction("reset") }, enabled = !locked) { Text("RESET") }
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { vm.loadInfo() }, enabled = !locked) { Text(stringResource(R.string.btn_refresh)) }
            }
        }

        // ---- full info dump ----
        SectionCard(stringResource(R.string.cc_full_state)) {
            if (vm.radioInfo.isEmpty()) {
                Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                vm.radioInfo.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, Modifier.width(130.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(v, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        }

        // ---- profiles ----
        ProfilesEditor(vm)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ParamRow(vm: AppViewModel, param: String) {
    var text by remember(param, vm.radioInfo[param]) { mutableStateOf(vm.radioInfo[param] ?: "") }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(param, Modifier.width(70.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            modifier = Modifier.weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
        )
        Spacer(Modifier.width(6.dp))
        Button(onClick = { vm.setParam(param, text.trim()) }, enabled = !vm.monitorRunning) { Text("Set") }
    }
}

@Composable
private fun GenericParamRow(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("param") },
            singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(stringResource(R.string.lbl_value)) },
            singleLine = true, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Button(onClick = { if (name.isNotBlank()) vm.setParam(name.trim(), value.trim()) }, enabled = !vm.monitorRunning) { Text("Set") }
    }
}

@Composable
private fun ProfilesEditor(vm: AppViewModel) {
    SectionCard(stringResource(R.string.cc_profiles)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            vm.profiles.forEach { name ->
                Pill(name, selected = vm.selectedProfile == name, onClick = { vm.openProfile(name) })
            }
        }
        Spacer(Modifier.height(6.dp))
        Row {
            TextButton(onClick = { vm.loadProfiles() }) { Text(stringResource(R.string.btn_refresh)) }
            TextButton(onClick = { vm.newProfileDraft() }) { Text(stringResource(R.string.btn_new)) }
            vm.selectedProfile?.let { sel ->
                TextButton(onClick = { vm.deleteProfile(sel) }) { Text(stringResource(R.string.btn_delete), color = ColorError) }
            }
        }

        OutlinedTextField(
            value = vm.profileEditorName,
            onValueChange = { vm.profileEditorName = it },
            label = { Text(stringResource(R.string.lbl_profile_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = vm.profileEditorText,
            onValueChange = { vm.profileEditorText = it },
            label = { Text(stringResource(R.string.lbl_profile_lines)) },
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { vm.saveProfile() }, enabled = !vm.monitorRunning) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_save))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.profileEditorName.takeIf { it.isNotBlank() }?.let { vm.applyProfile(it) } },
                enabled = !vm.monitorRunning,
            ) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.btn_apply_on) + " ${vm.selectedIface}") }
        }
    }
}
