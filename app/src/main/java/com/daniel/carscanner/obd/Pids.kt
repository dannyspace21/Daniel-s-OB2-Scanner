package com.daniel.carscanner.obd

/*
 * WHAT THIS FILE DOES
 * Lists every live sensor value ("PID") the app knows how to read, and how to
 * turn the car's raw bytes into a real number like 195 °F or 14.2 V.
 *
 * Every car supports a different subset. The app asks the car which ones it
 * has when it connects and hides the rest.
 */

/**
 * One live sensor value.
 *
 * code    the PID number the car uses for it (for example 0x0C = engine speed)
 * name    what we show on screen
 * unit    the unit after converting to US units
 * bytes   how many data bytes the car sends back for it
 * min/max the range used to draw the gauge arc
 * about   one plain-English sentence explaining what it measures
 * normal  what a healthy reading usually looks like
 * decode  the formula that turns the raw bytes into the number
 */
class Pid(
    val code: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val min: Double,
    val max: Double,
    val about: String,
    val normal: String,
    val decode: (List<Int>) -> Double,
)

/** Converts Celsius (what cars report) to Fahrenheit. */
private fun f(c: Int) = c * 9.0 / 5.0 + 32.0

/** Fuel trim formula: 128 means 0%, below means removing fuel, above means adding fuel. */
private fun trim(a: Int) = (a - 128) * 100.0 / 128

/** Wideband (air-fuel ratio) sensor formula. 1.00 is the perfect mixture. */
private fun lambda(a: Int, b: Int) = (a * 256 + b) * 2.0 / 65536

object Pids {
    private const val TRIM_ABOUT = "How much fuel the computer is adding (+) or taking away (−) to keep the mixture right."
    private const val LAMBDA_ABOUT = "Upstream air-fuel ratio sensor. Above 1.00 means lean (extra air), below means rich (extra fuel)."
    private const val LAMBDA_NORMAL = "About 0.97–1.03 at warm idle."

