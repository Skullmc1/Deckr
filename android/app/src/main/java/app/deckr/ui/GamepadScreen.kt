package app.deckr.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded._3dRotation
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.deckr.DeckrViewModel
import app.deckr.GyroAim
import kotlin.math.roundToInt
import app.deckr.Mode
import app.deckr.ble.PadState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Landscape controller. Controls are placed by fractions of the screen so the layout stays
 * spacious on any phone: shoulders in the top corners, D-pad and face buttons up high,
 * sticks lower and closer to the middle, small system buttons in the centre.
 */
@Composable
fun GamepadScreen(vm: DeckrViewModel) {
    UnbufferedTouch()
    BackHandler { vm.setMode(Mode.Touchpad) }

    // Gyro aim runs only while this screen is showing and the app is in front.
    val context = LocalContext.current
    val gyro = remember { GyroAim(context, vm) }
    LifecycleResumeEffect(vm.gyroOn) {
        if (vm.gyroOn) gyro.start()
        onPauseOrDispose { gyro.stop() }
    }
    var gyroSettings by remember { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(DeckrColors.Bg),
    ) {
        val w = maxWidth
        val h = maxHeight
        val unit = min(w.value, h.value * 1.9f).dp // scales controls with the screen
        val stick = unit * 0.21f
        val cluster = unit * 0.22f

        fun Modifier.at(fx: Float, fy: Float, size: Dp) =
            this.offset(x = w * fx - size / 2, y = h * fy - size / 2)

        fun Modifier.at(fx: Float, fy: Float, width: Dp, height: Dp) =
            this.offset(x = w * fx - width / 2, y = h * fy - height / 2)

        // Shoulders
        val shW = unit * 0.15f
        val shH = unit * 0.055f
        Shoulder("L2", Modifier.at(0.11f, 0.08f, shW, shH).size(shW, shH)) { vm.l2(if (it) 1f else 0f) }
        Shoulder("L1", Modifier.at(0.11f, 0.08f + 0.14f, shW, shH).size(shW, shH)) { vm.padButton(PadState.L1, it) }
        Shoulder("R2", Modifier.at(0.89f, 0.08f, shW, shH).size(shW, shH)) { vm.r2(if (it) 1f else 0f) }
        Shoulder("R1", Modifier.at(0.89f, 0.08f + 0.14f, shW, shH).size(shW, shH)) { vm.padButton(PadState.R1, it) }

        // Left side: D-pad up, stick lower and inward
        DPad(Modifier.at(0.14f, 0.55f, cluster).size(cluster)) { bit, down -> vm.padDpad(bit, down) }
        Joystick(
            Modifier.at(0.33f, 0.74f, stick).size(stick),
            onMove = vm::leftStick,
            onClick = { vm.padButton(PadState.L3, it) },
        )

        // Right side: face buttons up, stick lower and inward
        FaceButtons(Modifier.at(0.86f, 0.55f, cluster).size(cluster)) { bit, down -> vm.padButton(bit, down) }
        Joystick(
            Modifier.at(0.67f, 0.74f, stick).size(stick),
            onMove = vm::rightStick,
            onClick = { vm.padButton(PadState.R3, it) },
        )

        // Centre: touchpad, Create / Options, PS
        val tpW = unit * 0.22f
        val tpH = unit * 0.09f
        PadTouchpad(Modifier.at(0.5f, 0.2f, tpW, tpH).size(tpW, tpH)) { vm.padButton(PadState.TOUCHPAD, it) }
        val small = unit * 0.055f
        SmallButton("Create", Modifier.at(0.36f, 0.2f, small * 1.8f, small).size(small * 1.8f, small)) {
            vm.padButton(PadState.CREATE, it)
        }
        SmallButton("Options", Modifier.at(0.64f, 0.2f, small * 1.8f, small).size(small * 1.8f, small)) {
            vm.padButton(PadState.OPTIONS, it)
        }
        PsButton(Modifier.at(0.5f, 0.42f, small * 1.2f).size(small * 1.2f)) { vm.padButton(PadState.PS, it) }

        // Bottom row: back to touchpad, gyro aim on/off, gyro settings
        Box(Modifier.at(0.42f, 0.92f, 44.dp).size(44.dp), contentAlignment = Alignment.Center) {
            RoundIcon(Icons.Rounded.TouchApp, "Touchpad mode") { vm.setMode(Mode.Touchpad) }
        }
        Box(Modifier.at(0.5f, 0.92f, 44.dp).size(44.dp), contentAlignment = Alignment.Center) {
            RoundIcon(Icons.Rounded._3dRotation, if (vm.gyroOn) "Turn off gyro aim" else "Turn on gyro aim", active = vm.gyroOn) {
                if (gyro.available) vm.changeGyro(!vm.gyroOn)
                else Toast.makeText(context, "This phone has no gyroscope.", Toast.LENGTH_SHORT).show()
            }
        }
        Box(Modifier.at(0.58f, 0.92f, 44.dp).size(44.dp), contentAlignment = Alignment.Center) {
            RoundIcon(Icons.Rounded.Tune, "Gyro aim settings") { gyroSettings = true }
        }
    }

    if (gyroSettings) GyroSettings(vm) { gyroSettings = false }
}

