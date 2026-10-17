package com.wizzard.cc1101

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.wizzard.cc1101.cli.*
import com.wizzard.cc1101.data.PacketStore
import com.wizzard.cc1101.protocol.AnalysisResult
import com.wizzard.cc1101.protocol.AnalyzerRegistry
import com.wizzard.cc1101.protocol.ProtoField
import com.wizzard.cc1101.protocol.SavedEncoder
import com.wizzard.cc1101.serial.SerialManager
import kotlinx.coroutines.launch

/**
 * Single source of truth for the UI. Owns the serial link, the CLI engine, the
 * firmware command helpers and the packet store, and exposes Compose state for
 * the four tabs.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val serial = SerialManager(app)
    private val engine = CliEngine(serial, viewModelScope, app)
    private val fw = FirmwareCli(engine, app)
    private val store = PacketStore(app)
    private val prefs = com.wizzard.cc1101.util.Prefs(app)
    private val location = com.wizzard.cc1101.util.LocationHelper(app)

    /** Localized string lookup from the application context. */
    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    // ---- main-tab state ---------------------------------------------------
    val logs: SnapshotStateList<LogLine> = mutableStateListOf()
    var conn by mutableStateOf(ConnState.DISCONNECTED); private set
    var connectedName by mutableStateOf<String?>(null); private set
    var deviceInfo by mutableStateOf<FirmwareCli.DeviceInfo?>(null); private set
    var monitorRunning by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set

    // ---- config-tab state -------------------------------------------------
    val drivers: SnapshotStateList<UsbSerialDriver> = mutableStateListOf()
    val ifaces: SnapshotStateList<FirmwareCli.RadioIface> = mutableStateListOf()
    var selectedIface by mutableStateOf("r0")

    // ---- cc1101-tab state -------------------------------------------------
    val radioInfo: SnapshotStateMap<String, String> = mutableStateMapOf()
    val profiles: SnapshotStateList<String> = mutableStateListOf()
    var selectedProfile by mutableStateOf<String?>(null)
    var profileEditorName by mutableStateOf("")
    var profileEditorText by mutableStateOf("")

    // ---- interpretation-tab state ----------------------------------------
    var selectedPacket by mutableStateOf<CapturedPacket?>(null); private set
    val results: SnapshotStateList<AnalysisResult> = mutableStateListOf()
    var selectedResult by mutableStateOf<AnalysisResult?>(null); private set
    val editedValues: SnapshotStateMap<String, String> = mutableStateMapOf()
    val savedPackets: SnapshotStateList<PacketStore.Saved> = mutableStateListOf()

    // ---- samples-tab state ------------------------------------------------
    var samplesDir by mutableStateOf<java.io.File?>(null); private set
    val sampleEntries: SnapshotStateList<java.io.File> = mutableStateListOf()
    var currentSample by mutableStateOf<com.wizzard.cc1101.data.FlipperSample?>(null); private set
    var sampleError by mutableStateOf<String?>(null); private set

    // ---- TX-tab state -----------------------------------------------------
    var txSignal by mutableStateOf<TxSignal?>(null); private set
    var txRepeat by mutableStateOf(1); private set
    var txFreqHz by mutableStateOf(433_920_000L); private set
    var txError by mutableStateOf<String?>(null); private set

    val txFrequencies: List<Pair<String, Long>> = listOf(
        "433.92 MHz" to 433_920_000L,
        "433.42 MHz" to 433_420_000L,
        "315 MHz" to 315_000_000L,
    )

    fun setTxFreq(hz: Long) { txFreqHz = hz }
    fun updateTxRepeat(n: Int) { txRepeat = n.coerceIn(1, 100) }

    /**
     * Inter-frame gap (ms) used for the current transmission. This is a SESSION
     * override: it is seeded from the persistent config default at start and
     * edited in the TX tab, but is NOT written back to settings.
     */
    var txGapMs by mutableStateOf(prefs.txGapMs); private set
    fun updateTxGapMs(ms: Int) { txGapMs = ms.coerceIn(0, 10000) }

    /** Persistent default gap (ms), edited in Konfiguracja and stored. */
    var configGapMs by mutableStateOf(prefs.txGapMs); private set
    fun updateConfigGapMs(ms: Int) { configGapMs = ms.coerceIn(0, 10000); prefs.txGapMs = configGapMs }

    fun loadTxFromUri(uri: android.net.Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }.onSuccess { text ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "flipper.sub"
            parseTxFlipper(name, text)
        }.onFailure { txError = str(R.string.vm_cannot_read_file, it.message ?: "") }
    }

    fun loadTxFromFile(file: java.io.File) {
        runCatching { file.readText() }
            .onSuccess { parseTxFlipper(file.name, it) }
            .onFailure { txError = str(R.string.vm_cannot_read_file, it.message ?: "") }
    }

    private fun parseTxFlipper(name: String, text: String) {
        val s = com.wizzard.cc1101.data.FlipperSample.parse(name, text)
        if (s == null) { txError = str(R.string.vm_unsupported_file, name); return }
        val pulses = s.toPulses()
        if (pulses == null) { txError = str(R.string.vm_no_pulses_in, name); return }
        txError = null
        if (s.frequencyHz > 0) txFreqHz = s.frequencyHz
        txSignal = TxSignal(name, TxFrame.OokPulses(pulses), pulses, null, s.frequencyHz.takeIf { it > 0 })
    }

    fun loadTxFromSaved(saved: PacketStore.Saved) {
        val frame = SavedEncoder.encode(saved.analyzerId, saved.fields)
        if (frame == null) { txError = str(R.string.vm_cannot_build_frame_from, saved.name); return }
        txError = null
        val pulses = (frame as? TxFrame.OokPulses)?.us
        val hex = (frame as? TxFrame.FskHex)?.hex
        txSignal = TxSignal(saved.name, frame, pulses, hex, null)
    }

    // ---- protocol compose (build a frame from scratch) --------------------
    var composeId by mutableStateOf<String?>(null); private set
    val composeValues: SnapshotStateMap<String, String> = mutableStateMapOf()

    fun composeTemplates() = com.wizzard.cc1101.protocol.ProtocolTemplates.templates

    /** Pick a protocol to compose; loads its default fields. */
    fun selectComposeProtocol(id: String) {
        val tpl = com.wizzard.cc1101.protocol.ProtocolTemplates.byId(id) ?: return
        composeId = id
        composeValues.clear()
        tpl.defaults().forEach { composeValues[it.key] = it.value }
        // compose and packet views are mutually exclusive
        selectedPacket = null
        results.clear()
        selectedResult = null
    }

    fun clearCompose() { composeId = null; composeValues.clear() }

    fun updateComposeField(key: String, value: String) { composeValues[key] = value }

    /** Fields of the current compose protocol, as edited. */
    fun composeFields(): List<ProtoField> {
        val tpl = composeId?.let { com.wizzard.cc1101.protocol.ProtocolTemplates.byId(it) } ?: return emptyList()
        return tpl.defaults().map { it.copy(value = composeValues[it.key] ?: it.value) }
    }

    /** Open the composed protocol in the Protocol tab (tab index 1). */
    fun openComposeInProtocol() {
        if (composeId != null) selectedTab = 1
    }

    fun transmitCompose() = scope {
        val id = composeId ?: return@scope
        val frame = com.wizzard.cc1101.protocol.ProtocolTemplates.encode(id, composeFields()) ?: run {
            engine.log(LogKind.ERROR, str(R.string.vm_cannot_build_frame_for, id))
            return@scope
        }
        // apply the TX frequency (+ OOK for pulse frames) before sending
        if (frame is TxFrame.OokPulses) fw.setMod(selectedIface, "ook")
        fw.setParam(selectedIface, "freq", txFreqHz.toString())
        transmit(frame)   // honours repeat count + gap
    }

    /** Send the loaded TX signal through the protocol analyzers (Protocol tab). */
    fun interpretTxSignal() {
        val sig = txSignal ?: return
        val us = sig.pulses ?: run { txError = str(R.string.vm_interpret_ook_only); return }
        interpret(CapturedPacket.Ook("tx", System.currentTimeMillis(), 0, 0, us, "tx:${sig.label}"))
    }

    /** Apply the chosen frequency (and OOK for pulse signals), then send N times. */
    fun sendTx(applyRadio: Boolean) = scope {
        val sig = txSignal ?: run { txError = str(R.string.vm_no_signal_loaded); return@scope }
        if (applyRadio) {
            if (sig.pulses != null) fw.setMod(selectedIface, "ook")
            fw.setParam(selectedIface, "freq", txFreqHz.toString())
        }
        transmit(sig.frame)
    }

    // ---- settings ---------------------------------------------------------
    var gpsEnabled by mutableStateOf(prefs.gpsEnabled); private set
    var themeId by mutableStateOf(prefs.themeId); private set
    var autoReconnect by mutableStateOf(prefs.autoReconnect); private set

    fun updateGpsEnabled(enabled: Boolean) {
        gpsEnabled = enabled
        prefs.gpsEnabled = enabled
    }

    fun setTheme(id: String) { themeId = id; prefs.themeId = id }
    fun updateAutoReconnect(enabled: Boolean) { autoReconnect = enabled; prefs.autoReconnect = enabled }

    // ---- OOK monitor capture window (firmware build 2+) -------------------
    var captureMinUs by mutableStateOf(prefs.captureMinUs); private set
    var captureGapUs by mutableStateOf(prefs.captureGapUs); private set
    fun updateCaptureMinUs(us: Int) { captureMinUs = us.coerceIn(0, 2000); prefs.captureMinUs = captureMinUs }
    fun updateCaptureGapUs(us: Int) { captureGapUs = us.coerceIn(0, 60000); prefs.captureGapUs = captureGapUs }

    fun hasLocationPermission() = location.hasPermission()

    /** Whether the Konfiguracja screen (gear icon) is shown. */
    var showSettings by mutableStateOf(false)

    /** Tab the UI should show. */
    var selectedTab by mutableStateOf(0)

    /**
     * Whether the Monitor-tab connection panel is expanded. Kept here (not in
     * the composable) so it survives switching tabs away and back.
     */
    var connExpanded by mutableStateOf(true)

    private val MAX_LOG = 2000

    init {
        engine.onLog = { line ->
            logs.add(line)
            if (logs.size > MAX_LOG) repeat(logs.size - MAX_LOG) { logs.removeAt(0) }
        }
        engine.onMonitor = { /* packet is already attached to its MONITOR log line */ }
        engine.onConnection = { state, dev ->
            conn = state
            connectedName = dev?.let { it.productName ?: it.deviceName }
            if (state == ConnState.DISCONNECTED) {
                deviceInfo = null
                monitorRunning = false
                engine.blockWhileMonitor = false
                // unexpected drop + auto-reconnect enabled -> try to come back
                if (autoReconnect && !manualDisconnect) tryReconnect()
                manualDisconnect = false
            }
            // automatically identify the device right after the link opens
            if (state == ConnState.CONNECTED) verify()
        }
        refreshDrivers()
        refreshSaved()
    }

    // ---- connection -------------------------------------------------------

    fun refreshDrivers() {
        drivers.clear()
        drivers.addAll(serial.availableDrivers())
    }

    fun describe(d: UsbSerialDriver) = serial.describe(d)

    private var manualDisconnect = false

    fun connect(driver: UsbSerialDriver) {
        manualDisconnect = false
        conn = ConnState.CONNECTING
        serial.connect(driver)
    }

    /** Retry opening the first available serial device a few times (auto-reconnect). */
    private fun tryReconnect() {
        viewModelScope.launch {
            engine.log(LogKind.INFO, str(R.string.vm_autoreconnect_attempt))
            repeat(6) {
                kotlinx.coroutines.delay(1500)
                if (serial.isOpen || manualDisconnect) return@launch
                refreshDrivers()
                if (drivers.isNotEmpty()) {
                    conn = ConnState.CONNECTING
                    serial.connect(drivers.first())
                    return@launch
                }
            }
        }
    }

    fun connectFirstAndVerify() {
        refreshDrivers()
        if (drivers.isEmpty()) {
            engine.log(LogKind.ERROR, "no USB serial devices found")
            return
        }
        conn = ConnState.CONNECTING
        serial.connect(drivers.first())
        // verification is triggered from the UI once onConnected flips state
    }

    fun disconnect() {
        manualDisconnect = true   // user-initiated: do not auto-reconnect
        if (monitorRunning) stopMonitor()
        serial.disconnect()
        ifaces.clear()
        radioInfo.clear()
    }

    /** Call `info` to identify the device, then enumerate interfaces/profiles. */
    fun verify() = scope {
        val di = fw.identify()
        deviceInfo = di
        val (ok, list) = if (di != null) di.isWizzard to di.interfaces else fw.verify()
        ifaces.clear(); ifaces.addAll(list)
        if (list.isNotEmpty() && ifaces.none { it.name == selectedIface }) {
            selectedIface = list.first().name
        }
        conn = if (ok) ConnState.VERIFIED else ConnState.WRONG_DEVICE
        if (ok) {
            val pl = fw.profileList(); profiles.clear(); profiles.addAll(pl)
        }
    }

    // ---- cc1101 / radio ---------------------------------------------------

    fun loadInfo() = scope {
        val m = fw.info(selectedIface)
        radioInfo.clear(); radioInfo.putAll(m)
    }

    fun setParam(name: String, value: String) = scope {
        fw.setParam(selectedIface, name, value)
        val m = fw.info(selectedIface); radioInfo.clear(); radioInfo.putAll(m)
    }

    fun setMod(mod: String) = scope {
        fw.setMod(selectedIface, mod)
        val m = fw.info(selectedIface); radioInfo.clear(); radioInfo.putAll(m)
    }

    fun radioAction(action: String) = scope {
        when (action) {
            "rx" -> fw.rx(selectedIface)
            "idle" -> fw.idle(selectedIface)
            "reset" -> fw.reset(selectedIface)
        }
    }

    // ---- profiles ---------------------------------------------------------

    fun loadProfiles() = scope {
        val list = fw.profileList()
        profiles.clear(); profiles.addAll(list)
    }

    fun openProfile(name: String) = scope {
        selectedProfile = name
        profileEditorName = name
        val lines = fw.profileShow(name)
        profileEditorText = lines.joinToString("\n")
    }

    fun newProfileDraft() {
        selectedProfile = null
        profileEditorName = ""
        profileEditorText = "set mod ook\nset freq 433920000\nset rxbw 203000\nset drate 5000\nrx"
    }

    fun saveProfile() = scope {
        val name = profileEditorName.trim()
        if (name.isEmpty()) return@scope
        val lines = profileEditorText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        fw.profileWrite(name, lines)
        loadProfilesSync()
        selectedProfile = name
    }

    fun deleteProfile(name: String) = scope {
        fw.profileDel(name)
        loadProfilesSync()
        if (selectedProfile == name) { selectedProfile = null; profileEditorText = ""; profileEditorName = "" }
    }

    fun applyProfile(name: String) = scope { fw.profileApply(name, selectedIface); loadInfoSync() }

    private suspend fun loadProfilesSync() {
        val list = fw.profileList(); profiles.clear(); profiles.addAll(list)
    }
    private suspend fun loadInfoSync() {
        val m = fw.info(selectedIface); radioInfo.clear(); radioInfo.putAll(m)
    }

    // ---- monitor ----------------------------------------------------------

    fun startMonitor(profile: String) = scope {
        val res = fw.monitorStart(selectedIface, profile, json = true,
            minUs = captureMinUs, maxGapUs = captureGapUs)
        monitorRunning = res.ok
        engine.blockWhileMonitor = monitorRunning   // lock out other commands while capturing
    }

    fun stopMonitor() = scope {
        fw.monitorStop()
        monitorRunning = false
        engine.blockWhileMonitor = false
    }

    // ---- interpretation ---------------------------------------------------

    /** Called when a monitor log line is tapped in the main tab. */
    fun interpret(packet: CapturedPacket) {
        composeId = null                 // packet view takes over from compose
        selectedPacket = packet
        results.clear()
        results.addAll(AnalyzerRegistry.analyze(packet))
        selectResult(results.firstOrNull())
        selectedTab = 1
    }

    fun selectResult(r: AnalysisResult?) {
        selectedResult = r
        editedValues.clear()
        r?.fields?.forEach { editedValues[it.key] = it.value }
    }

    fun updateField(key: String, value: String) { editedValues[key] = value }

    private fun currentFields(): List<ProtoField> =
        selectedResult?.fields?.map { it.copy(value = editedValues[it.key] ?: it.value) } ?: emptyList()

    fun transmitEdited() = scope {
        val r = selectedResult ?: return@scope
        val frame = r.encode(currentFields()) ?: run {
            engine.log(LogKind.ERROR, "cannot build frame from fields")
            return@scope
        }
        applyRadioForTx(frame)
        transmit(frame)
    }

    /**
     * Replay the EXACT captured pulse-train (OOK), bypassing field reconstruction.
     * The most faithful replay for fixed-code protocols (EV1527/PT2262); honours
     * the TX repeat/gap. Note: for rolling codes (HCS/KeeLoq) a real receiver
     * tracks the counter and rejects a replayed old frame by design.
     */
    fun transmitRawCapture() = scope {
        val p = selectedPacket
        if (p !is CapturedPacket.Ook) { txError = str(R.string.vm_raw_ook_only); return@scope }
        val frame = TxFrame.OokPulses(p.us)
        applyRadioForTx(frame)
        transmit(frame)
    }

    /**
     * Put the radio into the right state for a replay: OOK modulation for pulse
     * frames, and the TX frequency, before sending. Without this a captured OOK
     * code could be transmitted in whatever modulation the radio was last left in
     * (so other receivers wouldn't recognise it). Mirrors [transmitCompose]/[sendTx].
     */
    private suspend fun applyRadioForTx(frame: TxFrame) {
        if (frame is TxFrame.OokPulses) fw.setMod(selectedIface, "ook")
        fw.setParam(selectedIface, "freq", txFreqHz.toString())
    }

    /**
     * Send a frame honouring the TX repeat count (1..100) and inter-frame gap.
     * OOK pulse frames use the firmware's on-device repeat/gap (precise µs timing
     * in a single `pulses play` command); FSK packet frames are repeated host-side
     * (the firmware `send` has no repeat), with a best-effort gap between them.
     */
    private suspend fun transmit(frame: TxFrame) {
        val n = txRepeat.coerceIn(1, 100)
        when (frame) {
            is TxFrame.OokPulses -> fw.sendPulses(selectedIface, frame.us, n, txGapMs.toLong() * 1000L)
            is TxFrame.FskHex -> {
                for (i in 0 until n) {
                    fw.sendHex(selectedIface, frame.hex)
                    if (i < n - 1 && txGapMs > 0) kotlinx.coroutines.delay(txGapMs.toLong())
                }
            }
        }
    }

    fun savePacket(name: String) {
        val r = selectedResult ?: return
        val gps = if (gpsEnabled) location.lastKnown()?.let {
            PacketStore.Gps(it.lat, it.lon, it.timeMs, it.provider, it.accuracyM)
        } else null
        if (gpsEnabled && gps == null) {
            engine.log(LogKind.INFO, str(R.string.vm_gps_no_fix))
        }
        store.save(name, r, currentFields(), gps)
        refreshSaved()
    }

    fun refreshSaved() {
        savedPackets.clear(); savedPackets.addAll(store.list())
    }

    fun deleteSaved(saved: PacketStore.Saved) {
        store.delete(saved.file); refreshSaved()
    }

    // ---- export saved packets outside the app's private storage ----------
    /** The packet queued for export; set just before launching the folder picker. */
    var pendingExport: PacketStore.Saved? = null

    /**
     * Copy the queued saved packet (its .json and the .txt sidecar) into the
     * SAF tree the user picked, so it leaves the app's private `data/` directory.
     */
    fun exportTo(treeUri: android.net.Uri) = scope {
        val s = pendingExport ?: return@scope
        pendingExport = null
        val app = getApplication<Application>()
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, treeUri)
        if (tree == null || !tree.canWrite()) { engine.log(LogKind.ERROR, str(R.string.vm_export_failed)); return@scope }
        runCatching {
            val base = s.file.nameWithoutExtension
            tree.createFile("application/json", "$base.json")?.let { doc ->
                app.contentResolver.openOutputStream(doc.uri)?.use { it.write(s.file.readBytes()) }
            } ?: throw java.io.IOException("createFile json")
            val txt = java.io.File(s.file.parentFile, "$base.txt")
            if (txt.exists()) tree.createFile("text/plain", "$base.txt")?.let { doc ->
                app.contentResolver.openOutputStream(doc.uri)?.use { it.write(txt.readBytes()) }
            }
            engine.log(LogKind.INFO, str(R.string.vm_export_ok, base))
        }.onFailure { engine.log(LogKind.ERROR, str(R.string.vm_export_failed)) }
    }

    fun transmitSaved(saved: PacketStore.Saved) = scope {
        val frame = SavedEncoder.encode(saved.analyzerId, saved.fields) ?: run {
            engine.log(LogKind.ERROR, "cannot rebuild frame for ${saved.name}")
            return@scope
        }
        applyRadioForTx(frame)
        transmit(frame)
    }

    fun clearLogs() = logs.clear()

    /**
     * Save the current log (each line prefixed with its `[HH:mm:ss.SSS]`
     * timestamp) as a .txt file in the SAF folder the user picked — same
     * mechanism as exporting saved packets, so it leaves the app's storage.
     */
    fun saveLogsTo(treeUri: android.net.Uri) = scope {
        if (logs.isEmpty()) { engine.log(LogKind.INFO, str(R.string.vm_log_empty)); return@scope }
        val app = getApplication<Application>()
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, treeUri)
        if (tree == null || !tree.canWrite()) { engine.log(LogKind.ERROR, str(R.string.vm_log_save_failed)); return@scope }
        runCatching {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val name = "cc1101-log-$stamp.txt"
            val text = logs.joinToString("\n") { "[${logTimestamp(it.timestampMs)}] ${it.text}" } + "\n"
            tree.createFile("text/plain", name)?.let { doc ->
                app.contentResolver.openOutputStream(doc.uri)?.use { it.write(text.toByteArray()) }
            } ?: throw java.io.IOException("createFile")
            engine.log(LogKind.INFO, str(R.string.vm_log_saved, name))
        }.onFailure { engine.log(LogKind.ERROR, str(R.string.vm_log_save_failed)) }
    }

    // ---- file browser (TX) : File mode + SAF document-tree mode ----------
    val browseItems: SnapshotStateList<BrowseItem> = mutableStateListOf()
    var browsePath by mutableStateOf("data/"); private set
    var browseError by mutableStateOf<String?>(null); private set
    private var browseSaf = false
    private var browseFileDir: java.io.File? = null
    private val safStack = ArrayDeque<androidx.documentfile.provider.DocumentFile>()
    private var safChildren: List<androidx.documentfile.provider.DocumentFile> = emptyList()

    fun browseRoots(): List<java.io.File> = sampleRoots()

    fun initBrowse() {
        if (browseItems.isEmpty() && !browseSaf) openBrowseFileDir(store.dir)
    }

    fun openBrowseFileDir(dir: java.io.File) {
        browseSaf = false
        safStack.clear()
        browseFileDir = dir
        browsePath = dir.absolutePath
        browseError = null
        browseItems.clear()
        val children = dir.listFiles()
        if (children == null) {
            browseError = str(R.string.vm_dir_unreadable)
            return
        }
        children.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { f ->
                browseItems.add(BrowseItem(f.name, f.isDirectory, f.name.endsWith(".sub", true), file = f))
            }
    }

    /** Open a SAF document tree (works on all Android versions, incl. 13+). */
    fun openBrowseTree(uri: android.net.Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri)
        if (root == null) { browseError = str(R.string.vm_cannot_open_folder); return }
        browseSaf = true
        safStack.clear()
        safStack.addLast(root)
        listSaf()
    }

    private fun listSaf() {
        val doc = safStack.lastOrNull() ?: return
        browseError = null
        browseItems.clear()
        val children = doc.listFiles().toList()
            .sortedWith(compareByDescending<androidx.documentfile.provider.DocumentFile> { it.isDirectory }
                .thenBy { (it.name ?: "").lowercase() })
        safChildren = children
        browsePath = safStack.mapNotNull { it.name }.joinToString("/").ifEmpty { "(folder)" }
        children.forEachIndexed { i, c ->
            val nm = c.name ?: "?"
            browseItems.add(BrowseItem(nm, c.isDirectory, nm.endsWith(".sub", true), uri = c.uri, idx = i))
        }
    }

    fun browseInto(item: BrowseItem) {
        if (!item.isDir) return
        if (browseSaf) {
            val child = safChildren.getOrNull(item.idx) ?: return
            safStack.addLast(child)
            listSaf()
        } else {
            item.file?.let { openBrowseFileDir(it) }
        }
    }

    fun browseUp() {
        if (browseSaf) {
            if (safStack.size > 1) { safStack.removeLast(); listSaf() }
            else openBrowseFileDir(store.dir)   // leave the tree, back to app data/
        } else {
            browseFileDir?.parentFile?.let { openBrowseFileDir(it) }
        }
    }

    fun loadBrowseItem(item: BrowseItem) {
        if (item.isDir) { browseInto(item); return }
        when {
            item.file != null -> loadTxFromFile(item.file)
            item.uri != null -> loadTxFromUri(item.uri)
        }
    }

    // ---- samples / Flipper .sub (legacy helpers, still used for roots) ----

    /** Directories offered as browser starting points. */
    fun sampleRoots(): List<java.io.File> {
        val app = getApplication<Application>()
        val roots = ArrayList<java.io.File>()
        roots.add(store.dir)                                   // filesDir/data (captured signals)
        app.getExternalFilesDir(null)?.let { roots.add(it) }   // app external files
        @Suppress("DEPRECATION")
        android.os.Environment.getExternalStorageDirectory()?.let { if (it.canRead()) roots.add(it) }
        return roots.distinctBy { it.absolutePath }
    }

    fun openSamplesDir(dir: java.io.File) {
        samplesDir = dir
        sampleError = null
        sampleEntries.clear()
        val children = dir.listFiles()
        if (children == null) {
            sampleError = "Katalog nieczytelny (uprawnienia): ${dir.absolutePath}"
            return
        }
        val sorted = children.sortedWith(compareByDescending<java.io.File> { it.isDirectory }.thenBy { it.name.lowercase() })
        sampleEntries.addAll(sorted)
    }

    fun samplesUp() {
        samplesDir?.parentFile?.let { openSamplesDir(it) }
    }

    fun initSamples() {
        if (samplesDir == null) openSamplesDir(store.dir)
    }

    fun loadFlipperFile(file: java.io.File) {
        runCatching { file.readText() }
            .onSuccess { parseSample(file.name, it) }
            .onFailure { sampleError = str(R.string.vm_cannot_read_file, it.message ?: "") }
    }

    fun loadFlipperUri(uri: android.net.Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
        }.onSuccess { text ->
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "flipper.sub"
            parseSample(name, text)
        }.onFailure { sampleError = str(R.string.vm_cannot_read_file, it.message ?: "") }
    }

    private fun parseSample(name: String, text: String) {
        val s = com.wizzard.cc1101.data.FlipperSample.parse(name, text)
        if (s == null) {
            sampleError = "To nie jest rozpoznawalny plik Flipper .sub: $name"
            currentSample = null
        } else {
            sampleError = null
            currentSample = s
        }
    }

    /** Feed the current sample's pulse-train into the interpretation tab. */
    fun interpretSample() {
        val s = currentSample ?: return
        val us = s.toPulses() ?: run { sampleError = str(R.string.vm_no_pulses_in_sample); return }
        interpret(CapturedPacket.Ook("sample", System.currentTimeMillis(), 0, 0, us, "flipper:${s.name}"))
    }

    /** Set frequency + OOK on the radio (from the sample) and replay its pulses. */
    fun transmitSample(applyRadio: Boolean) = scope {
        val s = currentSample ?: return@scope
        val us = s.toPulses() ?: run { sampleError = str(R.string.vm_no_pulses_in_sample); return@scope }
        if (applyRadio && s.frequencyHz > 0) {
            fw.setMod(selectedIface, "ook")
            fw.setParam(selectedIface, "freq", s.frequencyHz.toString())
        }
        fw.sendPulses(selectedIface, us)
    }

    // ---- helpers ----------------------------------------------------------

    private fun scope(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy = true
            try { block() } finally { busy = false }
        }
    }

    override fun onCleared() {
        serial.disconnect()
        super.onCleared()
    }
}

/** One entry in the TX file browser (File mode or SAF document-tree mode). */
data class BrowseItem(
    val name: String,
    val isDir: Boolean,
    val isSub: Boolean,
    val file: java.io.File? = null,
    val uri: android.net.Uri? = null,
    val idx: Int = -1,
)

/** A signal staged for transmission in the TX tab. */
data class TxSignal(
    val label: String,
    val frame: TxFrame,
    val pulses: IntArray?,   // present for OOK signals (for the waveform preview)
    val hex: String?,        // present for packet-mode (FSK) signals
    val freqHz: Long?,       // suggested frequency from the source, if any
) {
    override fun equals(other: Any?) = other is TxSignal && label == other.label && frame == other.frame
    override fun hashCode() = label.hashCode() * 31 + frame.hashCode()
}
