package com.wizzard.cc1101.cli

import android.content.Context
import android.hardware.usb.UsbDevice
import com.wizzard.cc1101.R
import com.wizzard.cc1101.serial.SerialManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Turns the raw serial byte stream into classified [LogLine]s and
 * [CapturedPacket]s, and serialises outgoing commands so each one is sent only
 * after the previous has finished (the firmware prints `OK` or an error line
 * when a command completes — see cc1101-wizzard-v2.ino `loop()`).
 *
 * The firmware echoes input (Terminal.setEcho(true)), so every command comes
 * back as an ECHO line; we surface it dimmed and use `OK`/error lines as the
 * real completion markers.
 */
class CliEngine(
    private val serial: SerialManager,
    private val scope: CoroutineScope,
    private val appContext: Context,
) : SerialManager.Listener {

    data class CmdResult(val command: String, val ok: Boolean, val lines: List<String>)

    private data class Command(
        val text: String,
        val deferred: CompletableDeferred<CmdResult>,
        val timeoutMs: Long,
        val silent: Boolean,          // don't echo the command as a TX log line
    )

    // ---- outputs (wired by the ViewModel) -------------------------------
    var onLog: ((LogLine) -> Unit)? = null
    var onMonitor: ((CapturedPacket) -> Unit)? = null
    var onConnection: ((ConnState, UsbDevice?) -> Unit)? = null

    private val ids = AtomicLong(0)
    private val queue = Channel<Command>(Channel.UNLIMITED)

    // completion markers printed by the firmware
    private val okMarker = "OK"
    private val errorMarkers = listOf(
        "not enough params", "wrong params", "unknown command",
        "CC1101 call error", "file error",
    )

    // current in-flight command state (touched from both the command coroutine
    // and the serial read thread, hence volatile + synchronized list)
    @Volatile private var pending: Command? = null
    private val pendingLines = java.util.Collections.synchronizedList(ArrayList<String>())
    @Volatile private var pendingOk = true

    init {
        serial.listener = this
        scope.launch {
            for (cmd in queue) runOne(cmd)
        }
    }

    // ---- command gating --------------------------------------------------

    /**
     * When true, every command except monitor control (`monitor stop` /
     * `monitor status` / `monitor`) is refused locally and never reaches the
     * RP2040. Set by the ViewModel while the monitor is running, so profiles,
     * parameter changes and transmissions can't be sent mid-capture.
     */
    @Volatile var blockWhileMonitor = false

    private fun isMonitorControl(command: String): Boolean {
        val t = command.trim()
        return t == "monitor" || t.startsWith("monitor stop") || t.startsWith("monitor status")
    }

    // ---- public command API ---------------------------------------------

    /** Enqueue a CLI command (without trailing newline). Suspends until done. */
    suspend fun send(command: String, timeoutMs: Long = 4000, silent: Boolean = false): CmdResult {
        if (blockWhileMonitor && !isMonitorControl(command)) {
            val msg = appContext.getString(R.string.cli_monitor_locked)
            emit(LogKind.ERROR, "⛔ $command — $msg")
            return CmdResult(command, false, listOf(msg))
        }
        val d = CompletableDeferred<CmdResult>()
        queue.send(Command(command, d, timeoutMs, silent))
        return d.await()
    }

    /** Fire-and-forget (still serialised through the queue). */
    fun post(command: String, timeoutMs: Long = 4000) {
        scope.launch { runCatching { send(command, timeoutMs) } }
    }

    private suspend fun runOne(cmd: Command) {
        if (!serial.isOpen) {
            cmd.deferred.complete(CmdResult(cmd.text, false, listOf("not connected")))
            return
        }
        pendingLines.clear()
        pendingOk = true
        pending = cmd
        if (!cmd.silent) emit(LogKind.TX, "> ${cmd.text}")

        serial.write((cmd.text + "\n").toByteArray(Charsets.US_ASCII))

        val result = withTimeoutOrNull(cmd.timeoutMs) { cmd.deferred.await() }
        if (result == null) {
            // timed out: complete with whatever we have
            val p = pending
            pending = null
            if (p != null && !p.deferred.isCompleted) {
                emit(LogKind.ERROR, "(timeout waiting for '${cmd.text}')")
                p.deferred.complete(CmdResult(cmd.text, false, pendingLines.toList()))
            }
        }
    }

    // ---- SerialManager.Listener -----------------------------------------

    private val rxBuffer = StringBuilder()

    override fun onConnected(device: UsbDevice) {
        onConnection?.invoke(ConnState.CONNECTED, device)
        emit(LogKind.INFO, "connected: ${device.productName ?: device.deviceName}")
    }

    override fun onDisconnected() {
        failPending("disconnected")
        onConnection?.invoke(ConnState.DISCONNECTED, null)
        emit(LogKind.INFO, "disconnected")
    }

    override fun onError(message: String) {
        emit(LogKind.ERROR, message)
    }

    override fun onBytes(data: ByteArray) {
        rxBuffer.append(String(data, Charsets.US_ASCII))
        var idx = indexOfLineBreak(rxBuffer)
        while (idx >= 0) {
            val line = rxBuffer.substring(0, idx)
            // drop the break char(s)
            var cut = idx + 1
            if (cut < rxBuffer.length && rxBuffer[idx] == '\r' && rxBuffer[cut] == '\n') cut++
            rxBuffer.delete(0, cut)
            handleLine(line)
            idx = indexOfLineBreak(rxBuffer)
        }
    }

    private fun indexOfLineBreak(sb: StringBuilder): Int {
        for (i in sb.indices) if (sb[i] == '\n' || sb[i] == '\r') return i
        return -1
    }

    // ---- line classification --------------------------------------------

    private fun handleLine(rawLine: String) {
        val line = rawLine.trimEnd('\r', '\n')
        if (line.isEmpty()) return

        // 1) monitor capture? route out-of-band regardless of pending command
        if (MonitorParser.isMonitorLine(line)) {
            val pkt = MonitorParser.parse(line)
            emit(LogKind.MONITOR, line, pkt)
            if (pkt != null) onMonitor?.invoke(pkt)
            return
        }

        val p = pending

        // 2) echo of the command we just sent
        if (p != null && line == p.text) {
            emit(LogKind.ECHO, line)
            return
        }

        // 3) boot banner / identification
        if (line.startsWith("CC1101 Wizard tool v")) {
            emit(LogKind.INFO, line)
            return
        }

        // 4) completion markers
        if (p != null) {
            if (line == okMarker) {
                emit(LogKind.RX, line)
                complete(true)
                return
            }
            if (errorMarkers.any { line.startsWith(it) }) {
                emit(LogKind.ERROR, line)
                pendingOk = false
                complete(false)
                return
            }
            // ordinary response line belonging to the pending command
            pendingLines.add(line)
            emit(LogKind.RX, line)
            return
        }

        // 5) unsolicited line (no pending command)
        emit(if (line.startsWith("hint:")) LogKind.INFO else LogKind.RX, line)
    }

    private fun complete(ok: Boolean) {
        val p = pending ?: return
        pending = null
        if (!p.deferred.isCompleted) {
            p.deferred.complete(CmdResult(p.text, ok, pendingLines.toList()))
        }
    }

    private fun failPending(reason: String) {
        val p = pending ?: return
        pending = null
        if (!p.deferred.isCompleted) {
            p.deferred.complete(CmdResult(p.text, false, listOf(reason)))
        }
    }

    private fun emit(kind: LogKind, text: String, packet: CapturedPacket? = null) {
        onLog?.invoke(LogLine(ids.incrementAndGet(), kind, text, packet = packet))
    }

    /**
     * Emit an app-originated log line through the same monotonic id counter used
     * for device traffic, so every [LogLine.id] in the log is unique (duplicate
     * ids would crash the LazyColumn that keys on them).
     */
    fun log(kind: LogKind, text: String) = emit(kind, text)
}
