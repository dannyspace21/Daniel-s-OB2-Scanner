package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * Describes everything the car drawing can show, and what the car is doing
 * right now. The drawing on Home reads a CarState and animates to match it.
 *
 * Each "Element" is one thing that can change: a light, a door, a window...
 *   Switch   - on or off (headlights, dome light, engine running)
 *   Position - 0 = closed, 1 = fully open, anything between (windows, sunroof)
 *   Pulse    - a quick event (horn, garage door button) shown as a short ripple
 *
 * Where the state comes from:
 *   - Live view: body data signals you've set up for your car (BodySignals.kt)
 *   - Active tests: the "Shows on car" setting
 *   - The preview buttons on the Vehicle screen
 *   - Engine running: read automatically from engine rpm
 */
enum class Element(val key: String, val label: String, val kind: Kind, val group: String) {
    HeadlightL("headlight_l", "Left headlight", Kind.Switch, "Lights"),
    HeadlightR("headlight_r", "Right headlight", Kind.Switch, "Lights"),
    HighBeam("high_beam", "High beams", Kind.Switch, "Lights"),
    Fog("fog", "Fog lights", Kind.Switch, "Lights"),
    BrakeL("brake_l", "Left brake light", Kind.Switch, "Lights"),
    BrakeR("brake_r", "Right brake light", Kind.Switch, "Lights"),
    Reverse("reverse", "Reverse lights", Kind.Switch, "Lights"),
    TurnL("turn_l", "Left turn signal", Kind.Switch, "Lights"),
    TurnR("turn_r", "Right turn signal", Kind.Switch, "Lights"),
    Hazard("hazard", "Hazard lights", Kind.Switch, "Lights"),
    Dome("dome", "Dome / interior light", Kind.Switch, "Lights"),
    DoorFL("door_fl", "Driver door", Kind.Position, "Doors"),
    DoorFR("door_fr", "Passenger door", Kind.Position, "Doors"),
    DoorRL("door_rl", "Rear left door", Kind.Position, "Doors"),
    DoorRR("door_rr", "Rear right door", Kind.Position, "Doors"),
    Trunk("trunk", "Trunk / tailgate", Kind.Position, "Doors"),
    Hood("hood", "Hood", Kind.Position, "Doors"),
    Locked("locked", "Doors locked", Kind.Switch, "Doors"),
    WindowFL("window_fl", "Driver window", Kind.Position, "Windows"),
    WindowFR("window_fr", "Passenger window", Kind.Position, "Windows"),
    WindowRL("window_rl", "Rear left window", Kind.Position, "Windows"),
    WindowRR("window_rr", "Rear right window", Kind.Position, "Windows"),
    Sunroof("sunroof", "Sunroof", Kind.Position, "Windows"),
    Wipers("wipers", "Wipers", Kind.Switch, "Other"),
    Horn("horn", "Horn", Kind.Pulse, "Other"),
    HomeLink("homelink", "Garage door button", Kind.Pulse, "Other"),
    Engine("engine", "Engine running", Kind.Switch, "Other");

    enum class Kind { Switch, Position, Pulse }

    companion object {
        fun byKey(key: String): Element? = entries.firstOrNull { it.key == key }
        val doors = listOf(DoorFL, DoorFR, DoorRL, DoorRR)
        val windows = listOf(WindowFL, WindowFR, WindowRL, WindowRR)
    }
}

/**
 * A snapshot of the car for the drawing.
 * values      each element's value (0..1); missing means off / closed
 * failed      lights reported as burned out (drawn dark with a red mark)
 * known       elements that are actually being read from the car (for status chips)
 * blinkMs     turn-signal cycle time, measured from your car (default 85 flashes a minute)
 * blinkAnchor when the car's last flash started, so ours flash in step with it
 * pulses      when each Pulse element last fired (horn, garage door button)
 */