// ============================================================================ gyro settings

@Composable
private fun GyroSettings(vm: DeckrViewModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(DeckrColors.Surface)
                .border(1.dp, DeckrColors.Outline, RoundedCornerShape(20.dp))
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gyro aim", color = DeckrColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                AccentSwitch(vm.gyroOn, vm::changeGyro)
            }
            Text(
                "Turn and tilt the phone to aim, like motion aiming on a console controller.",
                color = DeckrColors.TextDim, fontSize = 13.sp,
            )

            Setting("Aim with", if (vm.gyroToStick) "For games that only read the controller." else "Most precise. Works in most PC shooters.") {
                Choice(listOf("Mouse", "Right stick"), if (vm.gyroToStick) 1 else 0) { vm.changeGyroToStick(it == 1) }
            }
            Setting("Active", if (vm.gyroOnlyWithL2) "Aims only while you hold L2, like aiming down sights." else "Aims whenever gyro is on.") {
                Choice(listOf("Always", "Holding L2"), if (vm.gyroOnlyWithL2) 1 else 0) { vm.changeGyroOnlyWithL2(it == 1) }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sensitivity", color = DeckrColors.Text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Text("%.1fx".format(vm.gyroSensitivity), color = DeckrColors.TextDim, fontSize = 13.sp)
                }
                Slider(
                    value = vm.gyroSensitivity,
                    onValueChange = { vm.changeGyroSensitivity((it * 10).roundToInt() / 10f) },
                    valueRange = 0.3f..4f,
                    colors = SliderDefaults.colors(thumbColor = DeckrColors.Accent, activeTrackColor = DeckrColors.Accent),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Invert up and down", color = DeckrColors.Text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                AccentSwitch(vm.gyroInvertY, vm::changeGyroInvertY)
            }
        }
    }
}

@Composable
private fun AccentSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = DeckrColors.Accent, checkedThumbColor = DeckrColors.Bg),
    )
}

@Composable
private fun Setting(title: String, hint: String, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = DeckrColors.Text, fontWeight = FontWeight.Medium)
        control()
        Text(hint, color = DeckrColors.TextDim, fontSize = 12.sp)
    }
}

@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DeckrColors.Bg)
            .border(1.dp, DeckrColors.Outline, shape)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (on) DeckrColors.SurfaceHigh else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) DeckrColors.Text else DeckrColors.TextDim, fontSize = 13.sp) }
        }
    }
}

// ============================================================================ building blocks

/** Reports press/release of a single pointer on this element, with a light haptic tick. */
private fun Modifier.pressable(onPress: (Boolean) -> Unit): Modifier = composed {
    val view = LocalView.current
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPress(true)
            do {
                val e = awaitPointerEvent()
                e.changes.forEach { it.consume() }
            } while (e.changes.any { it.pressed })
            onPress(false)
        }
    }
}

@Composable
private fun Shoulder(label: String, modifier: Modifier, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (down) DeckrColors.Accent.copy(alpha = 0.35f) else DeckrColors.Surface)
            .border(1.dp, if (down) DeckrColors.Accent else DeckrColors.Outline, shape)
            .pressable { down = it; onPress(it) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = DeckrColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

@Composable
private fun SmallButton(label: String, modifier: Modifier, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .clip(shape)
            .background(if (down) DeckrColors.SurfaceHigh else DeckrColors.Surface)
            .border(1.dp, DeckrColors.Outline, shape)
            .pressable { down = it; onPress(it) },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = DeckrColors.TextDim, fontSize = 11.sp) }
}

