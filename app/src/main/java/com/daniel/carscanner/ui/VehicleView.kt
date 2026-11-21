package com.daniel.carscanner.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import com.daniel.carscanner.net.BodyShape
import com.daniel.carscanner.net.CabSize
import com.daniel.carscanner.obd.CarState
import com.daniel.carscanner.obd.Element

/*
 * WHAT THIS FILE DOES
 * Draws the car seen from above, like the Tesla app, entirely in code (no
 * image files), and animates everything in CarState:
 *   Lights:  left/right headlights with beams, high beams, fog lights,
 *            left/right brake lights, reverse lights, turn signals and hazards
 *            (front, rear and mirror repeaters, blinking at your car's measured
 *            rate and in step with it), dome light, burned-out bulbs marked red
 *   Body:    four doors on their hinges, trunk / liftgate / tailgate, hood with
 *            the engine bay showing, four windows, sunroof sliding back
 *   Other:   wipers sweeping, horn sound waves, garage-door-button ripple,
 *            exhaust puffs while the engine runs
 * The shape follows the VIN: coupe, sedan, hatchback, wagon, SUV, van,
 * pickup (regular, extended or crew cab) or motorcycle, with 2 or 4 doors.
 *
 * Coordinates: the car points UP. Along the car, 0 = front bumper and
 * 1 = rear bumper; across it, 0 = left side and 1 = right side.
 */

/** Paint colors you can choose from on the Vehicle screen. The VIN doesn't record color. */
val PaintColors = listOf(
    "White" to 0xFFEDEEF0, "Silver" to 0xFFB9BDC3, "Gray" to 0xFF6E737A, "Black" to 0xFF1E1F22,
    "Red" to 0xFFB3261E, "Blue" to 0xFF2F5BD3, "Navy" to 0xFF1F2F55, "Green" to 0xFF2E6B4F,
    "Orange" to 0xFFE46A1C, "Yellow" to 0xFFE6B800, "Bronze" to 0xFF7A5A3C,
)

/**
 * Where things sit along each body style, as fractions of the length.
 * ratio length ÷ width; ws0..ws1 windshield; roofEnd end of roof; rear1 end of rear window;
 * axles = wheel centers; bedStart = pickups only; liftgate = rear hatch instead of a trunk lid.
 */
private class Spec(
    val ratio: Float, val ws0: Float, val ws1: Float, val roofEnd: Float, val rear1: Float,
    val frontAxle: Float, val rearAxle: Float, val bedStart: Float? = null, val liftgate: Boolean = false,
)

private fun specFor(shape: BodyShape, cab: CabSize): Spec = when (shape) {
    BodyShape.Coupe -> Spec(2.25f, 0.36f, 0.47f, 0.64f, 0.77f, 0.17f, 0.80f)
    BodyShape.Sedan -> Spec(2.45f, 0.32f, 0.42f, 0.63f, 0.74f, 0.16f, 0.80f)
    BodyShape.Hatchback -> Spec(2.15f, 0.30f, 0.40f, 0.80f, 0.90f, 0.16f, 0.82f, liftgate = true)
    BodyShape.Wagon -> Spec(2.50f, 0.30f, 0.40f, 0.84f, 0.92f, 0.16f, 0.81f, liftgate = true)
    BodyShape.SUV -> Spec(2.30f, 0.28f, 0.38f, 0.84f, 0.93f, 0.17f, 0.81f, liftgate = true)
    BodyShape.Van -> Spec(2.55f, 0.14f, 0.25f, 0.90f, 0.95f, 0.13f, 0.80f, liftgate = true)
    BodyShape.Pickup -> {
        val roofEnd = when (cab) { CabSize.Regular -> 0.43f; CabSize.Extended -> 0.49f; CabSize.Crew -> 0.55f }
        Spec(2.85f, 0.25f, 0.33f, roofEnd, roofEnd + 0.035f, 0.15f, 0.80f, bedStart = roofEnd + 0.06f)
    }
    BodyShape.Motorcycle -> Spec(3.2f, 0f, 0f, 0f, 0f, 0f, 0f)
}

