/*
 * CC1101 Wizzard
 * Author: Grzegorz Rajtar
 * e-mail: mcgregor@blackmesaeast.com.pl
 * (c) 2026
 */

package com.wizzard.cc1101

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wizzard.cc1101.ui.theme.BrandOrange
import kotlinx.coroutines.delay

/**
 * Splash: a signal-waveform logo above the orange "cc1101-wizzard" title, with the
 * app version and build shown smaller beneath it, on a graphite background; then
 * hands off to [MainActivity]. If launched by a USB-attach intent, the device extra
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    WaveformLogo(
                        modifier = Modifier.width(240.dp).height(54.dp),
                        color = BrandOrange,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = "cc1101-wizzard",
                        color = BrandOrange,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${BuildConfig.VERSION_NAME}  ·  ${BuildConfig.GIT_HASH}",
                        color = Color(0xFF9AA0AA),       // muted grey
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
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

/** A stylised OOK pulse-train (square wave) drawn as a single stroked path. */
@Composable
private fun WaveformLogo(modifier: Modifier, color: Color) {
    // relative widths of alternating high/low segments, like a short OOK frame
    val segments = intArrayOf(1, 1, 2, 1, 1, 2, 2, 1, 1, 3, 1, 1, 2, 1, 1)
    Canvas(modifier) {
        val total = segments.sum().toFloat()
        val unit = size.width / total
        val hi = size.height * 0.18f          // high level (carrier on)
        val lo = size.height * 0.82f          // low level (carrier off)
        val path = Path()
        var x = 0f
        var high = true                        // start high
        var y = if (high) hi else lo
        path.moveTo(0f, y)
        for (seg in segments) {
            val nx = x + seg * unit
            path.lineTo(nx, y)                 // horizontal run at current level
            high = !high
            val ny = if (high) hi else lo
            path.lineTo(nx, ny)                // vertical transition
            x = nx; y = ny
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = size.height * 0.10f, cap = StrokeCap.Round),
        )
    }
}
