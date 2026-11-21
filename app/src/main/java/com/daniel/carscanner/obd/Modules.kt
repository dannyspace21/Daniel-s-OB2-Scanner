package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * Finds and reads EVERY computer ("module") in the car, not just the engine:
 * ABS, airbags, transmission, body, climate, cluster, steering and so on.
 *
 * How it works, in plain English:
 *  1. Every module has an address on the car's network. We "knock" on every
 *     possible address with a harmless "are you there?" message (3E 00) and
 *     write down which ones answer.
 *  2. For each module that answered, we ask its name (if it will say) and its
 *     trouble codes. Modules speak one of three dialects, so we try each:
 *       UDS (most cars from ~2010 on), KWP2000 (older), and Toyota's older style.
 *  3. Codes come with extra detail the engine-only scan doesn't have: whether
 *     the fault is active right now, and a "failure type" such as
 *     "open circuit" or "short to ground".
 *
 * Reading is safe. Clearing only erases the record; it doesn't fix anything.
 */

/** Which diagnostic dialect a module answered in. */
enum class DiagProtocol { UDS, KWP, TOYOTA }

/**
 * Where a module lives on the network.
 * request  the address we send to
 * response the address it answers from (usually request + 8)
 * ext      extra sub-address byte (Toyota/Lexus body network uses these)
 * is29     true for cars that use long 29-bit addresses
 */
data class ModuleAddress(val request: Int, val response: Int, val ext: Int? = null, val is29: Boolean = false) {
    /** Short label shown on screen, e.g. "7B0", "750/40" or "18DA28F1". */
    val label: String
        get() = when {
            is29 -> "18DA%02XF1".format(request and 0xFF)
            ext != null -> "%03X/%02X".format(request, ext)
            else -> "%03X".format(request)
        }
}

/**
 * One trouble code from a module.
 * status      UDS status bits (null for older modules, which don't send them)
 * failureType the "-1F" style suffix that says HOW it failed (UDS only)
 */
data class ModuleCode(val code: String, val status: Int?, val failureType: Int?) {
    val display: String get() = if (failureType != null && failureType != 0) "%s-%02X".format(code, failureType) else code
    val stateText: String get() = status?.let { Modules.statusText(it) } ?: "Stored"
    val failureText: String? get() = failureType?.let { Modules.failureText(it) }

    /** Red if active or confirmed, amber if only pending or history. */
    val level: Level
        get() = when {
            status == null -> Level.Problem
            status and 0x09 != 0 -> Level.Problem   // active now or confirmed
            else -> Level.Watch
        }
}

/** One module found during a scan. readOk = we managed to read its codes. */
data class Module(
    val address: ModuleAddress,
    val name: String,
    val protocol: DiagProtocol?,
    val codes: List<ModuleCode>,
    val readOk: Boolean,
) {
    val level: Level
        get() = when {
            !readOk -> Level.Unknown
            codes.isEmpty() -> Level.Good
            else -> codes.maxBy { it.level.severity }.level
        }

    val isAirbag: Boolean
        get() = listOf("AIRBAG", "SRS", "RESTRAINT", "OCCUPANT").any { name.uppercase().contains(it) }
}

object Modules {

    /** Turns UDS status bits into words. */
    fun statusText(s: Int): String {
        val parts = mutableListOf<String>()
        if (s and 0x01 != 0) parts += "active now"
        if (s and 0x08 != 0) parts += "confirmed"
        if (s and 0x04 != 0) parts += "pending"
        if (s and 0x80 != 0) parts += "warning light requested"
        if (parts.isEmpty() && s and 0x20 != 0) parts += "history (not active now)"
        return parts.joinToString(", ").replaceFirstChar { it.uppercase() }.ifEmpty { "Stored" }
    }