private fun Color.shade(f: Float) = Color((red * f).coerceIn(0f, 1f), (green * f).coerceIn(0f, 1f), (blue * f).coerceIn(0f, 1f), alpha)
private fun lerpColor(a: Color, b: Color, t: Float) = Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f)

private val Glass = Color(0xFF1C2129)
private val Tire = Color(0xFF0B0B0C)
private val Beam = Color(0xFFFFF4D6)
private val Amber = Color(0xFFFFA726)
private val BrakeRed = Color(0xFFFF3B30)
private val Interior = Color(0xFF3B3E44)
private val Ripple = Color(0xFF5B8CFF)

/** Converts car fractions into screen pixels. */
private class Geo(val x0: Float, val y0: Float, val w: Float, val l: Float) {
    fun x(f: Float) = x0 + f * w
    fun y(f: Float) = y0 + f * l
}

/**
 * The car drawing.
 * state = what to show (see CarState.kt). dim = faded placeholder when no car is known yet.
 */
@Composable
fun VehicleView(
    shape: BodyShape,
    doors: Int,
    cab: CabSize,
    paint: Color,
    state: CarState,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
) {
    // Every element gets its own smooth animation: lights fade, doors and windows glide.
    val anim = Element.entries.associateWith { e ->
        val spec = if (e.group == "Lights" || e.kind == Element.Kind.Switch) tween<Float>(320)
        else spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)
        animateFloatAsState(state[e], spec, label = e.key).value
    }

    // A frame clock for things that move continuously (blinking, wipers, ripples, exhaust).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = state.needsClock(now)
    LaunchedEffect(running) {
        if (running) while (true) withFrameMillis { now = System.currentTimeMillis() }
    }

    Canvas(modifier) {
        val spec = specFor(shape, cab)
        val top = size.height * 0.2f                 // room above for headlight beams
        val area = size.height * 0.96f - top
        val length = minOf(area, size.width * spec.ratio / 2.1f)   // leave room at the sides for open doors
        val g = Geo((size.width - length / spec.ratio) / 2f, top + (area - length) / 2f, length / spec.ratio, length)
        val alpha = if (dim) 0.35f else 1f
        val a: (Element) -> Float = { anim[it] ?: 0f }
        val period = state.blinkMs.coerceIn(300L, 1_500L)
        val blinkOn = Math.floorMod(now - state.blinkAnchor, period) < period / 2
        val turnL = state.turnLeft && blinkOn
        val turnR = state.turnRight && blinkOn

        if (shape == BodyShape.Motorcycle) drawMotorcycle(g, paint.copy(alpha = alpha), a, state, turnL, turnR, now)
        else drawCar(g, spec, shape, doors, paint.copy(alpha = alpha), alpha, a, state, turnL, turnR, now)
    }
}