    val all = listOf(
        Pid(0x0C, "Engine speed", "rpm", 2, 0.0, 8000.0,
            "How fast the engine is turning.",
            "Warm idle is about 550–850 rpm on most engines.") { (a, b) -> (a * 256 + b) / 4.0 },
        Pid(0x0D, "Vehicle speed", "mph", 1, 0.0, 160.0,
            "Road speed as the engine computer sees it.",
            "Should match your speedometer.") { (a) -> a * 0.621371 },
        Pid(0x05, "Coolant temp", "°F", 1, 0.0, 260.0,
            "Engine coolant temperature.",
            "About 190–220 °F once warm.") { (a) -> f(a - 40) },
        Pid(0x5C, "Oil temp", "°F", 1, 0.0, 300.0,
            "Engine oil temperature.",
            "About 180–250 °F once warm.") { (a) -> f(a - 40) },
        Pid(0x0F, "Intake air temp", "°F", 1, -40.0, 200.0,
            "Temperature of the air going into the engine.",
            "A bit above the outside temperature. −40 °F means the sensor is unplugged.") { (a) -> f(a - 40) },
        Pid(0x46, "Outside air temp", "°F", 1, -20.0, 120.0,
            "Outside temperature from the car's sensor.",
            "Should match the weather.") { (a) -> f(a - 40) },
        Pid(0x42, "System voltage", "V", 2, 10.0, 16.0,
            "Electrical voltage at the engine computer.",
            "Engine off 12.4–12.9 V. Engine running 13.5–14.8 V.") { (a, b) -> (a * 256 + b) / 1000.0 },
        Pid(0x04, "Engine load", "%", 1, 0.0, 100.0,
            "How hard the engine is working compared to its maximum.",
            "About 15–35% at warm idle.") { (a) -> a * 100.0 / 255 },
        Pid(0x11, "Throttle position", "%", 1, 0.0, 100.0,
            "How far the throttle is open.",
            "Low and steady at idle, rises smoothly with the pedal.") { (a) -> a * 100.0 / 255 },
        Pid(0x10, "Mass air flow", "g/s", 2, 0.0, 300.0,
            "How much air is flowing into the engine.",
            "Rises smoothly with rpm. Should never read zero while running.") { (a, b) -> (a * 256 + b) / 100.0 },
        Pid(0x0B, "Manifold pressure", "psi", 1, 0.0, 30.0,
            "Air pressure inside the intake (absolute). Low means strong vacuum.",
            "About 3–5 psi at warm idle.") { (a) -> a * 0.145038 },
        Pid(0x0E, "Timing advance", "°", 1, -20.0, 50.0,
            "When the spark plugs fire, in degrees before the piston reaches the top.",
            "Usually 5–20° at warm idle. Negative at idle can mean the engine is knocking.") { (a) -> a / 2.0 - 64 },
        Pid(0x06, "Short fuel trim B1", "%", 1, -25.0, 25.0, TRIM_ABOUT, "Within ±10%.") { (a) -> trim(a) },
        Pid(0x07, "Long fuel trim B1", "%", 1, -25.0, 25.0, TRIM_ABOUT, "Within ±10%.") { (a) -> trim(a) },
        Pid(0x08, "Short fuel trim B2", "%", 1, -25.0, 25.0, TRIM_ABOUT, "Within ±10%.") { (a) -> trim(a) },
        Pid(0x09, "Long fuel trim B2", "%", 1, -25.0, 25.0, TRIM_ABOUT, "Within ±10%.") { (a) -> trim(a) },
        // Narrowband oxygen sensors: voltage = first byte ÷ 200.
        Pid(0x14, "O2 upstream B1", "V", 1, 0.0, 1.1,
            "Upstream oxygen sensor, bank 1. Tells the computer if the mixture is rich or lean.",
            "Switches quickly between about 0.1 and 0.9 V when warm.") { (a) -> a / 200.0 },
        Pid(0x15, "O2 downstream B1", "V", 1, 0.0, 1.1,
            "Oxygen sensor after the catalytic converter, bank 1. Used to judge the converter.",
            "Fairly steady around 0.5–0.8 V when warm.") { (a) -> a / 200.0 },
        Pid(0x18, "O2 upstream B2", "V", 1, 0.0, 1.1,
            "Upstream oxygen sensor, bank 2.",
            "Switches quickly between about 0.1 and 0.9 V when warm.") { (a) -> a / 200.0 },
        Pid(0x19, "O2 downstream B2", "V", 1, 0.0, 1.1,
            "Oxygen sensor after the catalytic converter, bank 2.",
            "Fairly steady around 0.5–0.8 V when warm.") { (a) -> a / 200.0 },
        // Wideband air-fuel sensors (most Toyota and Lexus cars use these upstream).
        Pid(0x24, "A/F sensor B1", "λ", 2, 0.7, 1.3, LAMBDA_ABOUT, LAMBDA_NORMAL) { (a, b) -> lambda(a, b) },
        Pid(0x28, "A/F sensor B2", "λ", 2, 0.7, 1.3, LAMBDA_ABOUT, LAMBDA_NORMAL) { (a, b) -> lambda(a, b) },
        Pid(0x34, "A/F ratio B1", "λ", 2, 0.7, 1.3, LAMBDA_ABOUT, LAMBDA_NORMAL) { (a, b) -> lambda(a, b) },
        Pid(0x38, "A/F ratio B2", "λ", 2, 0.7, 1.3, LAMBDA_ABOUT, LAMBDA_NORMAL) { (a, b) -> lambda(a, b) },
        Pid(0x2F, "Fuel level", "%", 1, 0.0, 100.0,
            "How full the tank is.", "Matches the fuel gauge.") { (a) -> a * 100.0 / 255 },
        Pid(0x33, "Barometric pressure", "psi", 1, 10.0, 16.0,
            "Outside air pressure.", "About 14.7 psi at sea level, lower at altitude.") { (a) -> a * 0.145038 },
        Pid(0x1F, "Run time", "s", 2, 0.0, 3600.0,
            "Seconds since the engine started.", "Counts up while running.") { (a, b) -> (a * 256 + b).toDouble() },
        Pid(0x5E, "Fuel rate", "gal/h", 2, 0.0, 10.0,
            "How much fuel the engine is burning right now.",
            "Roughly 0.2–0.6 gal/h at idle.") { (a, b) -> (a * 256 + b) / 20.0 * 0.264172 },
    )

    /** Quick lookup: PID number -> its definition. */
    val byCode = all.associateBy { it.code }
}
