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
import com.daniel.carscanner.obd.Pids
import com.daniel.carscanner.obd.Risk
import com.daniel.carscanner.obd.Safety
import com.daniel.carscanner.obd.Uds
import kotlinx.coroutines.CancellationException
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
    val scanning = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow<String?>(null)
    val log = MutableStateFlow<List<String>>(emptyList())

    val vin = MutableStateFlow<String?>(null)
    val supported = MutableStateFlow<Set<Int>>(emptySet())
    val selectedPids = MutableStateFlow(setOf(0x0C, 0x05, 0x42, 0x0D, 0x11, 0x04))
    val live = MutableStateFlow<Map<Int, Double>>(emptyMap())
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

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

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
                supported.value = runCatching { client.supportedPids() }.getOrDefault(emptySet())
                vin.value = runCatching { client.readVin() }.getOrNull()
                voltage.value = runCatching { parseVolts(client.batteryVoltage()) }.getOrNull()
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
        testJob?.cancel()
        healthJob?.cancel()
        moduleJob?.cancel()
        transport?.close()
        transport = null
        elm = null
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
                            e.readPid(pid)?.let { v -> live.update { it + (code to v) } }
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
        codesRead.value = true
    }

    /** Clears codes, then re-reads them so you can see what came back. */
    fun clearCodes() = launchBusy("Clearing codes") { e ->
        val ok = e.clearCodes()
        message.value = if (ok) "Codes cleared." else "The car refused to clear codes. Turn the engine off, ignition on, and try again."
        e.monitorStatus()?.let { (mil, _) -> milOn.value = mil }
        storedCodes.value = e.readCodes("03")
        pendingCodes.value = e.readCodes("07")
    }

    // ---------- Active tests ----------

    /** Adds a new test or replaces an edited one, and saves to the phone. */
    fun saveTest(t: ActiveTest) {
        tests.update { list -> (list.filter { it.id != t.id } + t).also(store::save) }
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
        val blocked = (t.startCommands + t.stopCommands).any { Safety.classify(it) == Risk.Dangerous }
        if (blocked && !expertMode.value) {
            message.value = "This test contains a command that can reset or reprogram a module. Turn on Expert mode in Console to allow it."
            return
        }
        stopPolling()
        testJob = viewModelScope.launch {
            runningTest.value = t.id
            testStatus.value = "Starting…"
            try {
                if (t.header.isNotBlank()) e.send("ATSH" + t.header.replace(" ", ""))
                for (cmd in t.startCommands) {
                    val r = e.send(cmd)
                    testStatus.value = Uds.describe(r)
                    if (Uds.negative(r) != null || "NO DATA" in r.uppercase()) return@launch
                }
                val endAt = System.currentTimeMillis() + t.maxSeconds * 1000L
                while (isActive && System.currentTimeMillis() < endAt) {
                    testStatus.value = "Running, stops in ${(endAt - System.currentTimeMillis() + 999) / 1000} s"
                    delay(2_000)
                    if (t.keepAlive.isNotBlank()) e.send(t.keepAlive, quiet = true)
                }
            } catch (x: IOException) {
                testStatus.value = "Error: ${x.message}"
            } finally {
                withContext(NonCancellable) {
                    for (cmd in t.stopCommands) runCatching { e.send(cmd) }
                    runCatching { e.send(e.resetHeaderCommand()) }
                }
                if (!testStatus.value.startsWith("Rejected") && !testStatus.value.startsWith("No reply") &&
                    !testStatus.value.startsWith("Error")) testStatus.value = "Stopped"
                runningTest.value = null
                testJob = null
            }
        }
    }

    fun stopTest() { testJob?.cancel() }

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
            try {
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
                    modules.value = found.toList()
                }
                modulesScanned.value = true
                if (found.isEmpty()) message.value = "No modules answered. Some adapters can't reach them; an OBDLink MX+ is more capable."
            } catch (x: IOException) {
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
        try {
            scanner.begin()
            for (m in targets) {
                val ok = scanner.clear(m.address, m.protocol ?: DiagProtocol.UDS)
                if (!ok) refused += m.name
                val result = scanner.readCodes(m.address)
                modules.update { list ->
                    list.map { if (it.address == m.address) it.copy(codes = result?.second ?: emptyList(), readOk = result != null) else it }
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { scanner.end() } }
        }
        message.value = if (refused.isEmpty()) "Codes cleared."
        else "Refused by ${refused.joinToString(", ")}. Some faults must be fixed first, and some modules need the engine off."
    }

    // ---------- Console ----------

    /** Sends a command typed in the Console. Dangerous ones are blocked unless Expert mode is on. */
    fun sendRaw(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
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

    /** Android calls this when the app closes: release Bluetooth. */
    override fun onCleared() {
        stopBleScan()
        disconnect()
    }
}