/** Draws any 4-wheeled vehicle. */
private fun DrawScope.drawCar(
    g: Geo, spec: Spec, shape: BodyShape, doorCount: Int, paint: Color, alpha: Float,
    a: (Element) -> Float, st: CarState, turnL: Boolean, turnR: Boolean, now: Long,
) {
    val outline = if (paint.luminance() < 0.15f) Color(0xFF55595F) else paint.shade(0.7f)
    val high = a(Element.HighBeam)

    // ---- 1. Light beams on the ground (behind everything) ----
    for ((side, lx) in listOf(Element.HeadlightL to g.x(0.22f), Element.HeadlightR to g.x(0.78f))) {
        val on = maxOf(a(side), high)
        if (on > 0.01f && side !in st.failed) {
            val reach = size.height * (0.12f + 0.07f * high)
            beam(lx, g.y(0.03f), g.y0 - reach, g.w * (0.32f - 0.08f * high), (0.3f + 0.15f * high) * on)
        }
    }
    a(Element.Fog).takeIf { it > 0.01f }?.let { f ->
        for (lx in listOf(g.x(0.12f), g.x(0.88f))) beam(lx, g.y(0.01f), g.y0 - size.height * 0.06f, g.w * 0.45f, 0.25f * f)
    }
    a(Element.Reverse).takeIf { it > 0.01f }?.let { r ->
        val from = g.y(1f); val to = minOf(size.height, from + size.height * 0.05f)
        drawRect(Brush.verticalGradient(listOf(Beam.copy(alpha = 0.35f * r), Color.Transparent), startY = from, endY = to),
            Offset(g.x(0.15f), from), Size(g.w * 0.7f, to - from))
    }

    // ---- 2. Wheels ----
    val tw = g.w * 0.13f; val tl = g.l * 0.15f
    for (axle in listOf(spec.frontAxle, spec.rearAxle)) {
        val cy = g.y(axle) - tl / 2
        drawRoundRect(Tire.copy(alpha = alpha), Offset(g.x0 - tw * 0.35f, cy), Size(tw, tl), CornerRadius(tw * 0.3f))
        drawRoundRect(Tire.copy(alpha = alpha), Offset(g.x0 + g.w - tw * 0.65f, cy), Size(tw, tl), CornerRadius(tw * 0.3f))
    }

    // ---- 3. Openings where doors have swung away ----
    val spans = doorSpans(spec, shape, doorCount)
    spans.forEach { (door, span) ->
        val open = a(door)
        if (open > 0.01f) {
            val left = door == Element.DoorFL || door == Element.DoorRL
            drawRect(Interior.copy(alpha = alpha * open), Offset(if (left) g.x0 else g.x(0.93f), g.y(span.first)),
                Size(g.w * 0.07f, g.y(span.second) - g.y(span.first)))
        }
    }

    // ---- 4. Body shell ----
    val body = Path().apply {
        addRoundRect(RoundRect(
            left = g.x0, top = g.y0, right = g.x0 + g.w, bottom = g.y0 + g.l,
            topLeftCornerRadius = CornerRadius(g.w * 0.30f, g.w * 0.34f), topRightCornerRadius = CornerRadius(g.w * 0.30f, g.w * 0.34f),
            bottomLeftCornerRadius = CornerRadius(g.w * 0.20f, g.w * 0.18f), bottomRightCornerRadius = CornerRadius(g.w * 0.20f, g.w * 0.18f),
        ))
    }
    drawPath(body, paint)
    drawPath(body, Brush.horizontalGradient(
        listOf(Color.Black.copy(alpha = 0.22f * alpha), Color.Transparent, Color.White.copy(alpha = 0.06f * alpha), Color.Transparent, Color.Black.copy(alpha = 0.22f * alpha)),
        startX = g.x0, endX = g.x0 + g.w,
    ))
    drawPath(body, outline.copy(alpha = alpha), style = Stroke(width = g.w * 0.012f))

    // ---- 5. Hood: hinged at the windshield, so it looks shorter as it lifts, showing the engine ----
    val hood = a(Element.Hood)
    val hoodTop = g.y(0.05f); val hoodEnd = g.y(spec.ws0 - 0.005f)
    if (hood > 0.01f) {
        drawRoundRect(Color(0xFF26282C).copy(alpha = alpha * hood), Offset(g.x(0.1f), hoodTop), Size(g.w * 0.8f, hoodEnd - hoodTop), CornerRadius(g.w * 0.08f))
        drawRoundRect(Color(0xFF55585E).copy(alpha = alpha * hood), Offset(g.x(0.28f), g.y(0.1f)), Size(g.w * 0.44f, hoodEnd - g.y(0.14f)), CornerRadius(g.w * 0.04f))
        for (row in 0..1) for (i in 0..2) drawCircle(Color(0xFF8A8E95).copy(alpha = alpha * hood), g.w * 0.035f,
            Offset(g.x(0.36f + 0.14f * i), g.y(0.13f) + (hoodEnd - g.y(0.18f)) * (0.3f + 0.4f * row)))
        val panelTop = hoodTop + (hoodEnd - hoodTop) * 0.75f * hood
        drawRoundRect(lerpColor(paint, Color.White, 0.12f * hood).copy(alpha = alpha), Offset(g.x(0.08f), panelTop), Size(g.w * 0.84f, hoodEnd - panelTop), CornerRadius(g.w * 0.06f))
    }

    // ---- 6. Pickup bed ----
    spec.bedStart?.let { bs ->
        drawRoundRect(paint.shade(0.55f), Offset(g.x(0.1f), g.y(bs)), Size(g.w * 0.8f, g.y(0.955f) - g.y(bs)), CornerRadius(g.w * 0.03f))
    }

    // ---- 7. Glass, roof and windows ----
    val glass = Glass.copy(alpha = alpha)
    trapezoid(g, spec.ws0, spec.ws1, 0.17f, 0.11f, glass)
    drawRect(paint.shade(0.86f), Offset(g.x(0.11f), g.y(spec.ws1)), Size(g.w * 0.78f, g.y(spec.roofEnd) - g.y(spec.ws1)))
    // Side windows: front and rear on each side, lighter when lowered
    val mid = if (doorCount >= 4) spans.first { it.first == Element.DoorRL }.second.first else spec.roofEnd
    for ((win, xs) in listOf(Element.WindowFL to 0.07f, Element.WindowFR to 0.89f, Element.WindowRL to 0.07f, Element.WindowRR to 0.89f)) {
        val rear = win == Element.WindowRL || win == Element.WindowRR
        if (rear && doorCount < 4) continue
        val f0 = if (rear) mid else spec.ws1
        val f1 = if (rear) spec.roofEnd else mid
        drawRect(glass, Offset(g.x(xs), g.y(f0)), Size(g.w * 0.04f, g.y(f1) - g.y(f0)))
        val open = a(win)
        if (open > 0.01f) drawRect(Color(0xFF9DA3AB).copy(alpha = alpha * open), Offset(g.x(xs), g.y(f0)), Size(g.w * 0.04f, (g.y(f1) - g.y(f0)) * open))
    }
    // Sunroof: glass panel slides back to reveal the cabin
    val roofLen = g.y(spec.roofEnd) - g.y(spec.ws1)
    if (roofLen > g.l * 0.12f) {
        val sx = g.x(0.3f); val sw = g.w * 0.4f
        val sy = g.y(spec.ws1) + roofLen * 0.12f; val sh = roofLen * 0.32f
        val open = a(Element.Sunroof)
        drawRoundRect(Interior.copy(alpha = alpha), Offset(sx, sy), Size(sw, sh), CornerRadius(g.w * 0.03f))
        drawRoundRect(glass, Offset(sx, sy + sh * 0.9f * open), Size(sw, sh), CornerRadius(g.w * 0.03f))
    }
    // Dome light: warm glow through the cabin
    a(Element.Dome).takeIf { it > 0.01f }?.let { d ->
        glow(Offset(g.x(0.5f), (g.y(spec.ws1) + g.y(spec.roofEnd)) / 2), g.w * 0.55f, Color(0xFFFFE2A8), 0.6f * d)
        drawCircle(Color(0xFFFFF4D6).copy(alpha = d), g.w * 0.025f, Offset(g.x(0.5f), g.y(spec.ws1) + roofLen * 0.15f))
    }

    // ---- 8. Trunk, liftgate or tailgate ----
    val trunk = a(Element.Trunk)
    when {
        spec.bedStart != null -> {
            // Tailgate drops down and out
            val ty = g.y(0.955f) + g.l * 0.06f * trunk
            drawRoundRect(paint.shade(1f - 0.15f * trunk), Offset(g.x(0.1f), ty), Size(g.w * 0.8f, g.l * 0.03f), CornerRadius(g.w * 0.02f))
        }
        spec.liftgate -> {
            // Liftgate hinged at the roof swings up and back past the bumper
            trapezoid(g, spec.roofEnd, spec.rear1, 0.11f, 0.18f, glass)
            if (trunk > 0.01f) {
                drawRect(Interior.copy(alpha = alpha * trunk), Offset(g.x(0.12f), g.y(spec.roofEnd)), Size(g.w * 0.76f, g.y(0.99f) - g.y(spec.roofEnd)))
                val gateEnd = g.y(spec.rear1) + (g.y(1f) - g.y(spec.rear1)) * (1f + 0.6f * trunk)
                drawRoundRect(lerpColor(paint, Color.White, 0.1f * trunk).copy(alpha = alpha), Offset(g.x(0.1f), g.y(spec.roofEnd)),
                    Size(g.w * 0.8f, gateEnd - g.y(spec.roofEnd)), CornerRadius(g.w * 0.05f))
                drawRect(glass, Offset(g.x(0.18f), g.y(spec.roofEnd) + g.l * 0.01f), Size(g.w * 0.64f, (g.y(spec.rear1) - g.y(spec.roofEnd)) * 0.8f))
            }
        }
        else -> {
            // Trunk lid hinged at the rear window lifts and looks shorter from above
            trapezoid(g, spec.roofEnd, spec.rear1, 0.11f, 0.18f, glass)
            if (trunk > 0.01f) {
                val t0 = g.y(spec.rear1 + 0.005f); val t1 = g.y(0.985f)
                drawRoundRect(Interior.copy(alpha = alpha * trunk), Offset(g.x(0.12f), t0), Size(g.w * 0.76f, t1 - t0), CornerRadius(g.w * 0.05f))
                drawRoundRect(lerpColor(paint, Color.White, 0.12f * trunk).copy(alpha = alpha), Offset(g.x(0.1f), t0),
                    Size(g.w * 0.8f, (t1 - t0) * (1f - 0.7f * trunk)), CornerRadius(g.w * 0.05f))
            }
        }
    }

    // ---- 9. Mirrors with turn-signal repeaters ----
    val my = g.y(spec.ws0 + 0.01f)
    drawOval(paint.shade(0.8f), Offset(g.x0 - g.w * 0.07f, my), Size(g.w * 0.11f, g.l * 0.035f))
    drawOval(paint.shade(0.8f), Offset(g.x0 + g.w * 0.96f, my), Size(g.w * 0.11f, g.l * 0.035f))
    if (turnL) lamp(Offset(g.x0 - g.w * 0.07f, my + g.l * 0.01f), Size(g.w * 0.05f, g.l * 0.015f), Amber, 1f, g.w * 0.15f)
    if (turnR) lamp(Offset(g.x0 + g.w * 1.02f, my + g.l * 0.01f), Size(g.w * 0.05f, g.l * 0.015f), Amber, 1f, g.w * 0.15f)

    // ---- 10. Lamps ----
    val off = Color(0xFFC9CED6)
    val tailOff = Color(0xFF6B1A1A)
    lampFor(Element.HeadlightL, st, Offset(g.x(0.1f), g.y(0.018f)), Size(g.w * 0.22f, g.l * 0.026f), off, Color.White, maxOf(a(Element.HeadlightL), high), g.w * 0.28f, alpha)
    lampFor(Element.HeadlightR, st, Offset(g.x(0.68f), g.y(0.018f)), Size(g.w * 0.22f, g.l * 0.026f), off, Color.White, maxOf(a(Element.HeadlightR), high), g.w * 0.28f, alpha)
    lampFor(Element.TurnL, st, Offset(g.x(0.02f), g.y(0.045f)), Size(g.w * 0.08f, g.l * 0.022f), Color(0xFF7A5A2A), Amber, if (turnL) 1f else 0f, g.w * 0.2f, alpha)
    lampFor(Element.TurnR, st, Offset(g.x(0.9f), g.y(0.045f)), Size(g.w * 0.08f, g.l * 0.022f), Color(0xFF7A5A2A), Amber, if (turnR) 1f else 0f, g.w * 0.2f, alpha)
    lampFor(Element.BrakeL, st, Offset(g.x(0.09f), g.y(0.968f)), Size(g.w * 0.2f, g.l * 0.018f), tailOff, BrakeRed, maxOf(a(Element.BrakeL), 0.25f * a(Element.HeadlightL)), g.w * 0.3f, alpha)
    lampFor(Element.BrakeR, st, Offset(g.x(0.71f), g.y(0.968f)), Size(g.w * 0.2f, g.l * 0.018f), tailOff, BrakeRed, maxOf(a(Element.BrakeR), 0.25f * a(Element.HeadlightR)), g.w * 0.3f, alpha)
    lampFor(Element.TurnL, st, Offset(g.x(0.03f), g.y(0.955f)), Size(g.w * 0.05f, g.l * 0.03f), Color(0xFF7A5A2A), Amber, if (turnL) 1f else 0f, g.w * 0.2f, alpha)
    lampFor(Element.TurnR, st, Offset(g.x(0.92f), g.y(0.955f)), Size(g.w * 0.05f, g.l * 0.03f), Color(0xFF7A5A2A), Amber, if (turnR) 1f else 0f, g.w * 0.2f, alpha)
    lampFor(Element.Reverse, st, Offset(g.x(0.34f), g.y(0.972f)), Size(g.w * 0.09f, g.l * 0.014f), Color(0xFF5A5E66), Color.White, a(Element.Reverse), g.w * 0.2f, alpha)
    lampFor(Element.Reverse, st, Offset(g.x(0.57f), g.y(0.972f)), Size(g.w * 0.09f, g.l * 0.014f), Color(0xFF5A5E66), Color.White, a(Element.Reverse), g.w * 0.2f, alpha)
    a(Element.Fog).takeIf { it > 0.01f }?.let { f ->
        for (lx in listOf(g.x(0.06f), g.x(0.9f))) lamp(Offset(lx, g.y(0.006f)), Size(g.w * 0.04f, g.l * 0.01f), Color(0xFFFFF1C1), f, g.w * 0.15f)
    }

    // ---- 11. Doors: seams when closed, swung out on the front hinge when open ----
    spans.forEach { (door, span) ->
        val left = door == Element.DoorFL || door == Element.DoorRL
        val ys = g.y(span.first); val ye = g.y(span.second)
        val edgeX = if (left) g.x0 else g.x0 + g.w
        val inX = if (left) g.x0 + g.w * 0.07f else g.x0 + g.w * 0.93f
        drawLine(outline.copy(alpha = alpha * 0.9f), Offset(edgeX, ys), Offset(inX, ys), strokeWidth = g.w * 0.01f)
        drawLine(outline.copy(alpha = alpha * 0.9f), Offset(edgeX, ye), Offset(inX, ye), strokeWidth = g.w * 0.01f)
        val open = a(door)
        if (open > 0.01f) {
            val thick = g.w * 0.06f
            rotate(62f * open * (if (left) 1f else -1f), pivot = Offset(edgeX, ys)) {
                val dx = if (left) edgeX - thick else edgeX
                drawRoundRect(paint, Offset(dx, ys), Size(thick, ye - ys), CornerRadius(thick * 0.4f))
                drawRoundRect(outline.copy(alpha = alpha), Offset(dx, ys), Size(thick, ye - ys), CornerRadius(thick * 0.4f), style = Stroke(g.w * 0.01f))
            }
        }
    }

    // ---- 12. Moving effects ----
    if (st.on(Element.Wipers)) {
        val phase = (now % 1_300L) / 1_300f
        val angle = 80f * (if (phase < 0.5f) phase * 2 else 2 - phase * 2)
        for (px in listOf(0.26f, 0.56f)) {
            val pivot = Offset(g.x(px), g.y(spec.ws0 + 0.01f))
            rotate(angle, pivot) {
                drawLine(Color(0xFF111214), pivot, Offset(pivot.x + g.w * 0.32f, pivot.y), strokeWidth = g.w * 0.018f, cap = StrokeCap.Round)
            }
        }
    }
    st.pulses[Element.HomeLink]?.let { t0 ->
        val t = (now - t0) / CarState.PULSE_MS.toFloat()
        if (t in 0f..1f) for (i in 0..2) {
            val tt = (t * 1.6f - i * 0.25f).coerceIn(0f, 1f)
            if (tt > 0f) drawCircle(Ripple.copy(alpha = (1f - tt) * 0.8f), g.w * (0.06f + 0.5f * tt),
                Offset(g.x(0.5f), g.y(spec.ws1 - 0.01f)), style = Stroke(g.w * 0.02f))
        }
    }
    st.pulses[Element.Horn]?.let { t0 ->
        val t = (now - t0) / CarState.PULSE_MS.toFloat()
        if (t in 0f..1f) for (i in 0..2) {
            val tt = (t * 1.6f - i * 0.25f).coerceIn(0f, 1f)
            if (tt > 0f) {
                val r = g.w * (0.2f + 0.6f * tt)
                drawArc(Color(0xFFB0B6BF).copy(alpha = (1f - tt) * 0.8f), 225f, 90f, false,
                    Offset(g.x(0.5f) - r, g.y0 - r * 0.3f - r), Size(r * 2, r * 2), style = Stroke(g.w * 0.02f, cap = StrokeCap.Round))
            }
        }
    }
    if (st.on(Element.Engine)) exhaust(Offset(g.x(0.28f), g.y(1f)), g.w, now)
}

