/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.wizzard.cc1101.ui.MainScreen
import com.wizzard.cc1101.ui.theme.CC1101Theme

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If launched via USB attach (or forwarded from the splash), auto-connect
        // to the attached device.
        val attached = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (attached != null) autoConnect(attached)

        setContent {
            CC1101Theme(themeId = vm.themeId) {
                MainScreen(vm)
            }
        }
    }

    private fun autoConnect(device: UsbDevice) {
        vm.refreshDrivers()
        val match = vm.drivers.firstOrNull { it.device.deviceId == device.deviceId }
            ?: vm.drivers.firstOrNull()
        if (match != null) vm.connect(match)
    }
}
