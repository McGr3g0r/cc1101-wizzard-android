package com.wizzard.cc1101.serial

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.Executors

/**
 * Thin wrapper over usb-serial-for-android. Handles device discovery, the
 * runtime USB permission dialog, opening a port at 115200 8N1, a background
 * read thread, and writes. All driver specifics live here; the CLI layer only
 * sees bytes in / bytes out.
 */
class SerialManager(private val context: Context) {

    interface Listener {
        fun onConnected(device: UsbDevice)
        fun onDisconnected()
        fun onBytes(data: ByteArray)
        fun onError(message: String)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.wizzard.cc1101.USB_PERMISSION"
        const val BAUD_RATE = 115200
    }

    var listener: Listener? = null

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var port: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var permissionReceiver: BroadcastReceiver? = null

    /** All driver-recognised serial devices currently attached. */
    fun availableDrivers(): List<UsbSerialDriver> =
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

    fun describe(driver: UsbSerialDriver): String {
        val d = driver.device
        return "%s  VID=%04X PID=%04X".format(
            d.productName ?: d.deviceName, d.vendorId, d.productId
        )
    }

    val isOpen: Boolean get() = port != null

    /** Request permission if needed, then open. */
    fun connect(driver: UsbSerialDriver) {
        val device = driver.device
        if (usbManager.hasPermission(device)) {
            open(driver)
        } else {
            requestPermission(driver)
        }
    }

    /** Pick the first available driver and connect; returns false if none. */
    fun connectFirst(): Boolean {
        val d = availableDrivers().firstOrNull() ?: return false
        connect(d)
        return true
    }

    private fun requestPermission(driver: UsbSerialDriver) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        permissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                context.unregisterReceiver(this)
                permissionReceiver = null
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) open(driver)
                else listener?.onError("USB permission denied")
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(permissionReceiver, filter)
        }
        usbManager.requestPermission(driver.device, pi)
    }

    private fun open(driver: UsbSerialDriver) {
        try {
            val connection = usbManager.openDevice(driver.device)
                ?: run { listener?.onError("openDevice failed (no permission?)"); return }
            val p = driver.ports.first()
            p.open(connection)
            p.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try {
                p.dtr = true   // RP2040 CDC enumerates regardless, but assert DTR/RTS anyway
                p.rts = true
            } catch (_: Exception) { /* some drivers don't support control lines */ }
            port = p

            val io = SerialInputOutputManager(p, object : SerialInputOutputManager.Listener {
                override fun onNewData(data: ByteArray) { listener?.onBytes(data) }
                override fun onRunError(e: Exception) {
                    listener?.onError("serial read error: ${e.message}")
                    disconnect()
                }
            })
            ioManager = io
            ioExecutor.submit(io)
            listener?.onConnected(driver.device)
        } catch (e: Exception) {
            listener?.onError("open failed: ${e.message}")
            disconnect()
        }
    }

    fun write(bytes: ByteArray) {
        val p = port ?: run { listener?.onError("not connected"); return }
        try {
            p.write(bytes, 1000)
        } catch (e: Exception) {
            listener?.onError("write failed: ${e.message}")
        }
    }

    fun disconnect() {
        ioManager?.listener = null
        ioManager?.stop()
        ioManager = null
        try { port?.close() } catch (_: Exception) {}
        val wasOpen = port != null
        port = null
        permissionReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            permissionReceiver = null
        }
        if (wasOpen) listener?.onDisconnected()
    }
}