/**
 * Which doors exist and where: door -> (start, end) along the car.
 * 2-door cars get long front doors; 4-door cars split the cabin in two.
 */
private fun doorSpans(spec: Spec, shape: BodyShape, doorCount: Int): List<Pair<Element, Pair<Float, Float>>> {
    if (doorCount <= 0) return emptyList()
    val start = spec.ws0 + 0.02f
    val end = (if (shape == BodyShape.Van) spec.ws1 + 0.35f else spec.roofEnd) - 0.015f
    return if (doorCount < 4) listOf(Element.DoorFL to (start to end), Element.DoorFR to (start to end))
    else {
        val mid = (start + end) / 2f + 0.01f
        listOf(Element.DoorFL to (start to mid), Element.DoorFR to (start to mid), Element.DoorRL to (mid to end), Element.DoorRR to (mid to end))
    }
}

/** A light cone on the ground from (x, fromY) out to toY. */
private fun DrawScope.beam(x: Float, fromY: Float, toY: Float, halfWidth: Float, strength: Float) {
    val p = Path().apply {
        moveTo(x - halfWidth * 0.15f, fromY); lineTo(x - halfWidth, toY); lineTo(x + halfWidth, toY); lineTo(x + halfWidth * 0.15f, fromY); close()
    }
    drawPath(p, Brush.verticalGradient(listOf(Beam.copy(alpha = 0f), Beam.copy(alpha = strength)), startY = toY, endY = fromY))
}