@Composable
private fun PsButton(modifier: Modifier, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier
            .clip(CircleShape)
            .background(if (down) DeckrColors.Accent else DeckrColors.SurfaceHigh)
            .border(1.dp, DeckrColors.Outline, CircleShape)
            .pressable { down = it; onPress(it) },
        contentAlignment = Alignment.Center,
    ) { Text("PS", color = DeckrColors.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun PadTouchpad(modifier: Modifier, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (down) DeckrColors.SurfaceHigh else DeckrColors.Surface)
            .border(1.dp, DeckrColors.Outline, shape)
            .pressable { down = it; onPress(it) },
    )
}

// ---------------------------------------------------------------------------- D-pad

@Composable
private fun DPad(modifier: Modifier, onDir: (bit: Int, down: Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(0) }
    val view = LocalView.current

    Canvas(
        modifier.pointerInput(Unit) {
            // One finger can roll between directions (and diagonals) without lifting.
            awaitEachGesture {
                var current = 0
                fun apply(next: Int) {
                    if (next == current) return
                    for (bit in listOf(PadState.DPAD_UP, PadState.DPAD_RIGHT, PadState.DPAD_DOWN, PadState.DPAD_LEFT)) {
                        val was = current and bit != 0
                        val now = next and bit != 0
                        if (was != now) onDir(bit, now)
                    }
                    if (next != 0 && next != current) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    current = next
                    pressed = next
                }

                fun dirAt(p: Offset): Int {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val d = p - c
                    val dead = size.width * 0.12f
                    var bits = 0
                    if (d.y < -dead) bits = bits or PadState.DPAD_UP
                    if (d.y > dead) bits = bits or PadState.DPAD_DOWN
                    if (d.x < -dead) bits = bits or PadState.DPAD_LEFT
                    if (d.x > dead) bits = bits or PadState.DPAD_RIGHT
                    // Only allow a diagonal when the finger is clearly between two arms.
                    if (bits.countOneBits() == 2) {
                        val ratio = kotlin.math.abs(d.x) / (kotlin.math.abs(d.y) + 0.001f)
                        if (ratio > 2.2f) bits = bits and (PadState.DPAD_UP or PadState.DPAD_DOWN).inv()
                        else if (ratio < 0.45f) bits = bits and (PadState.DPAD_LEFT or PadState.DPAD_RIGHT).inv()
                    }
                    return bits
                }

                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                apply(dirAt(down.position))
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    if (!c.pressed) break
                    apply(dirAt(c.position))
                }
                apply(0)
            }
        },
    ) {
        val s = size.width
        val arm = s * 0.34f
        val r = s * 0.06f
        val mid = (s - arm) / 2f
        fun arm(bit: Int, x: Float, y: Float, w: Float, h: Float) {
            drawRoundRect(
                color = if (pressed and bit != 0) DeckrColors.Accent.copy(alpha = 0.55f) else DeckrColors.Surface,
                topLeft = Offset(x, y), size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
            drawRoundRect(
                color = DeckrColors.Outline, topLeft = Offset(x, y), size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r), style = Stroke(2f),
            )
        }
        arm(PadState.DPAD_UP, mid, 0f, arm, mid + r)
        arm(PadState.DPAD_DOWN, mid, s - mid - r, arm, mid + r)
        arm(PadState.DPAD_LEFT, 0f, mid, mid + r, arm)
        arm(PadState.DPAD_RIGHT, s - mid - r, mid, mid + r, arm)
        drawRect(DeckrColors.Surface, topLeft = Offset(mid + 1, mid + 1), size = Size(arm - 2, arm - 2))
        // little arrows
        val a = arm * 0.16f
        val c = s / 2f
        fun tri(p1: Offset, p2: Offset, p3: Offset) =
            drawPath(Path().apply { moveTo(p1.x, p1.y); lineTo(p2.x, p2.y); lineTo(p3.x, p3.y); close() }, DeckrColors.TextDim)
        tri(Offset(c, mid * 0.45f), Offset(c - a, mid * 0.45f + a), Offset(c + a, mid * 0.45f + a))
        tri(Offset(c, s - mid * 0.45f), Offset(c - a, s - mid * 0.45f - a), Offset(c + a, s - mid * 0.45f - a))
        tri(Offset(mid * 0.45f, c), Offset(mid * 0.45f + a, c - a), Offset(mid * 0.45f + a, c + a))
        tri(Offset(s - mid * 0.45f, c), Offset(s - mid * 0.45f - a, c - a), Offset(s - mid * 0.45f - a, c + a))
    }
}

// ---------------------------------------------------------------------------- face buttons

