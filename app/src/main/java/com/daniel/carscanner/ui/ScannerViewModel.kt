package com.daniel.carscanner.ui

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daniel.carscanner.bt.BleTransport
import com.daniel.carscanner.bt.ClassicTransport
import com.daniel.carscanner.bt.ObdTransport
import com.daniel.carscanner.obd.ActiveTest
import com.daniel.carscanner.obd.ActiveTestStore
import com.daniel.carscanner.obd.ElmClient
import com.daniel.carscanner.obd.ElmTimeout
import com.daniel.carscanner.obd.Finding
import com.daniel.carscanner.obd.HealthCheck
import com.daniel.carscanner.obd.Level
import com.daniel.carscanner.obd.Module
import com.daniel.carscanner.obd.ModuleAddress
import com.daniel.carscanner.obd.ModuleScanner
import com.daniel.carscanner.obd.Modules
import com.daniel.carscanner.obd.DiagProtocol
import com.daniel.carscanner.obd.Vin
import com.daniel.carscanner.obd.DtcLibrary
import com.daniel.carscanner.net.Nhtsa
import com.daniel.carscanner.net.Recall
import com.daniel.carscanner.net.VehicleInfo
import com.daniel.carscanner.data.GarageCar
import com.daniel.carscanner.data.CodeWatch
import com.daniel.carscanner.data.ReturnedCode
import com.daniel.carscanner.data.FuelMath
import com.daniel.carscanner.data.TripMeter
import com.daniel.carscanner.data.TripRecord
import com.daniel.carscanner.alerts.Notifier
import com.daniel.carscanner.widget.CarWidget
import com.daniel.carscanner.data.GarageJson
import com.daniel.carscanner.data.HistoryEntry
import com.daniel.carscanner.data.HistoryStore
import com.daniel.carscanner.data.MaintItem
import com.daniel.carscanner.data.Maintenance
import com.daniel.carscanner.obd.BlinkTracker
import com.daniel.carscanner.obd.BodySignal
import com.daniel.carscanner.obd.BodySignalStore
import com.daniel.carscanner.obd.DataSource
import com.daniel.carscanner.BuildConfig
import com.daniel.carscanner.net.UpdateInfo
import com.daniel.carscanner.net.Updater
import com.daniel.carscanner.obd.Learn
import com.daniel.carscanner.obd.LearnStep
import com.daniel.carscanner.obd.CarState
import com.daniel.carscanner.obd.Element
import com.daniel.carscanner.obd.Readiness
import com.daniel.carscanner.obd.Pid
import com.daniel.carscanner.obd.Visuals
import com.daniel.carscanner.obd.WatchTracker
import com.daniel.carscanner.obd.Hex
import com.daniel.carscanner.obd.Pids
import com.daniel.carscanner.obd.Risk
import com.daniel.carscanner.obd.Safety
import com.daniel.carscanner.obd.Uds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/*
 * WHAT THIS FILE DOES
 * The "control center" of the app. Screens never talk to Bluetooth or the car
 * directly; they call functions here (connect, readCodes, runHealthCheck...)
 * and display the values stored here. Each value is a "flow": when it changes,
 * any screen showing it redraws automatically.
 */

/** One Bluetooth device in the Connect list. */
data class DeviceItem(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val ble: Boolean,
    val paired: Boolean,
) {
    val looksLikeObd: Boolean
        get() = listOf("OBD", "ELM", "LINK", "VGATE", "VEEPEAK", "ICAR", "KONNWEI", "CAR")
            .any { name.uppercase().contains(it) }
}

/** The four possible connection states shown at the top of the app. */
sealed interface Conn {
    data object Disconnected : Conn
    data class Connecting(val name: String) : Conn
    data class Connected(val name: String, val adapterVersion: String, val protocol: String) : Conn
    data class Failed(val reason: String) : Conn
}