/** A lamp that can be off, on (with glow) or burned out (dark with a red warning dot). */
private fun DrawScope.lampFor(
    e: Element, st: CarState, at: Offset, sz: Size, offColor: Color, onColor: Color, level: Float, glowR: Float, alpha: Float,
) {
    if (e in st.failed) {
        drawRoundRect(Color(0xFF2A2A2E).copy(alpha = alpha), at, sz, CornerRadius(sz.height * 0.4f))
        drawRoundRect(Color(0xFFE8443A), at, sz, CornerRadius(sz.height * 0.4f), style = Stroke(sz.height * 0.25f))
        drawCircle(Color(0xFFE8443A), sz.height * 0.45f, Offset(at.x + sz.width / 2, at.y + sz.height / 2))
        return
    }
    drawRoundRect(lerpColor(offColor, onColor, level).copy(alpha = alpha), at, sz, CornerRadius(sz.height * 0.4f))
    if (level > 0.01f) glow(Offset(at.x + sz.width / 2, at.y + sz.height / 2), glowR, onColor, 0.55f * level)
}

/** A small lamp with glow (used for repeaters and fog lamps). */
private fun DrawScope.lamp(at: Offset, sz: Size, color: Color, level: Float, glowR: Float) {
    drawRoundRect(color.copy(alpha = level), at, sz, CornerRadius(sz.height * 0.4f))
    glow(Offset(at.x + sz.width / 2, at.y + sz.height / 2), glowR, color, 0.5f * level)
}

