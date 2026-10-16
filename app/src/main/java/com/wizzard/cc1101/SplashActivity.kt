package com.wizzard.cc1101

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Splash: white "cc1101-wizzard" title on a graphite background, then hands
 * off to [MainActivity]. If launched by a USB-attach intent, the device extra
 * is forwarded so MainActivity can auto-connect.
 */
class SplashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forwardedUsbDevice = intent?.getParcelableExtra<android.hardware.usb.UsbDevice>(
            android.hardware.usb.UsbManager.EXTRA_DEVICE
        )

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E2127)),   // graphite
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "cc1101-wizzard",
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            LaunchedEffect(Unit) {
                delay(1200)
                val next = Intent(this@SplashActivity, MainActivity::class.java).apply {
                    if (forwardedUsbDevice != null) {
                        putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, forwardedUsbDevice)
                    }
                }
                startActivity(next)
                finish()
            }
        }
    }
}