private enum class Face(val bit: Int, val color: Color, val fx: Float, val fy: Float) {
    Triangle(PadState.TRIANGLE, DeckrColors.Triangle, 0.5f, 0.17f),
    Circle(PadState.CIRCLE, DeckrColors.Circle, 0.83f, 0.5f),
    Cross(PadState.CROSS, DeckrColors.Cross, 0.5f, 0.83f),
    Square(PadState.SQUARE, DeckrColors.Square, 0.17f, 0.5f),
}

@Composable
private fun FaceButtons(modifier: Modifier, onButton: (bit: Int, down: Boolean) -> Unit) {
    BoxWithConstraints(modifier) {
        val b = maxWidth * 0.34f
        Face.entries.forEach { face ->
            FaceButton(
                face,
                Modifier
                    .offset(x = maxWidth * face.fx - b / 2, y = maxHeight * face.fy - b / 2)
                    .size(b),
            ) { onButton(face.bit, it) }
        }
    }
}

@Composable
private fun FaceButton(face: Face, modifier: Modifier, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Canvas(
        modifier
            .clip(CircleShape)
            .pressable { down = it; onPress(it) },
    ) {
        drawCircle(if (down) face.color.copy(alpha = 0.3f) else DeckrColors.Surface)
        drawCircle(if (down) face.color else DeckrColors.Outline, style = Stroke(2.5f))
        drawSymbol(face, if (down) Color.White else face.color)
    }
}

private fun DrawScope.drawSymbol(face: Face, color: Color) {
    val s = size.width
    val c = Offset(s / 2f, s / 2f)
    val r = s * 0.2f
    val stroke = Stroke(width = s * 0.06f)
    when (face) {
        Face.Triangle -> drawPath(
            Path().apply {
                moveTo(c.x, c.y - r)
                lineTo(c.x + r * 0.95f, c.y + r * 0.65f)
                lineTo(c.x - r * 0.95f, c.y + r * 0.65f)
                close()
            },
            color, style = stroke,
        )
        Face.Circle -> drawCircle(color, radius = r, center = c, style = stroke)
        Face.Cross -> {
            drawLine(color, Offset(c.x - r, c.y - r), Offset(c.x + r, c.y + r), stroke.width)
            drawLine(color, Offset(c.x + r, c.y - r), Offset(c.x - r, c.y + r), stroke.width)
        }
        Face.Square -> drawRect(color, Offset(c.x - r * 0.85f, c.y - r * 0.85f), Size(r * 1.7f, r * 1.7f), style = stroke)
    }
}

// ---------------------------------------------------------------------------- joystick

/**
 * Touch anywhere on the base and drag; the knob follows up to the rim. Output is -1..1 on each
 * axis with a small dead zone. A quick tap without moving clicks the stick (L3/R3).
 */
@Composable
private fun Joystick(
    modifier: Modifier,
    onMove: (Float, Float) -> Unit,
    onClick: (Boolean) -> Unit,
) {
    var knob by remember { mutableStateOf(Offset.Zero) } // -1..1
    var active by remember { mutableStateOf(false) }
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                active = true
                val radius = size.width / 2f
                val center = Offset(radius, radius)
                val startTime = down.uptimeMillis
                var maxTravel = 0f

                fun update(p: Offset) {
                    var v = (p - center) / radius
                    val len = v.getDistance()
                    if (len > 1f) v /= len
                    maxTravel = maxOf(maxTravel, v.getDistance())
                    knob = v
                    val dead = 0.08f
                    val out = if (v.getDistance() < dead) Offset.Zero else v
                    onMove(out.x, out.y)
                }
                update(down.position)

                var endTime = startTime
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    if (!c.pressed) { endTime = c.uptimeMillis; break }
                    update(c.position)
                }
                knob = Offset.Zero
                active = false
                onMove(0f, 0f)

                // Tap with almost no movement = stick click, held briefly so games notice it
                if (endTime - startTime < 220 && maxTravel < 0.35f) {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    scope.launch {
                        onClick(true)
                        delay(90)
                        onClick(false)
                    }
                }
            }
        },
    ) {
        val r = size.width / 2f
        val c = Offset(r, r)
        drawCircle(DeckrColors.Surface, r)
        drawCircle(if (active) DeckrColors.Accent.copy(alpha = 0.6f) else DeckrColors.Outline, r, style = Stroke(2.5f))
        drawCircle(DeckrColors.Outline, r * 0.55f, style = Stroke(1.5f))
        val kr = r * 0.42f
        val kc = c + knob * (r - kr)
        drawCircle(if (active) DeckrColors.Accent else DeckrColors.SurfaceHigh, kr, kc)
        drawCircle(Color.White.copy(alpha = 0.08f), kr * 0.7f, kc)
    }
}