    /** Common failure-type meanings (SAE J2012). Unknown ones show the number only. */
    private val failureTypes = mapOf(
        0x01 to "general electrical failure",
        0x11 to "short to ground",
        0x12 to "short to battery",
        0x13 to "open circuit",
        0x16 to "voltage below threshold",
        0x17 to "voltage above threshold",
        0x1C to "voltage out of range",
        0x29 to "signal invalid",
        0x31 to "no signal",
        0x4B to "over temperature",
        0x54 to "missing calibration",
        0x55 to "not configured",
        0x64 to "signal plausibility failure",
        0x81 to "invalid data received",
        0x82 to "message counter incorrect",
        0x83 to "message checksum incorrect",
        0x86 to "invalid signal received",
        0x87 to "missing message",
        0x88 to "network bus off",
        0x96 to "internal failure inside the module",
    )
    fun failureText(ft: Int): String? = if (ft == 0) null else failureTypes[ft] ?: "failure type %02X".format(ft)

    // Usual addresses on Toyota and Lexus. Shown as "(likely)" because a module
    // can sit elsewhere; if it reports its own name, that wins.
    private val toyotaGuesses = mapOf(
        0x7E0 to "Engine", 0x7E1 to "Transmission", 0x7E2 to "Hybrid system",
        0x7B0 to "ABS / brakes", 0x780 to "Airbag (SRS)", 0x7C0 to "Instrument cluster",
        0x7C4 to "Climate control", 0x7A1 to "Power steering", 0x750 to "Body gateway",
    )
    private val genericGuesses = mapOf(0x7E0 to "Engine", 0x7E1 to "Transmission")
    private val generic29Guesses = mapOf(0x10 to "Engine", 0x18 to "Transmission", 0x28 to "Brakes")

    /** Best guess at a module's job from its address and the car's maker. */
    fun guessName(a: ModuleAddress, maker: String?): String? {
        if (a.ext != null) return "Body module %02X".format(a.ext)
        if (a.is29) return generic29Guesses[a.request and 0xFF]?.let { "$it (likely)" }
        val toyota = maker == "Lexus" || maker == "Toyota"
        val guess = (if (toyota) toyotaGuesses else genericGuesses)[a.request] ?: return null
        return if (a.request == 0x7E0) guess else "$guess (likely)"
    }
}

/**
 * Runs a module scan using the adapter. begin() before, end() after.
 * Uses the same ElmClient as the rest of the app, so it never talks over another task.
 */
class ModuleScanner(private val elm: ElmClient) {
    private var currentExt: Int? = null
    private var cpSet = false

    /** Speeds up the scan: short reply timeout, fixed timing. */
    suspend fun begin() {
        elm.send("ATH0", quiet = true)
        elm.send("ATAT0", quiet = true)
        elm.send("ATST19", quiet = true)   // wait ~100 ms for a reply instead of ~200 ms
        currentExt = null
        cpSet = false
    }

    /** Puts the adapter back to normal settings. */
    suspend fun end() {
        runCatching { elm.send("ATCRA", quiet = true) }
        if (currentExt != null) runCatching { elm.send("ATCEA", quiet = true) }
        runCatching { elm.send("ATST32", quiet = true) }
        runCatching { elm.send("ATAT1", quiet = true) }
        runCatching { elm.send(elm.resetHeaderCommand(), quiet = true) }
    }

    /** Points the adapter at one module. Returns false if the adapter can't do that addressing. */
    private suspend fun target(a: ModuleAddress): Boolean {
        if (a.is29) {
            if (!cpSet) { elm.send("ATCP18", quiet = true); cpSet = true }
            elm.send("ATSHDA%02XF1".format(a.request and 0xFF), quiet = true)
            elm.send("ATCRA18DAF1%02X".format(a.request and 0xFF), quiet = true)
        } else {
            elm.send("ATSH%03X".format(a.request), quiet = true)
            elm.send("ATCRA%03X".format(a.response), quiet = true)
        }
        if (a.ext != currentExt) {
            val r = elm.send(if (a.ext != null) "ATCEA%02X".format(a.ext) else "ATCEA", quiet = true)
            if (a.ext != null && "?" in r) return false   // older/clone adapter: no extended addressing
            currentExt = a.ext
        }
        return true
    }

    /** Sends a request to the currently targeted module; returns clean reply lines, or empty if silent. */
    private suspend fun ask(cmd: String, timeout: Long = 1_500): List<String> =
        try { Hex.dataLines(elm.send(cmd, timeout, quiet = true)) } catch (_: ElmTimeout) { emptyList() }

