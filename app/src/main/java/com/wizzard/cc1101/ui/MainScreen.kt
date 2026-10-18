/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import com.wizzard.cc1101.BuildConfig
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.AppViewModel
import com.wizzard.cc1101.R

private val TAB_TITLES = listOf("Monitor", "Protocol", "TX", "cc1101")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var showCloseDialog by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    // Back: close the settings screen first; otherwise ask about the monitor.
    BackHandler(enabled = true) {
        when {
            vm.showSettings -> vm.showSettings = false
            vm.monitorRunning -> showCloseDialog = true
            else -> (context as? Activity)?.finish()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            if (vm.showSettings) stringResource(R.string.settings_title) else "cc1101-wizzard",
                            fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                        )
                    },
                    navigationIcon = {
                        if (vm.showSettings) {
                            IconButton(onClick = { vm.showSettings = false }) {
                                Icon(Icons.Default.ArrowBack, stringResource(R.string.cd_back))
                            }
                        }
                    },
                    actions = {
                        if (!vm.showSettings) {
                            IconButton(onClick = { showAbout = true }) {
                                Icon(Icons.Default.Info, stringResource(R.string.about_title))
                            }
                            IconButton(onClick = { vm.showSettings = true }) {
                                Icon(Icons.Default.Settings, stringResource(R.string.settings_title))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                )
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!vm.showSettings) {
                    ScrollableTabRow(
                        selectedTabIndex = vm.selectedTab,
                        containerColor = MaterialTheme.colorScheme.background,
                        edgePadding = 8.dp,
                    ) {
                        TAB_TITLES.forEachIndexed { i, title ->
                            Tab(
                                selected = vm.selectedTab == i,
                                onClick = { vm.selectedTab = i },
                                text = { Text(title, fontSize = 13.sp) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (vm.showSettings) {
                ConfigTab(vm)
            } else when (vm.selectedTab) {
                0 -> MainTab(vm)
                1 -> InterpretTab(vm)
                2 -> TxTab(vm)
                3 -> Cc1101Tab(vm)
            }
        }
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text(stringResource(R.string.about_app_name), fontFamily = FontFamily.Monospace) },
            text = {
                Column {
                    Text(stringResource(R.string.about_author))
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.about_email))
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.about_copyright))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${BuildConfig.VERSION_NAME}  ·  ${BuildConfig.GIT_HASH}  (build ${BuildConfig.VERSION_CODE})",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text(stringResource(R.string.ok)) }
            },
        )
    }

    if (showCloseDialog) {
        AlertDialog(
            onDismissRequest = { showCloseDialog = false },
            title = { Text(stringResource(R.string.monitor_running_title)) },
            text = { Text(stringResource(R.string.monitor_close_question)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.stopMonitor()
                    showCloseDialog = false
                    (context as? Activity)?.finish()
                }) { Text(stringResource(R.string.stop_and_exit)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showCloseDialog = false
                        (context as? Activity)?.finish()
                    }) { Text(stringResource(R.string.exit_without_stopping)) }
                    TextButton(onClick = { showCloseDialog = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }
}