data class CarState(
    val values: Map<Element, Float> = emptyMap(),
    val failed: Set<Element> = emptySet(),
    val known: Set<Element> = emptySet(),
    val blinkMs: Long = DEFAULT_BLINK_MS,
    val blinkAnchor: Long = 0L,
    val pulses: Map<Element, Long> = emptyMap(),
) {
    operator fun get(e: Element): Float = values[e] ?: 0f
    fun on(e: Element): Boolean = get(e) > 0.5f
    fun with(e: Element, v: Float): CarState = copy(values = values + (e to v.coerceIn(0f, 1f)))

    val turnLeft: Boolean get() = on(Element.TurnL) || on(Element.Hazard)
    val turnRight: Boolean get() = on(Element.TurnR) || on(Element.Hazard)

    /** True while something needs smooth, continuous animation (blinking, wipers, exhaust, ripples). */
    fun needsClock(now: Long): Boolean =
        turnLeft || turnRight || on(Element.Wipers) || on(Element.Engine) ||
            pulses.values.any { now - it < PULSE_MS }

    /** Short status phrases for the chips under the car, most important first. */
    fun statusChips(): List<String> {
        val out = mutableListOf<String>()
        val openDoors = Element.doors.count { on(it) }
        if (openDoors > 0) out += if (openDoors == 1) "Door open" else "$openDoors doors open"
        if (on(Element.Trunk)) out += "Trunk open"
        if (on(Element.Hood)) out += "Hood open"
        val openWindows = Element.windows.count { get(it) > 0.1f }
        if (openWindows > 0) out += if (openWindows == 1) "Window open" else "$openWindows windows open"
        if (get(Element.Sunroof) > 0.1f) out += "Sunroof open"
        if (Element.Locked in known) out += if (on(Element.Locked)) "Locked" else "Unlocked"
        failed.forEach { out += "Bulb out: ${it.label.lowercase()}" }
        if (on(Element.Hazard)) out += "Hazards on"
        else {
            if (on(Element.TurnL)) out += "Left signal"
            if (on(Element.TurnR)) out += "Right signal"
        }
        if (on(Element.HighBeam)) out += "High beams"
        else if (on(Element.HeadlightL) || on(Element.HeadlightR)) out += "Headlights on"
        if (on(Element.Dome)) out += "Interior light on"
        if (on(Element.Engine)) out += "Engine running"
        return out
    }

    companion object {
        const val DEFAULT_BLINK_MS = 706L   // 85 flashes per minute, typical for most cars
        const val PULSE_MS = 1_800L         // how long a horn / garage-button ripple shows
    }
}

/**
 * Watches a turn-signal reading over time.
 * The car reports the bulb going on and off; we turn that into
 * "signal active" (steady, no flicker from polling) plus the measured flash rate
 * and the moment of the last flash, so the drawing can blink in step with the car.
 */
class BlinkTracker {
    private val rises = ArrayDeque<Long>()
    private var last = false
    private var lastChange = 0L

    /** Returns (active, measured cycle ms or null, time of last flash start or null). */
    fun feed(on: Boolean, now: Long): Triple<Boolean, Long?, Long?> {
        if (rises.isNotEmpty() && now - rises.last() > 3_000) rises.clear()
        if (on && !last) {
            rises.addLast(now)
            while (rises.size > 5) rises.removeFirst()
        }
        if (on != last) lastChange = now
        last = on
        val period = if (rises.size >= 2) ((rises.last() - rises.first()) / (rises.size - 1)).takeIf { it in 300..1_500 } else null
        val flashing = period != null && now - rises.last() < period * 2
        return Triple(on || flashing, period, rises.lastOrNull())
    }
}

/**
 * Helps you find which byte and bit of a reply changes when you do something
 * (open a door, switch on a light). Feed it each reply; it remembers which bytes
 * and bits changed in the last few seconds so the screen can highlight them.
 */
class WatchTracker(private val windowMs: Long = 2_500) {
    private var previous: List<Int> = emptyList()
    private val byteChanged = mutableMapOf<Int, Long>()
    private val bitChanged = mutableMapOf<Pair<Int, Int>, Long>()
    var current: List<Int> = emptyList(); private set

    fun feed(bytes: List<Int>, now: Long) {
        if (previous.size == bytes.size) {
            bytes.forEachIndexed { i, b ->
                val diff = b xor previous[i]
                if (diff != 0) {
                    byteChanged[i] = now
                    for (bit in 0..7) if ((diff shr bit) and 1 == 1) bitChanged[i to bit] = now
                }
            }
        }
        previous = bytes
        current = bytes
    }

    fun changedBytes(now: Long): Set<Int> = byteChanged.filterValues { now - it < windowMs }.keys
    fun changedBits(byte: Int, now: Long): Set<Int> =
        bitChanged.filter { it.key.first == byte && now - it.value < windowMs }.keys.map { it.second }.toSet()
}