    /** Finds tag at the start of a reply line, also allowing for a leading sub-address byte. */
    private fun after(line: String, tag: String): String? = when {
        line.startsWith(tag) -> line.substring(tag.length)
        line.length > 2 && line.substring(2).startsWith(tag) -> line.substring(2 + tag.length)
        else -> null
    }

    /** "Are you there?" Any reply at all, even a refusal, means a module lives here. */
    suspend fun probe(a: ModuleAddress): Boolean = target(a) && ask("3E00").isNotEmpty()

    /** Asks the module for its own name (UDS identifier F197). Many modules answer; some don't. */
    suspend fun readName(a: ModuleAddress): String? {
        if (!target(a)) return null
        for (l in ask("22F197")) {
            val p = after(l, "62F197") ?: continue
            val s = Hex.bytes(p).map { it.toChar() }.filter { it.code in 32..126 }.joinToString("").trim()
            if (s.length >= 2) return s
        }
        return null
    }

    /** Reads codes, trying UDS, then KWP2000, then Toyota's older request. Null if none worked. */
    suspend fun readCodes(a: ModuleAddress): Pair<DiagProtocol, List<ModuleCode>>? {
        if (!target(a)) return null

        // UDS: 19 02 FF = "report all codes with any status". Reply 59 02 <mask> then 4 bytes per code.
        for (l in ask("1902FF", 4_000)) {
            val p = after(l, "5902") ?: continue
            val codes = p.drop(2).chunked(8).filter { it.length == 8 }.mapNotNull { r ->
                val code = DtcDecoder.decode(r.substring(0, 4)) ?: return@mapNotNull null
                val ft = r.substring(4, 6).toInt(16)
                val st = r.substring(6, 8).toInt(16)
                if (st and 0xAF == 0) null else ModuleCode(code, st, ft)   // skip "test not run yet" entries
            }
            return DiagProtocol.UDS to codes.distinctBy { it.display }
        }

        // KWP2000: 18 00 FF 00. Reply 58 <count> then 3 bytes per code (2 code bytes + status).
        for (l in ask("1800FF00", 4_000)) {
            val p = after(l, "58") ?: continue
            val codes = p.drop(2).chunked(6).filter { it.length == 6 }.mapNotNull { r ->
                DtcDecoder.decode(r.substring(0, 4))?.let { ModuleCode(it, null, null) }
            }
            return DiagProtocol.KWP to codes.distinctBy { it.display }
        }

        // Older Toyota style: 13. Reply 53 <count> then 2 bytes per code.
        for (l in ask("13", 4_000)) {
            val p = after(l, "53") ?: continue
            val codes = p.drop(2).chunked(4).filter { it.length == 4 }.mapNotNull { r ->
                DtcDecoder.decode(r)?.let { ModuleCode(it, null, null) }
            }
            return DiagProtocol.TOYOTA to codes.distinctBy { it.display }
        }
        return null
    }

    /** Clears one module's codes. Returns true if the module confirmed it. */
    suspend fun clear(a: ModuleAddress, protocol: DiagProtocol): Boolean {
        if (!target(a)) return false
        val cmd = if (protocol == DiagProtocol.UDS) "14FFFFFF" else "14FF00"
        val r = try { elm.send(cmd, 6_000) } catch (_: ElmTimeout) { return false }
        return Hex.dataLines(r).any { after(it, "54") != null }
    }

    companion object {
        /**
         * Every address to knock on.
         * Normal cars: 700–7F7 (minus the engine's reply addresses and the broadcast address).
         * deepToyota adds the 256 sub-addresses on Toyota/Lexus's body network (750).
         */
        fun addresses(is29: Boolean, deepToyota: Boolean): List<ModuleAddress> =
            if (is29) (0x00..0xFF).filter { it != 0xF1 && it != 0x33 }.map { ModuleAddress(it, 0, is29 = true) }
            else (0x700..0x7F7).filter { it != 0x7DF && it !in 0x7E8..0x7EF }.map { ModuleAddress(it, it + 8) } +
                (if (deepToyota) (0x00..0xFF).map { ModuleAddress(0x750, 0x758, ext = it) } else emptyList())
    }
}