/** A 4-sided window between two points along the car. inset = how far in from the sides. */
private fun DrawScope.trapezoid(g: Geo, f0: Float, f1: Float, inset0: Float, inset1: Float, color: Color) {
    if (f1 <= f0) return
    val p = Path().apply {
        moveTo(g.x(inset0), g.y(f0)); lineTo(g.x(1f - inset0), g.y(f0))
        lineTo(g.x(1f - inset1), g.y(f1)); lineTo(g.x(inset1), g.y(f1)); close()
    }
    drawPath(p, color)
}

/** A soft round glow. */
private fun DrawScope.glow(center: Offset, radius: Float, color: Color, strength: Float) {
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = strength), Color.Transparent), center, radius), radius, center)
}

/** Little puffs drifting from the tailpipe while the engine runs. */
private fun DrawScope.exhaust(from: Offset, width: Float, now: Long) {
    for (i in 0..2) {
        val t = ((now / 900f) + i / 3f) % 1f
        val room = size.height - from.y
        drawCircle(Color(0xFF9AA0A8).copy(alpha = 0.35f * (1f - t)), width * (0.03f + 0.05f * t),
            Offset(from.x - width * 0.1f * t, from.y + room * 0.8f * t))
    }
}

/** Draws a motorcycle from above: wheels, bars, tank, seat, lights and signals. */
private fun DrawScope.drawMotorcycle(g: Geo, paint: Color, a: (Element) -> Float, st: CarState, turnL: Boolean, turnR: Boolean, now: Long) {
    val cx = g.x0 + g.w / 2
    val head = maxOf(a(Element.HeadlightL), a(Element.HeadlightR), a(Element.HighBeam))
    if (head > 0.01f) beam(cx, g.y(0.17f), g.y0 - size.height * (0.12f + 0.07f * a(Element.HighBeam)), g.w * 0.7f, 0.32f * head)
    drawRoundRect(Tire, Offset(cx - g.w * 0.12f, g.y(0f)), Size(g.w * 0.24f, g.l * 0.2f), CornerRadius(g.w * 0.1f))
    drawRoundRect(Tire, Offset(cx - g.w * 0.16f, g.y(0.74f)), Size(g.w * 0.32f, g.l * 0.26f), CornerRadius(g.w * 0.12f))
    drawLine(Color(0xFF3A3D42), Offset(g.x0, g.y(0.24f)), Offset(g.x0 + g.w, g.y(0.24f)), strokeWidth = g.w * 0.07f)
    drawOval(paint, Offset(cx - g.w * 0.42f, g.y(0.28f)), Size(g.w * 0.84f, g.l * 0.24f))
    drawOval(Color.White.copy(alpha = 0.08f), Offset(cx - g.w * 0.2f, g.y(0.31f)), Size(g.w * 0.3f, g.l * 0.14f))
    drawRoundRect(Color(0xFF222326), Offset(cx - g.w * 0.28f, g.y(0.5f)), Size(g.w * 0.56f, g.l * 0.22f), CornerRadius(g.w * 0.2f))
    drawCircle(lerpColor(Color(0xFFC9CED6), Color.White, head), g.w * 0.12f, Offset(cx, g.y(0.17f)))
    if (head > 0.01f) glow(Offset(cx, g.y(0.17f)), g.w * 0.6f, Beam, 0.55f * head)
    val brake = maxOf(a(Element.BrakeL), a(Element.BrakeR))
    drawRoundRect(lerpColor(Color(0xFF6B1A1A), BrakeRed, maxOf(brake, 0.25f * head)), Offset(cx - g.w * 0.15f, g.y(0.73f)), Size(g.w * 0.3f, g.l * 0.02f), CornerRadius(g.w * 0.05f))
    if (brake > 0.01f) glow(Offset(cx, g.y(0.74f)), g.w * 0.6f, BrakeRed, 0.5f * brake)
    // Signals at the bar ends and beside the tail light
    for ((on, x) in listOf(turnL to g.x0, turnR to g.x0 + g.w)) {
        val c = if (on) Amber else Color(0xFF7A5A2A)
        drawCircle(c, g.w * 0.07f, Offset(x, g.y(0.24f)))
        drawCircle(c, g.w * 0.06f, Offset(if (x == g.x0) cx - g.w * 0.3f else cx + g.w * 0.3f, g.y(0.72f)))
        if (on) { glow(Offset(x, g.y(0.24f)), g.w * 0.35f, Amber, 0.5f); glow(Offset(if (x == g.x0) cx - g.w * 0.3f else cx + g.w * 0.3f, g.y(0.72f)), g.w * 0.3f, Amber, 0.5f) }
    }
    st.pulses[Element.Horn]?.let { t0 ->
        val t = (now - t0) / CarState.PULSE_MS.toFloat()
        if (t in 0f..1f) {
            val r = g.w * (0.5f + 1.2f * t)
            drawArc(Color(0xFFB0B6BF).copy(alpha = (1f - t) * 0.8f), 225f, 90f, false, Offset(cx - r, g.y0 - r * 1.3f), Size(r * 2, r * 2), style = Stroke(g.w * 0.05f))
        }
    }
    if (st.on(Element.Engine)) exhaust(Offset(cx + g.w * 0.2f, g.y(1f)), g.w * 2.5f, now)
}