@SuppressLint("MissingPermission")
class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val store = ActiveTestStore(File(app.filesDir, "active_tests.json"))
    private var transport: ObdTransport? = null
    private var elm: ElmClient? = null
    private var pollJob: Job? = null
    private var testJob: Job? = null

    val conn = MutableStateFlow<Conn>(Conn.Disconnected)
    val devices = MutableStateFlow<List<DeviceItem>>(emptyList())
    /** The device a failed connection was for, so "Try again" knows what to retry. */
    var lastAttempt: DeviceItem? = null; private set
    val scanning = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow<String?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())

    val vin = MutableStateFlow<String?>(null)

    // Automatic live-view setup (declared before init, which loads the last car)
    /** Data blocks found in the current car's modules (saved per car). */
    val learnSources = MutableStateFlow<List<DataSource>>(emptyList())
    /** What setup is doing right now ("Reading module 750/40…"), or null when idle. */
    val learnStatus = MutableStateFlow<String?>(null)
    val learnProgress = MutableStateFlow(0f)
    /** The part being learned and which instruction is showing (0 = off, 1 = on, 2 = off again). */
    val learnActive = MutableStateFlow<Pair<LearnStep, Int>?>(null)
    /** The last result: step id to a message ("Found it" or why not). */
    val learnResult = MutableStateFlow<Pair<String, String>?>(null)
    private var learnJob: Job? = null
    private val learnReads = mutableMapOf<Int, MutableList<Map<DataSource, List<Int>>>>()
    val supported = MutableStateFlow<Set<Int>>(emptySet())
    val selectedPids = MutableStateFlow(setOf(0x0C, 0x05, 0x42, 0x0D, 0x11, 0x04))
    val live = MutableStateFlow<Map<Int, Double>>(emptyMap())
    /** Recent readings per sensor for the live graphs (oldest first, last ~2 minutes). */
    val history = MutableStateFlow<Map<Int, List<Float>>>(emptyMap())
    val polling = MutableStateFlow(false)

    val milOn = MutableStateFlow<Boolean?>(null)
    val storedCodes = MutableStateFlow<List<String>>(emptyList())
    val pendingCodes = MutableStateFlow<List<String>>(emptyList())
    val permanentCodes = MutableStateFlow<List<String>>(emptyList())
    val codesRead = MutableStateFlow(false)

    val tests = MutableStateFlow(store.load())
    val runningTest = MutableStateFlow<String?>(null)
    val testStatus = MutableStateFlow("")
    val expertMode = MutableStateFlow(false)

    // Health check results
    val healthFindings = MutableStateFlow<List<Finding>>(emptyList())
    val healthProgress = MutableStateFlow<Float?>(null)   // null = not running, 0..1 = progress
    val voltage = MutableStateFlow<Double?>(null)           // battery voltage for the Home screen
    private var healthJob: Job? = null

    // All-module scan
    val modules = MutableStateFlow<List<Module>>(emptyList())
    val moduleScanStatus = MutableStateFlow<String?>(null)   // non-null while a scan runs
    val moduleProgress = MutableStateFlow(0f)
    val modulesScanned = MutableStateFlow(false)
    private var moduleJob: Job? = null
    private val moduleNames = app.getSharedPreferences("module_names", 0)   // your renames

    // Vehicle details, recalls and drawing (from NHTSA; cached on the phone so they show offline)
    private val prefs = app.getSharedPreferences("vehicle", 0)
    /** The adapter you connected to last time, so Connect can offer one-tap Reconnect. */
    val lastAdapter = MutableStateFlow(prefs.getString("last_adapter", null))
    val vehicle = MutableStateFlow<VehicleInfo?>(null)
    val recalls = MutableStateFlow<List<Recall>>(emptyList())
    val vehicleStatus = MutableStateFlow<String?>(null)        // "Looking up…" or an error, else null
    val paint = MutableStateFlow(DEFAULT_PAINT)                 // chosen paint color for the drawing
    private var vehicleJob: Job? = null

    // What the car drawing shows right now (from live view, active tests and previews)
    val carState = MutableStateFlow(CarState())
    // Set by the Vehicle screen's "Set up" buttons so the Tests screen opens a pre-filled form
    val testDraftVisual = MutableStateFlow<String?>(null)

    // Garage: every car you've connected to or looked up
    val garage = MutableStateFlow(GarageJson.fromJson(prefs.getString("garage", null)))

    // Live view: reads your body signals over and over to mirror the real car
    private val signalStore = BodySignalStore(File(app.filesDir, "body_signals.json"))
    val allSignals = MutableStateFlow(signalStore.load())
    val liveView = MutableStateFlow(false)
    val liveViewPaused = MutableStateFlow(false)    // paused while Console or Watch is open
    private var liveJob: Job? = null

    // Watch tool: shows a reply's bytes live and highlights what changes
    val watchBytes = MutableStateFlow<List<Int>>(emptyList())
    val watchChanged = MutableStateFlow<Set<Int>>(emptySet())
    val watchBits = MutableStateFlow<Map<Int, Set<Int>>>(emptyMap())
    val watchError = MutableStateFlow<String?>(null)
    private var watchJob: Job? = null

    // Scan history, maintenance, inspection readiness, freeze frame
    private val historyStore = HistoryStore(File(app.filesDir, "history.json"))
    val history = MutableStateFlow(historyStore.load())
    val maintenance = MutableStateFlow<List<MaintItem>>(emptyList())
    val readiness = MutableStateFlow<Readiness?>(null)
    val freezeFrame = MutableStateFlow<Pair<String, List<Pair<Pid, Double>>>?>(null)
    /** Mode 06 self-test results, null until read. */
    val monitorTests = MutableStateFlow<List<com.daniel.carscanner.obd.MonitorTest>?>(null)
    val monitorProgress = MutableStateFlow<Float?>(null)

    // Codes that came back after you cleared them (shown on Home and Codes until dismissed)
    val returnedCodes = MutableStateFlow<List<ReturnedCode>>(emptyList())

    // Trips and MPG
    val trips = MutableStateFlow<List<TripRecord>>(emptyList())
    val tripLive = MutableStateFlow<TripMeter?>(null)          // the trip in progress, or null
    val tripTick = MutableStateFlow(0L)                        // changes every reading so the screen refreshes
    val fuelPrice = MutableStateFlow(prefs.getString("fuel_price", null)?.toDoubleOrNull())
    private var tripJob: Job? = null

    // Recall alerts on/off (the daily background check reads the same setting)
    val recallAlerts = MutableStateFlow(prefs.getBoolean("recall_alerts", true))

    // Set when a notification or the widget asks to open a certain car and screen
    val openRequest = MutableStateFlow<Pair<String?, String>?>(null)

    // Appearance setting: System, Light or Dark
    val themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", "System") ?: "System") }.getOrDefault(ThemeMode.System)
    )

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    init {
        // Load the code library in the background, and show the last car right away (even offline).
        viewModelScope.launch(Dispatchers.IO) { DtcLibrary.load(app) }
        prefs.getString("last_vin", null)?.let { setVin(it) }
    }

    // ---------- Devices ----------

    /** Rebuilds the device list from adapters already paired in Android settings. */
    fun refreshDevices() {
        val bonded = try {
            adapter?.bondedDevices.orEmpty().map { d ->
                DeviceItem(d, d.name ?: d.address, d.address, d.type == BluetoothDevice.DEVICE_TYPE_LE, paired = true)
            }
        } catch (_: SecurityException) { emptyList() }
        devices.update { old ->
            val scanned = old.filter { !it.paired && bonded.none { b -> b.address == it.address } }
            sort(bonded + scanned)
        }
    }

    /** Puts devices that look like OBD adapters at the top. */
    private fun sort(list: List<DeviceItem>) = list.sortedWith(compareByDescending<DeviceItem> { it.looksLikeObd }.thenBy { it.name })

    /** Called by Android for every BLE device found during a scan. */
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = (try { d.name } catch (_: SecurityException) { null }) ?: result.scanRecord?.deviceName ?: return
            devices.update { old ->
                if (old.any { it.address == d.address && it.ble }) old
                else sort(old + DeviceItem(d, name, d.address, ble = true, paired = false))
            }
        }
    }

    /** Scans for BLE adapters for 12 seconds. */
    fun startBleScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !bluetoothOn) { message.value = "Turn on Bluetooth first."; return }
        if (scanning.value) return
        try { scanner.startScan(scanCallback) } catch (_: SecurityException) {
            message.value = "Allow the Bluetooth permission to scan."; return
        }
        scanning.value = true
        viewModelScope.launch { delay(12_000); stopBleScan() }
    }

    fun stopBleScan() {
        if (!scanning.value) return
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        scanning.value = false
    }

    // ---------- Connection ----------

    /**
     * Connects to the chosen adapter: opens Bluetooth, resets the adapter,
     * finds the car's protocol, then reads which sensors it supports, the VIN
     * and the battery voltage.
     */
    fun connect(item: DeviceItem) {
        if (conn.value is Conn.Connecting || conn.value is Conn.Connected) return
        stopBleScan()
        lastAttempt = item
        viewModelScope.launch {
            conn.value = Conn.Connecting(item.name)
            val t = if (item.ble) BleTransport(getApplication(), item.device) else ClassicTransport(item.device, adapter)
            try {
                t.connect()
                val client = ElmClient(t, ::appendLog)
                val version = client.initialize()
                transport = t
                elm = client
                conn.value = Conn.Connected(item.name, version, client.protocolName)
                prefs.edit().putString("last_adapter", item.address).apply()
                lastAdapter.value = item.address
                supported.value = runCatching { client.supportedPids() }.getOrDefault(emptySet())
                runCatching { client.readVin() }.getOrNull()?.let { setVin(it) }
                markSeen()   // update "last connected" in the garage
                voltage.value = runCatching { parseVolts(client.batteryVoltage()) }.getOrNull()
                // Newer cars report their odometer; save it for the maintenance tracker
                if (0xA6 in supported.value) runCatching { client.readPid(Pids.byCode[0xA6]!!) }.getOrNull()?.let { setOdometer(it.toInt(), fromCar = true) }
            } catch (e: CancellationException) {
                t.close(); throw e
            } catch (e: Exception) {
                t.close()
                conn.value = Conn.Failed(e.message ?: "Connection failed")
            }
        }
    }

    /** Stops everything and closes the Bluetooth link. */
    fun disconnect() {
        stopPolling()
        setLiveView(false)
        watchJob?.cancel()
        stopTrip()
        testJob?.cancel()
        healthJob?.cancel()
        moduleJob?.cancel()
        transport?.close()
        transport = null
        elm = null
        expertMode.value = false   // never left armed for the next connection (or the next car)
        conn.value = Conn.Disconnected
        live.value = emptyMap()
    }

    /** Called when the adapter drops out mid-task (unplugged, out of range). */
    private fun onLinkLost(e: IOException) {
        disconnect()
        conn.value = Conn.Failed("Connection lost: ${e.message}")
    }

    /** Adds a line to the Console log, keeping the last 600 lines. */
    private fun appendLog(line: String) {
        log.update { (it + line).takeLast(600) }
    }

    fun clearLog() { log.value = emptyList() }

    /**
     * Runs a car task in the background, shows a spinner while it runs, and turns
     * errors into friendly messages. Only one of these runs at a time.
     */
    private fun launchBusy(label: String, block: suspend (ElmClient) -> Unit) {
        val e = elm ?: run { message.value = "Connect to your adapter first."; return }
        if (busy.value != null) return
        viewModelScope.launch {
            busy.value = label
            try { block(e) }
            catch (x: ElmTimeout) { message.value = "The car didn't answer in time. Try again." }
            catch (x: IOException) { onLinkLost(x) }
            finally { busy.value = null }
        }
    }

    // ---------- Live data ----------

    /** Adds or removes a gauge from the Live screen. */
    fun togglePid(code: Int) {
        selectedPids.update { if (code in it) it - code else it + code }
    }

    /** Reads the chosen gauges over and over until stopped. */
    fun startPolling() {
        val e = elm ?: return
        if (pollJob != null) return
        pollJob = viewModelScope.launch {
            polling.value = true
            try {
                while (isActive) {
                    for (code in selectedPids.value.toList()) {
                        val pid = Pids.byCode[code] ?: continue
                        if (supported.value.isNotEmpty() && code !in supported.value) continue
                        try {
                            e.readPid(pid)?.let { v ->
                                live.update { it + (code to v) }
                                history.update { h -> h + (code to ((h[code] ?: emptyList()) + v.toFloat()).takeLast(HISTORY_SAMPLES)) }
                            }
                        } catch (_: ElmTimeout) { }
                    }
                    delay(50)
                }
            } catch (x: IOException) {
                onLinkLost(x)
            } finally {
                polling.value = false
                pollJob = null
            }
        }
    }

    fun stopPolling() { pollJob?.cancel() }

    // ---------- Trouble codes ----------

    /** Reads the check engine light state and all three kinds of trouble codes. */
    fun readCodes() = launchBusy("Reading codes") { e ->
        e.monitorStatus()?.let { (mil, _) -> milOn.value = mil }
        storedCodes.value = e.readCodes("03")
        pendingCodes.value = e.readCodes("07")
        permanentCodes.value = try { e.readCodes("0A") } catch (_: ElmTimeout) { emptyList() }
        readiness.value = try { e.readiness() } catch (_: ElmTimeout) { null }
        freezeFrame.value = if (storedCodes.value.isEmpty()) null else try { e.freezeFrame() } catch (_: ElmTimeout) { null }
        codesRead.value = true
        recordCodes()
        checkReturned(CodeWatch.ENGINE, "Engine computer", storedCodes.value + pendingCodes.value)
        val st = storedCodes.value; val pe = pendingCodes.value
        writeWidgetStatus(
            when { st.isNotEmpty() -> Level.Problem; pe.isNotEmpty() -> Level.Watch; else -> Level.Good },
            when { st.isNotEmpty() -> "${st.size} stored code${if (st.size > 1) "s" else ""}"; pe.isNotEmpty() -> "${pe.size} pending code${if (pe.size > 1) "s" else ""}"; else -> "No trouble codes" },
        )
    }

    /** Clears codes, then re-reads them so you can see what came back. */
    fun clearCodes() = launchBusy("Clearing codes") { e ->
        val before = (storedCodes.value + pendingCodes.value).ifEmpty { e.readCodes("03") + e.readCodes("07") }
        val ok = e.clearCodes()
        if (ok) rememberCleared(CodeWatch.ENGINE, before)
        message.value = if (ok) "Codes cleared." else "The car refused to clear codes. Turn the engine off, ignition on, and try again."
        e.monitorStatus()?.let { (mil, _) -> milOn.value = mil }
        storedCodes.value = e.readCodes("03")
        pendingCodes.value = e.readCodes("07")
        writeWidgetStatus(if (storedCodes.value.isEmpty()) Level.Good else Level.Problem,
            if (storedCodes.value.isEmpty()) "Codes cleared" else "${storedCodes.value.size} codes still stored")
    }

    // ---------- Active tests ----------

    /** Adds a new test or replaces an edited one, and saves to the phone. */
    fun saveTest(t: ActiveTest) {
        problemWith(t)?.let { message.value = it; return }
        val owned = if (t.vin.isBlank()) t.copy(vin = vin.value ?: "") else t
        tests.update { list -> (list.filter { it.id != owned.id } + owned).also(store::save) }
    }

    /** Removes a test and saves. */
    fun deleteTest(id: String) {
        tests.update { list -> list.filter { it.id != id }.also(store::save) }
    }

    /**
     * Runs an active test:
     *  1. points the adapter at the test's module (ATSH)
     *  2. sends the start commands; stops early if the module refuses
     *  3. keeps the session alive every 2 s until the time limit or you press Stop
     *  4. ALWAYS sends the stop commands at the end, even after an error
     */
    fun runTest(t: ActiveTest) {
        val e = elm ?: run { message.value = "Connect to your adapter first."; return }
        if (testJob != null) return
        problemWith(t)?.let { message.value = it; return }
        val blocked = (t.startCommands + t.stopCommands + t.keepAlive).filter { it.isNotBlank() }
            .any { Safety.classify(it) == Risk.Dangerous }
        if (blocked && !expertMode.value) {
            message.value = "This test contains a command that can reset or reprogram a module. Turn on Expert mode in Console to allow it."
            return
        }
        stopPolling()
        testJob = viewModelScope.launch {
            runningTest.value = t.id
            testStatus.value = "Starting…"
            try { e.exclusive {
                if (t.header.isNotBlank()) e.send("ATSH" + t.header.replace(" ", ""))
                for (cmd in t.startCommands) {
                    val r = e.send(cmd)
                    testStatus.value = Uds.describe(r)
                    if (Uds.negative(r) != null || "NO DATA" in r.uppercase()) return@exclusive
                }
                showVisual(t.visual, true)   // animate the car drawing to match
                val endAt = System.currentTimeMillis() + t.maxSeconds * 1000L
                while (isActive && System.currentTimeMillis() < endAt) {
                    testStatus.value = "Running, stops in ${(endAt - System.currentTimeMillis() + 999) / 1000} s"
                    delay(2_000)
                    if (t.keepAlive.isNotBlank()) e.send(t.keepAlive, quiet = true)
                }
            } } catch (x: IOException) {
                testStatus.value = "Error: ${x.message}"
            } finally {
                withContext(NonCancellable) {
                    e.exclusive {
                        for (cmd in t.stopCommands) runCatching { e.send(cmd) }
                        runCatching { e.send(e.resetHeaderCommand()) }
                    }
                }
                showVisual(t.visual, false)
                if (!testStatus.value.startsWith("Rejected") && !testStatus.value.startsWith("No reply") &&
                    !testStatus.value.startsWith("Error")) testStatus.value = "Stopped"
                runningTest.value = null
                testJob = null
            }
        }
    }

    fun stopTest() { testJob?.cancel() }

    /** Why a test can't be saved or run (bad module address, hidden characters), or null if it's fine. */
    private fun problemWith(t: ActiveTest): String? =
        Safety.testProblem(t.header, (t.startCommands + t.stopCommands + t.keepAlive).filter { it.isNotBlank() }, t.maxSeconds)

    // ---------- Health check ----------

    /**
     * The one-tap health check. Takes about 20 seconds:
     *  1. Reads general sensors 4 times (temps, voltage, fuel trims, rpm...)
     *  2. Records oxygen sensors rapidly for 12 seconds to see how they switch
     *  3. Reads trouble codes
     *  4. Hands everything to HealthCheck.evaluate() for green/yellow/red findings
     * Best results: engine fully warm, idling in Park, A/C off.
     */
    fun runHealthCheck() {
        val e = elm ?: run { message.value = "Connect to your adapter first."; return }
        if (healthJob != null || busy.value != null) return
        stopPolling()
        healthJob = viewModelScope.launch {
            val samples = mutableMapOf<Int, MutableList<Double>>()
            fun add(code: Int, v: Double) { samples.getOrPut(code) { mutableListOf() }.add(v) }
            val sup = supported.value
            fun has(code: Int) = sup.isEmpty() || code in sup
            try {
                healthProgress.value = 0.02f

                // Step 1: general sensors, 4 rounds
                val general = HealthCheck.generalPids.filter(::has)
                repeat(4) { round ->
                    for (code in general) {
                        val pid = Pids.byCode[code] ?: continue
                        try { e.readPid(pid)?.let { add(code, it) } } catch (_: ElmTimeout) { }
                    }
                    healthProgress.value = 0.05f + 0.25f * (round + 1) / 4
                }
                // Cars that don't report voltage: ask the adapter instead
                if (samples[0x42].isNullOrEmpty()) parseVolts(e.batteryVoltage())?.let { add(0x42, it) }
                samples[0x42]?.lastOrNull()?.let { voltage.value = it }

                // Step 2: oxygen sensors, sampled fast for 12 seconds
                val o2 = HealthCheck.o2Pids.filter { it in sup }
                val start = System.currentTimeMillis()
                val window = 12_000L
                if (o2.isNotEmpty()) {
                    while (System.currentTimeMillis() - start < window) {
                        for (code in o2) {
                            val pid = Pids.byCode[code] ?: continue
                            try { e.readPid(pid)?.let { add(code, it) } } catch (_: ElmTimeout) { }
                        }
                        healthProgress.value = 0.3f + 0.55f * ((System.currentTimeMillis() - start).toFloat() / window).coerceAtMost(1f)
                    }
                }
                val seconds = (System.currentTimeMillis() - start) / 1000.0

                // Step 3: trouble codes
                e.monitorStatus()?.let { (mil, _) -> milOn.value = mil }
                storedCodes.value = e.readCodes("03")
                pendingCodes.value = e.readCodes("07")
                codesRead.value = true
                healthProgress.value = 0.95f

                // Step 4: judge everything
                healthFindings.value = (HealthCheck.evaluate(samples, seconds) + codeFindings())
                    .sortedByDescending { it.level.severity }
                val bad = healthFindings.value.filter { it.level == Level.Problem || it.level == Level.Watch }
                record("Health check",
                    if (bad.isEmpty()) "Everything checked was normal" else "${bad.size} item${if (bad.size > 1) "s" else ""} flagged",
                    healthFindings.value.maxOf { it.level.severity }.let { s -> Level.entries.first { it.severity == s } },
                    bad.map { "${it.level}: ${it.title}${if (it.reading.isNotBlank()) " (${it.reading})" else ""}" })
                checkReturned(CodeWatch.ENGINE, "Engine computer", storedCodes.value + pendingCodes.value)
                val problems = healthFindings.value.count { it.level == Level.Problem }
                val watches = healthFindings.value.count { it.level == Level.Watch }
                writeWidgetStatus(
                    when { problems > 0 -> Level.Problem; watches > 0 -> Level.Watch; else -> Level.Good },
                    when { problems > 0 -> "Needs attention"; watches > 0 -> "Mostly healthy"; else -> "Looks healthy" },
                )
            } catch (x: IOException) {
                onLinkLost(x)
            } finally {
                healthProgress.value = null
                healthJob = null
            }
        }
    }

    /** Turns the stored/pending code lists (and any module scan) into health findings. */
    private fun codeFindings(): List<Finding> {
        val s = storedCodes.value; val p = pendingCodes.value
        val moduleFindings = modules.value.filter { it.codes.isNotEmpty() }.map { m ->
            Finding(HealthCheck.CODES, "${m.name}: ${m.codes.size} code${if (m.codes.size > 1) "s" else ""}", m.level,
                m.codes.joinToString(", ") { it.display }, "Open All modules for details on each code.")
        }
        return moduleFindings + when {
            s.isNotEmpty() -> listOf(Finding(HealthCheck.CODES, "${s.size} stored code${if (s.size > 1) "s" else ""}", Level.Problem,
                s.joinToString(", "), "The engine computer has confirmed a fault. Open Trouble codes for details."))
            p.isNotEmpty() -> listOf(Finding(HealthCheck.CODES, "${p.size} pending code${if (p.size > 1) "s" else ""}", Level.Watch,
                p.joinToString(", "), "Seen once but not confirmed yet. Worth checking before it turns the light on."))
            else -> listOf(Finding(HealthCheck.CODES, "No trouble codes", Level.Good, "", "Nothing stored or pending."))
        }
    }

    /** Pulls the number out of an adapter voltage reply like "12.6V". */
    private fun parseVolts(text: String): Double? =
        Regex("[0-9]+\\.[0-9]+").find(text)?.value?.toDoubleOrNull()

    // ---------- All-module scan ----------

    /** Key for saving your rename of a module, per car. */
    private fun nameKey(a: ModuleAddress) = "${vin.value ?: "car"}_${a.label}"

    /** Name priority: your rename, then the name the module reports, then our best guess. */
    private fun nameFor(a: ModuleAddress, reported: String?): String =
        moduleNames.getString(nameKey(a), null) ?: reported
            ?: Modules.guessName(a, vin.value?.let { Vin.describe(it) }?.substringAfter(' '))
            ?: "Module ${a.label}"

    /**
     * Scans the whole car:
     *  1. Knocks on every address and lists each module that answers (shown live as found)
     *  2. Reads each module's name and trouble codes
     * deep = also scan Toyota/Lexus's body network (adds about a minute).
     */
    fun scanModules(deep: Boolean) {
        val e = elm ?: run { message.value = "Connect to your adapter first."; return }
        if (!e.isCan) { message.value = "Scanning every module needs a CAN car (most cars from 2008 on)."; return }
        if (moduleJob != null || busy.value != null || healthJob != null) return
        stopPolling()
        moduleJob = viewModelScope.launch {
            val scanner = ModuleScanner(e)
            val found = mutableListOf<Module>()
            modules.value = emptyList()
            try { e.exclusive {
                scanner.begin()
                val list = ModuleScanner.addresses(e.isCan29, deep)
                // Step 1: who's there?
                list.forEachIndexed { i, a ->
                    moduleScanStatus.value = "Looking for modules, ${found.size} found"
                    moduleProgress.value = 0.65f * i / list.size
                    if (scanner.probe(a)) {
                        found += Module(a, nameFor(a, null), null, emptyList(), readOk = false)
                        modules.value = found.toList()
                    }
                }
                // Step 2: read each one
                found.toList().forEachIndexed { i, m ->
                    moduleScanStatus.value = "Reading ${m.name}"
                    moduleProgress.value = 0.65f + 0.35f * i / found.size.coerceAtLeast(1)
                    val reported = scanner.readName(m.address)
                    val result = scanner.readCodes(m.address)
                    found[i] = m.copy(
                        name = nameFor(m.address, reported), protocol = result?.first,
                        codes = result?.second ?: emptyList(), readOk = result != null,
                    )
                    checkReturned(m.address.label, found[i].name.removeSuffix(" (likely)"), found[i].codes.map { it.code })
                    modules.value = found.toList()
                }
                modulesScanned.value = true
                if (found.isEmpty()) message.value = "No modules answered. Some adapters can't reach them; an OBDLink MX+ is more capable."
                val withCodes = found.filter { it.codes.isNotEmpty() }
                record("Module scan",
                    "${found.size} modules, ${if (withCodes.isEmpty()) "no codes" else "${withCodes.size} with codes"}",
                    if (withCodes.isEmpty()) Level.Good else withCodes.maxOf { it.level.severity }.let { s -> Level.entries.first { it.severity == s } },
                    withCodes.map { m -> "${m.name}: " + m.codes.joinToString(", ") { c -> c.display + (c.failureText?.let { " ($it)" } ?: "") } })
            } } catch (x: IOException) {
                onLinkLost(x)
            } finally {
                withContext(NonCancellable) { runCatching { scanner.end() } }
                moduleScanStatus.value = null
                moduleJob = null
            }
        }
    }

    fun stopModuleScan() { moduleJob?.cancel() }

    /** Saves your own name for a module (e.g. "Seat memory"). Blank resets it. */
    fun renameModule(m: Module, newName: String) {
        val n = newName.trim()
        moduleNames.edit().apply { if (n.isEmpty()) remove(nameKey(m.address)) else putString(nameKey(m.address), n) }.apply()
        modules.update { list -> list.map { if (it.address == m.address) it.copy(name = n.ifEmpty { nameFor(m.address, null) }) else it } }
    }

    /** Clears codes in one module, then re-reads it so you see what came back right away. */
    fun clearModule(m: Module): Unit = clearModules(listOf(m))

    /** Clears codes in every module that has some. */
    fun clearAllModules(): Unit = clearModules(modules.value.filter { it.codes.isNotEmpty() && it.readOk })

    private fun clearModules(targets: List<Module>): Unit = launchBusy("Clearing codes") { e ->
        val scanner = ModuleScanner(e)
        val refused = mutableListOf<String>()
        e.exclusive { try {
            scanner.begin()
            for (m in targets) {
                val ok = scanner.clear(m.address, m.protocol ?: DiagProtocol.UDS)
                if (!ok) refused += m.name else rememberCleared(m.address.label, m.codes.map { it.code })
                val result = scanner.readCodes(m.address)
                modules.update { list ->
                    list.map { if (it.address == m.address) it.copy(codes = result?.second ?: emptyList(), readOk = result != null) else it }
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { scanner.end() } }
        } }
        message.value = if (refused.isEmpty()) "Codes cleared."
        else "Refused by ${refused.joinToString(", ")}. Some faults must be fixed first, and some modules need the engine off."
    }

    // ---------- Vehicle, recalls and drawing ----------

    /** Remembers this VIN as the current car and looks it up. */
    private fun setVin(v: String) {
        vin.value = v
        prefs.edit().putString("last_vin", v).apply()
        if (garage.value.none { it.vin == v }) saveGarage(garage.value + GarageCar(v))
        maintenance.value = Maintenance.fromJson(prefs.getString("maint_$v", null)) ?: Maintenance.defaults()
        trips.value = TripRecord.listFromJson(prefs.getString("trips_$v", null))
        learnSources.value = Learn.sourcesFromJson(prefs.getString("learn_sources_$v", null))
        returnedCodes.value = emptyList()
        CarWidget.updateAll(getApplication())
        carState.value = CarState()
        loadVehicle(v)
    }

    /** Called from the Vehicle screen's "Enter a VIN" box. Checks it looks like a real VIN first. */
    fun setVinManually(input: String) {
        val v = input.uppercase().filter { it.isLetterOrDigit() }
        when {
            v.length != 17 -> message.value = "A VIN is exactly 17 characters."
            v.any { it in "IOQ" } -> message.value = "VINs never contain I, O or Q. Check for 1 and 0 instead."
            !Vin.isValid(v) -> message.value = "A VIN only uses the letters A to Z and the numbers 0 to 9."
            else -> setVin(v)
        }
    }

    /**
     * Shows saved details instantly, then refreshes from NHTSA:
     * VIN -> year/make/model/body -> recalls. Both are saved for offline use.
     */
    private fun loadVehicle(v: String) {
        vehicleJob?.cancel()
        vehicleJob = viewModelScope.launch {
            paint.value = prefs.getLong("paint_$v", DEFAULT_PAINT)
            vehicle.value = prefs.getString("info_$v", null)?.let { VehicleInfo.fromJson(it) }
            recalls.value = prefs.getString("recalls_$v", null)?.let { Recall.listFromJson(it) } ?: emptyList()
            vehicleStatus.value = if (vehicle.value == null) "Looking up your vehicle…" else null
            try {
                val info = withContext(Dispatchers.IO) { Nhtsa.decodeVin(v) }
                vehicle.value = info
                prefs.edit().putString("info_$v", info.toJson()).apply()
                val list = withContext(Dispatchers.IO) { Nhtsa.recalls(info) }
                // Tell you about recalls that weren't there last time
                val previous = prefs.getString("recalls_$v", null)?.let { Recall.listFromJson(it).map { r -> r.campaign }.toSet() }
                val fresh = if (previous == null) emptyList() else list.filter { it.campaign !in previous }
                if (fresh.isNotEmpty()) message.value = "New recall for your car: ${fresh.first().component.substringBefore(':').lowercase()}. See Vehicle & recalls."
                recalls.value = list
                prefs.edit().putString("recalls_$v", Recall.listToJson(list)).apply()
                vehicleStatus.value = null
                CarWidget.updateAll(getApplication())
            } catch (x: CancellationException) {
                throw x
            } catch (x: Exception) {
                // Keep whatever we had saved; only complain if we have nothing at all.
                vehicleStatus.value = if (vehicle.value == null) "Couldn't reach NHTSA: ${x.message ?: "no connection"}" else null
            }
        }
    }

    /** Saves the paint color for this car. */
    fun setPaint(color: Long) {
        paint.value = color
        vin.value?.let { prefs.edit().putLong("paint_$it", color).apply() }
        CarWidget.updateAll(getApplication())
    }

    /** Saves the appearance choice. The theme follows it immediately. */
    fun setTheme(mode: ThemeMode) {
        themeMode.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    /** Turns an active test's "Shows on car" setting into the matching animation. */
    private fun showVisual(visual: String, on: Boolean) {
        Visuals.elements(visual).forEach { setElement(it, on) }
    }

    /** Switches one element of the drawing on or off (pulses fire a ripple instead). */
    private fun setElement(e: Element, on: Boolean) {
        carState.update { st ->
            if (e.kind == Element.Kind.Pulse) { if (on) st.copy(pulses = st.pulses + (e to System.currentTimeMillis())) else st }
            else st.with(e, if (on) 1f else 0f)
        }
    }

    /**
     * The active test that controls a part of the current car, if you've set one up.
     * An exact match ("Driver door") wins over a combination ("All doors").
     */
    fun testFor(e: Element, all: List<ActiveTest>): ActiveTest? {
        val mine = all.filter { it.vin.isBlank() || it.vin == vin.value }
        return mine.firstOrNull { it.visual == e.key } ?: mine.firstOrNull { e in Visuals.elements(it.visual) }
    }

    // ---------- Garage ----------

    private fun saveGarage(list: List<GarageCar>) {
        garage.value = list
        prefs.edit().putString("garage", GarageJson.toJson(list)).apply()
    }

    /** Records that we just connected to the current car. */
    private fun markSeen() {
        val v = vin.value ?: return
        saveGarage(garage.value.map { if (it.vin == v) it.copy(lastSeen = System.currentTimeMillis()) else it })
    }

    /** Makes another car from the garage the current one. */
    fun switchCar(v: String) { if (v != vin.value) setVin(v) }

    fun renameCar(v: String, nickname: String) {
        saveGarage(garage.value.map { if (it.vin == v) it.copy(nickname = nickname.trim()) else it })
        CarWidget.updateAll(getApplication())
    }

    /** Removes a car from the garage (its saved data stays in case you add it back). */
    fun removeCar(v: String) {
        val rest = garage.value.filter { it.vin != v }
        saveGarage(rest)
        if (vin.value == v) {
            val next = rest.maxByOrNull { it.lastSeen }?.vin
            if (next != null) setVin(next) else {
                vin.value = null; vehicle.value = null; recalls.value = emptyList()
                prefs.edit().remove("last_vin").apply()
            }
        }
    }

    /** Saved details for any garage car (for the Garage list). */
    fun infoFor(v: String): VehicleInfo? = prefs.getString("info_$v", null)?.let { VehicleInfo.fromJson(it) }
    fun paintFor(v: String): Long = prefs.getLong("paint_$v", DEFAULT_PAINT)

    /** The name to show for a car: your nickname, else "2016 Lexus RC". */
    fun nameFor(v: String): String =
        garage.value.firstOrNull { it.vin == v }?.nickname?.takeIf { it.isNotBlank() }
            ?: infoFor(v)?.displayName ?: Vin.describe(v) ?: v

    // ---------- Maintenance ----------

    private fun today(): Long = System.currentTimeMillis() / 86_400_000L

    private fun saveMaintenance(list: List<MaintItem>) {
        maintenance.value = list
        vin.value?.let { prefs.edit().putString("maint_$it", Maintenance.toJson(list)).apply() }
    }

    /** Current mileage for the current car, from the car or typed in. */
    fun setOdometer(miles: Int, fromCar: Boolean = false) {
        val v = vin.value ?: return
        saveGarage(garage.value.map { if (it.vin == v) it.copy(odometerMiles = miles, odometerFromCar = fromCar) else it })
    }

    fun markServiceDone(item: MaintItem) {
        val miles = garage.value.firstOrNull { it.vin == vin.value }?.odometerMiles
        saveMaintenance(maintenance.value.map { if (it.id == item.id) it.copy(lastDay = today(), lastMiles = miles ?: it.lastMiles) else it })
    }

    fun saveServiceItem(item: MaintItem) {
        saveMaintenance(maintenance.value.filter { it.id != item.id } + item)
    }

    fun deleteServiceItem(item: MaintItem) { saveMaintenance(maintenance.value.filter { it.id != item.id }) }

    fun todayDay(): Long = today()

    // ---------- History ----------

    /** Adds an entry to the scan history for the current car. */
    private fun record(kind: String, title: String, level: Level, lines: List<String>) {
        val v = vin.value ?: "unknown"
        history.update { (it + HistoryEntry(v, System.currentTimeMillis(), kind, title, level, lines)).takeLast(300).also(historyStore::save) }
    }

    private fun recordCodes() {
        val s = storedCodes.value; val p = pendingCodes.value
        val lines = s.map { "Stored $it: ${com.daniel.carscanner.obd.DtcDecoder.describe(it)}" } +
            p.map { "Pending $it: ${com.daniel.carscanner.obd.DtcDecoder.describe(it)}" } +
            listOfNotNull(readiness.value?.verdict?.second?.let { "Inspection: $it" })
        record("Codes",
            when { s.isNotEmpty() -> "${s.size} stored code${if (s.size > 1) "s" else ""}"; p.isNotEmpty() -> "${p.size} pending"; else -> "No codes" },
            when { s.isNotEmpty() -> Level.Problem; p.isNotEmpty() -> Level.Watch; else -> Level.Good }, lines)
    }

    fun deleteHistory(e: HistoryEntry) { history.update { list -> list.filter { it != e }.also(historyStore::save) } }

    // ---------- Body signals and live view ----------

    fun saveSignal(sig: BodySignal) {
        // Live view repeats this request several times a second, so it must only read.
        if (!isReadRequest(sig.header, sig.command)) {
            message.value = "Body signals can only use read requests (like 22xxxx or 21xx) and a valid module address."
            return
        }
        allSignals.update { list -> (list.filter { it.id != sig.id } + sig).also(signalStore::save) }
    }

    /** True for a valid module address plus a request that only reads (safe to repeat forever). */
    private fun isReadRequest(header: String, command: String): Boolean =
        Safety.readRequestProblem(header, command) == null

    // ---------- Monitor test results (mode 06) ----------

    /** Reads every emissions self-test result the engine computer has stored. */
    fun readMonitorTests() = launchBusy("Reading test results") { e ->
        monitorProgress.value = 0f
        try {
            val tests = e.monitorTests { p -> monitorProgress.value = p }
            if (tests == null) {
                message.value = "Test results need a CAN car (most cars from 2008 on)."
                return@launchBusy
            }
            monitorTests.value = tests
            if (tests.isEmpty()) message.value = "The car didn't report any test results. Drive it normally for a day so its monitors run, then try again."
            else {
                val failed = tests.filter { !it.passed }
                record("Monitor tests", if (failed.isEmpty()) "${tests.size} tests, all passed" else "${failed.size} of ${tests.size} tests failed",
                    if (failed.isEmpty()) Level.Good else Level.Problem,
                    failed.map { "${it.monitor}: ${it.test}" })
            }
        } finally { monitorProgress.value = null }
    }

    // ---------- In-app updates (from the app's GitHub releases) ----------

    /** info = a newer build is available; progress = downloading; note = a message for you. */
    data class UpdateUi(val info: UpdateInfo? = null, val progress: Float? = null, val note: String? = null, val checking: Boolean = false)
    val update = MutableStateFlow(UpdateUi())
    private var updateJob: Job? = null

    /** Asks GitHub for a newer build. Quiet on app start; manual checks say "up to date" or why it failed. */
    fun checkForUpdate(manual: Boolean) {
        if (updateJob != null) return
        updateJob = viewModelScope.launch {
            update.update { it.copy(checking = true, note = null) }
            try {
                val info = withContext(Dispatchers.IO) { Updater.check(BuildConfig.VERSION_CODE) }
                update.update {
                    it.copy(info = info, checking = false,
                        note = if (info == null && manual) "You're on the newest version." else null)
                }
            } catch (e: Exception) {
                update.update { it.copy(checking = false, note = if (manual) "Couldn't check for updates. Are you online?" else null) }
            } finally { updateJob = null }
        }
    }

    /** Downloads the newer build, checks it's signed with this app's key, and opens Android's installer. */
    fun installUpdate() {
        val info = update.value.info ?: return
        val ctx = getApplication<Application>()
        if (!Updater.canInstall(ctx)) {
            update.update { it.copy(note = "Turn on \"Allow from this source\" for this app, then come back and tap Update again.") }
            runCatching { Updater.openInstallPermission(ctx) }
            return
        }
        if (updateJob != null) return
        updateJob = viewModelScope.launch {
            update.update { it.copy(progress = 0f, note = null) }
            try {
                val apk = withContext(Dispatchers.IO) {
                    Updater.download(ctx, info) { p -> update.update { u -> u.copy(progress = p) } }
                }
                val ok = withContext(Dispatchers.IO) { Updater.isSignedLikeThisApp(ctx, apk) }
                if (!ok) {
                    apk.delete()
                    update.update { it.copy(progress = null, note = "The download wasn't signed with this app's key, so it was not installed.") }
                } else {
                    update.update { it.copy(progress = null, note = "Tap Install on the next screen.") }
                    Updater.install(ctx, apk)
                }
            } catch (e: Exception) {
                update.update { it.copy(progress = null, note = "Download failed: ${e.message ?: "unknown error"}. Try again.") }
            } finally { updateJob = null }
        }
    }

    // ---------- Automatic setup: find body data, then learn each part ----------

    /**
     * Step 1 of automatic setup: finds every block of data the car's modules share.
     * Knocks on every module (including the Toyota/Lexus body network), then asks each
     * one for every data block. Read-only. Takes a few minutes, once per car.
     */
    fun findBodyData() {
        val e = elm ?: run { message.value = "Connect to your adapter first."; return }
        val v = vin.value ?: run { message.value = "Add your car first (connect once so it reads the VIN)."; return }
        if (!e.isCan) { message.value = "Automatic setup needs a CAN car (most cars from 2008 on)."; return }
        if (learnJob != null || moduleJob != null || healthJob != null || testJob != null || busy.value != null) return
        stopPolling()
        setLiveView(false)
        learnResult.value = null
        learnJob = viewModelScope.launch {
            val scanner = ModuleScanner(e)
            val found = mutableListOf<DataSource>()
            try { e.exclusive {
                scanner.begin()
                // Which modules to read: from a module scan if there was one, else knock on every address now.
                val make = vehicle.value?.make?.uppercase()
                val deep = make == null || make in setOf("TOYOTA", "LEXUS", "SCION")
                val known = modules.value.map { it.address }
                val addresses = (if (known.isNotEmpty() && (!deep || known.any { it.ext != null })) known else {
                    val all = ModuleScanner.addresses(e.isCan29, deep)
                    val alive = mutableListOf<ModuleAddress>()
                    all.forEachIndexed { i, a ->
                        learnStatus.value = "Finding your car's computers, ${alive.size} found"
                        learnProgress.value = 0.35f * i / all.size
                        if (scanner.probe(a)) alive += a
                    }
                    alive
                }).filter { !it.is29 && !Learn.skip(it) }

                // Ask each module for every data block. Toyota-style modules first (21xx);
                // if no module answers those, try the UDS range (22xxxx).
                suspend fun sweep(requestsFor: suspend (ModuleAddress) -> List<String>, from: Float, to: Float) {
                    addresses.forEachIndexed { i, a ->
                        val requests = requestsFor(a)
                        requests.forEachIndexed { j, r ->
                            learnStatus.value = "Reading module ${a.label} (${i + 1} of ${addresses.size}), ${found.size} data blocks found"
                            learnProgress.value = from + (to - from) * (i + j.toFloat() / requests.size.coerceAtLeast(1)) / addresses.size.coerceAtLeast(1)
                            val bytes = Learn.payload(scanner.readLines(a, r, 1_000), r)
                            if (bytes != null && bytes.size > r.length / 2) found += DataSource(a, r)
                        }
                    }
                }
                sweep({ a ->
                    val test = scanner.readLines(a, "2101", 1_000)
                    if (test.isEmpty() || Learn.serviceUnsupported(test, "2101")) emptyList() else Learn.kwpRequests
                }, 0.35f, 0.9f)
                if (found.isEmpty()) sweep({ a ->
                    val test = scanner.readLines(a, "220100", 1_000)
                    if (test.isEmpty() || Learn.serviceUnsupported(test, "220100")) emptyList() else Learn.udsRequests
                }, 0.9f, 1f)

                prefs.edit().putString("learn_sources_$v", Learn.sourcesToJson(found)).apply()
                learnSources.value = found.toList()
                message.value = if (found.isEmpty())
                    "Your car's modules didn't share any data blocks. Cheap adapters often can't reach them; an OBDLink MX+ or Vgate iCar Pro usually can."
                else "Found ${found.size} data blocks. Now teach each part."
            } } catch (x: IOException) {
                onLinkLost(x)
            } finally {
                withContext(NonCancellable) { runCatching { scanner.end() } }
                learnStatus.value = null
                learnJob = null
            }
        }
    }

    /** Starts teaching one part: shows its first instruction ("Close the driver door"). */
    fun startLearning(step: LearnStep) {
        if (learnJob != null) return
        learnReads.clear()
        learnResult.value = null
        learnActive.value = step to 0
    }

    fun cancelLearning() {
        learnJob?.cancel()
        learnActive.value = null
        learnReads.clear()
    }

    /**
     * You did what the instruction said and tapped Next: read every data block a few times,
     * then move to the next instruction. After the last one, find the bit and save it.
     */
    fun learnNext() {
        val e = elm ?: run { message.value = "Connect to your adapter first."; return }
        val (step, phase) = learnActive.value ?: return
        val sources = learnSources.value
        if (learnJob != null || sources.isEmpty()) return
        stopPolling()
        setLiveView(false)
        learnJob = viewModelScope.launch {
            try {
                // More readings while "off" weed out bits that change on their own; blinking parts need several "on" readings.
                val rounds = when (phase) { 0 -> 3; 1 -> if (step.blinks) 5 else 2; else -> 2 }
                val list = learnReads.getOrPut(phase) { mutableListOf() }
                repeat(rounds) { r ->
                    learnStatus.value = "Reading (${r + 1} of $rounds)…"
                    list += readAll(e, sources)
                }
                if (phase < 2) learnActive.value = step to phase + 1
                else finishLearning(step, sources)
            } catch (x: IOException) {
                learnActive.value = null
                onLinkLost(x)
            } finally {
                learnStatus.value = null
                learnJob = null
            }
        }
    }

    /** Reads every data block once. */
    private suspend fun readAll(e: ElmClient, sources: List<DataSource>): Map<DataSource, List<Int>> = e.exclusive {
        val scanner = ModuleScanner(e)
        val out = mutableMapOf<DataSource, List<Int>>()
        try {
            scanner.begin()
            for (s in sources) Learn.payload(scanner.readLines(s.address, s.request, 1_000), s.request)?.let { out[s] = it }
        } finally {
            withContext(NonCancellable) { runCatching { scanner.end() } }
        }
        out
    }

    /** Compares the readings, picks the bit that followed what you did, and saves it. */
    private fun finishLearning(step: LearnStep, sources: List<DataSource>) {
        val v = vin.value ?: return
        val mine = allSignals.value.filter { it.vin == v && it.element !in step.elements }
        val hits = mutableListOf<Pair<DataSource, com.daniel.carscanner.obd.FoundBit>>()
        for (s in sources) {
            fun reads(p: Int) = learnReads[p].orEmpty().mapNotNull { it[s] }
            val found = Learn.candidates(reads(0), reads(1), reads(2), step.blinks)
            val header = "%03X".format(s.address.request)
            // Skip bits already taught to another part (e.g. a door bit when learning the interior light).
            hits += found.filter { b ->
                mine.none { it.header == header && it.ext == s.address.ext && it.command == s.request && it.byteIndex == b.byteIndex && it.bit == b.bit }
            }.map { s to it }
        }
        learnActive.value = null
        learnReads.clear()
        if (hits.isEmpty()) {
            learnResult.value = step.id to "Couldn't find it. Make sure each step was done before tapping Next, then try again. Some cars don't share this one."
            return
        }
        // Prefer the body network (where doors and lights usually live), then the first match.
        val (src, b) = hits.firstOrNull { it.first.address.ext != null } ?: hits.first()
        val header = "%03X".format(src.address.request)
        allSignals.update { list ->
            (list.filter { !(it.vin == v && it.element in step.elements) } + step.elements.map { el ->
                BodySignal(vin = v, element = el, header = header, command = src.request, byteIndex = b.byteIndex,
                    bit = b.bit, invert = b.invert, fast = step.blinks, ext = src.address.ext)
            }).also(signalStore::save)
        }
        learnResult.value = step.id to (if (hits.size == 1) "Found it." else "Found it (best of ${hits.size} matches). If it looks wrong in live view, learn it again.")
    }

    fun deleteSignal(id: String) { allSignals.update { list -> list.filter { it.id != id }.also(signalStore::save) } }

    /** Turns live view on or off. While on, the drawing mirrors the real car. */
    fun setLiveView(on: Boolean) {
        liveView.value = on
        liveJob?.cancel(); liveJob = null
        if (on) startLiveView()
    }

    /**
     * The live view loop:
     *  - reads each body signal (grouped so one request feeds several signals)
     *  - turn signals every cycle, everything else every third cycle
     *  - engine rpm every few seconds for "engine running"
     *  - feeds turn signals through BlinkTracker so the drawing flashes at your car's rate
     * Runs inside exclusive { } so it never collides with scans or tests.
     */
    private fun startLiveView() {
        val e = elm ?: run { message.value = "Connect to your adapter first."; liveView.value = false; return }
        liveJob = viewModelScope.launch {
            val trackers = mapOf(Element.TurnL to BlinkTracker(), Element.TurnR to BlinkTracker(), Element.Hazard to BlinkTracker())
            var cycle = 0
            try {
                while (isActive) {
                    if (liveViewPaused.value || healthJob != null || moduleJob != null || testJob != null) { delay(400); continue }
                    val v = vin.value
                    // Signals saved by older versions weren't checked, so only read-only ones are polled.
                    val sigs = allSignals.value.filter { it.vin == v && isReadRequest(it.header, it.command) }
                    val due = sigs.filter { it.fast || it.element in trackers || cycle % 3 == 0 }
                    val read = mutableMapOf<Element, Float>()
                    val failed = mutableSetOf<Element>()
                    val failedChecked = mutableSetOf<Element>()
                    e.exclusive {
                        // Signals on the Toyota/Lexus body network need the module's sub-address.
                        val (viaAddress, plain) = due.partition { it.address != null }
                        if (viaAddress.isNotEmpty()) {
                            val scanner = ModuleScanner(e)
                            try {
                                for ((key, group) in viaAddress.groupBy { it.address!! to it.command }) {
                                    val bytes = Learn.payload(scanner.readLines(key.first, key.second), key.second) ?: continue
                                    for (s in group) s.decodeBytes(bytes)?.let { value ->
                                        if (s.failure) { failedChecked += s.element; if (value > 0.5f) failed += s.element }
                                        else read[s.element] = value
                                    }
                                }
                            } finally { scanner.release() }
                        }
                        for ((key, group) in plain.groupBy { it.header to it.command }) {
                            try {
                                e.send("ATSH" + key.first.replace(" ", ""), quiet = true)
                                val reply = e.send(key.second, 1_500, quiet = true)
                                for (s in group) s.decode(reply)?.let { value ->
                                    if (s.failure) { failedChecked += s.element; if (value > 0.5f) failed += s.element }
                                    else read[s.element] = value
                                }
                            } catch (_: ElmTimeout) { }
                        }
                        if (plain.isNotEmpty()) e.send(e.resetHeaderCommand(), quiet = true)
                        if (cycle % 10 == 0) {
                            try { e.readPid(Pids.byCode[0x0C]!!)?.let { read[Element.Engine] = if (it > 300) 1f else 0f } } catch (_: ElmTimeout) { }
                        }
                    }
                    val now = System.currentTimeMillis()
                    carState.update { st ->
                        var next = st
                        var blink = st.blinkMs
                        var anchor = st.blinkAnchor
                        for ((el, value) in read) {
                            val tracker = trackers[el]
                            if (tracker != null) {
                                val (active, period, last) = tracker.feed(value > 0.5f, now)
                                if (period != null) blink = period
                                if (last != null) anchor = last
                                next = next.with(el, if (active) 1f else 0f)
                            } else if (el.kind == Element.Kind.Pulse) {
                                if (value > 0.5f && !st.on(el)) next = next.copy(pulses = next.pulses + (el to now))
                                next = next.with(el, value)
                            } else next = next.with(el, value)
                        }
                        next.copy(
                            blinkMs = blink, blinkAnchor = anchor,
                            failed = (next.failed - failedChecked) + failed,
                            known = sigs.map { it.element }.toSet() + Element.Engine,
                        )
                    }
                    cycle++
                    delay(if (sigs.any { it.element in trackers }) 120 else 300)
                }
            } catch (x: IOException) {
                onLinkLost(x)
            }
        }
    }

    /**
     * Watch tool: sends one request over and over and shows the reply's bytes,
     * highlighting any byte or bit that changed in the last few seconds.
     * Open a door while watching and the byte that flips is your door signal.
     */
    fun startWatch(header: String, command: String) {
        val e = elm ?: run { watchError.value = "Connect to your adapter first."; return }
        val cmd = command.replace(" ", "").uppercase()
        if (!isReadRequest(header, cmd)) { watchError.value = "Watch only sends read requests to a valid module address."; return }
        watchJob?.cancel()
        liveViewPaused.value = true
        watchError.value = null
        watchJob = viewModelScope.launch {
            val tracker = WatchTracker()
            try {
                while (isActive) {
                    val reply = try {
                        e.exclusive {
                            if (header.isNotBlank()) e.send("ATSH" + header.replace(" ", ""), quiet = true)
                            val r = e.send(cmd, 1_500, quiet = true)
                            if (header.isNotBlank()) e.send(e.resetHeaderCommand(), quiet = true)
                            r
                        }
                    } catch (_: ElmTimeout) { null }
                    val line = reply?.let { Hex.dataLines(it).firstOrNull() }
                    if (line == null) watchError.value = "No reply. Check the module address and request."
                    else {
                        watchError.value = if (line.startsWith("7F")) Uds.describe(reply) else null
                        val now = System.currentTimeMillis()
                        tracker.feed(Hex.bytes(line), now)
                        watchBytes.value = tracker.current
                        val changed = tracker.changedBytes(now)
                        watchChanged.value = changed
                        watchBits.value = changed.associateWith { tracker.changedBits(it, now) }
                    }
                    delay(250)
                }
            } catch (x: IOException) {
                onLinkLost(x)
            }
        }
    }

    fun stopWatch() {
        watchJob?.cancel(); watchJob = null
        watchBytes.value = emptyList(); watchChanged.value = emptySet(); watchBits.value = emptyMap()
        liveViewPaused.value = false
    }

    // ---------- Codes that come back ----------

    private fun clearedMap(): Map<String, Long> = CodeWatch.fromJson(prefs.getString("cleared_${vin.value}", null))
    private fun saveCleared(map: Map<String, Long>) { prefs.edit().putString("cleared_${vin.value}", CodeWatch.toJson(map)).apply() }

    /** Called right after clearing: remembers which codes were cleared, and when. */
    private fun rememberCleared(place: String, codes: List<String>) {
        if (codes.isEmpty() || vin.value == null) return
        val now = System.currentTimeMillis()
        saveCleared(clearedMap() + codes.distinct().associate { CodeWatch.key(place, it) to now })
    }

    /** Called after every code read: flags any cleared code that has reappeared, and alerts you once. */
    private fun checkReturned(place: String, where: String, codes: List<String>) {
        val v = vin.value ?: return
        val cleared = clearedMap()
        val back = CodeWatch.returned(cleared, place, where, codes)
        if (back.isEmpty()) return
        saveCleared(cleared - back.map { CodeWatch.key(it.placeKey, it.code) }.toSet())   // alert once per return
        returnedCodes.update { old -> (old + back).distinctBy { it.placeKey + it.code } }
        val now = System.currentTimeMillis()
        record("Code returned", back.joinToString(", ") { it.code } + " came back", Level.Problem,
            back.map { "${it.code} (${it.where}) returned ${CodeWatch.ago(it.clearedAt, now)} after being cleared" })
        message.value = "${back.joinToString(", ") { it.code }} came back after you cleared ${if (back.size == 1) "it" else "them"}."
        Notifier.codesReturned(getApplication(), v, nameFor(v), back)
    }

    fun dismissReturned(r: ReturnedCode) { returnedCodes.update { list -> list.filter { it != r } } }

    // ---------- Trips and MPG ----------

    /**
     * Starts a trip: about once a second reads speed and fuel use, and adds up
     * miles, gallons and MPG. Fuel use comes from the car's fuel-rate reading
     * if it has one, otherwise from the mass air flow sensor.
     * Keep the app open on screen during the trip.
     */
    fun startTrip() {
        val e = elm ?: run { message.value = "Connect to your adapter first."; return }
        if (tripJob != null) return
        val sup = supported.value
        val useRate = 0x5E in sup
        val useMaf = !useRate && (sup.isEmpty() || 0x10 in sup)
        val meter = TripMeter(System.currentTimeMillis())
        tripLive.value = meter
        tripJob = viewModelScope.launch {
            try {
                while (isActive) {
                    try {
                        val mph = e.readPid(Pids.byCode[0x0D]!!) ?: 0.0
                        val gph = when {
                            useRate -> e.readPid(Pids.byCode[0x5E]!!)
                            useMaf -> e.readPid(Pids.byCode[0x10]!!)?.let { FuelMath.gphFromMaf(it) }
                            else -> null
                        }
                        meter.feed(mph, gph, System.currentTimeMillis())
                        tripTick.value = System.currentTimeMillis()
                    } catch (_: ElmTimeout) { }
                    delay(900)
                }
            } catch (x: IOException) {
                onLinkLost(x)
            }
        }
    }

    /** Ends the trip and saves it (trips under 0.05 miles are discarded). */
    fun stopTrip() {
        tripJob?.cancel(); tripJob = null
        val meter = tripLive.value ?: return
        tripLive.value = null
        val v = vin.value ?: return
        if (meter.miles >= 0.05) {
            val list = (listOf(meter.toRecord()) + trips.value).take(100)
            trips.value = list
            prefs.edit().putString("trips_$v", TripRecord.listToJson(list)).apply()
            val mpg = meter.averageMpg
            record("Trip", "%.1f mi".format(meter.miles) + (mpg?.let { ", %.1f MPG".format(it) } ?: ""), Level.Good,
                listOf("Time: ${meter.seconds.toLong() / 60} min", "Average speed: %.0f mph".format(meter.averageMph)))
        }
    }

    fun deleteTrip(t: TripRecord) {
        val v = vin.value ?: return
        trips.update { list -> list.filter { it != t }.also { prefs.edit().putString("trips_$v", TripRecord.listToJson(it)).apply() } }
    }

    /** Price per gallon, for trip cost. Blank clears it. */
    fun setFuelPrice(text: String) {
        val p = text.toDoubleOrNull()
        fuelPrice.value = p
        prefs.edit().putString("fuel_price", p?.toString()).apply()
    }

    // ---------- Alerts and widget ----------

    fun setRecallAlerts(on: Boolean) {
        recallAlerts.value = on
        prefs.edit().putBoolean("recall_alerts", on).apply()
    }

    /** Saves the latest result for the home screen widget and refreshes it. */
    private fun writeWidgetStatus(level: Level, headline: String) {
        val v = vin.value ?: return
        prefs.edit().putString("status_$v", "${level.name}|$headline|${System.currentTimeMillis()}").apply()
        CarWidget.updateAll(getApplication())
    }

    // ---------- Console ----------

    /** Sends a command typed in the Console. Dangerous ones are blocked unless Expert mode is on. */
    fun sendRaw(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        if (!Safety.isSendable(c)) {
            appendLog("! Blocked: one command per line, using only letters, numbers and spaces.")
            return
        }
        if (Safety.classify(c) == Risk.Dangerous && !expertMode.value) {
            appendLog("! Blocked \"$c\": this can reset, unlock or reprogram a module. Turn on Expert mode to send it.")
            return
        }
        val e = elm ?: run { message.value = "Connect to your adapter first."; return }
        viewModelScope.launch {
            try { e.send(c, 8_000) }
            catch (x: ElmTimeout) { appendLog("(no reply)") }
            catch (x: IOException) { onLinkLost(x) }
        }
    }

    companion object {
        /** Silver, until you pick a color. */
        const val DEFAULT_PAINT = 0xFFB9BDC3
        /** How many readings each live graph keeps. */
        const val HISTORY_SAMPLES = 120
    }

    /** Android calls this when the app closes: release Bluetooth. */
    override fun onCleared() {
        stopBleScan()
        disconnect()
    }
}
