package app.deckr.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.deckr.DeckrViewModel
import app.deckr.Mode
import app.deckr.ble.Protocol
import kotlin.math.max

@Composable
fun TouchpadScreen(vm: DeckrViewModel) {
    var keyboardOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = keyboardOpen) { keyboardOpen = false }
    UnbufferedTouch()

    Column(
        Modifier
            .fillMaxSize()
            .background(DeckrColors.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        if (keyboardOpen) {
            KeyboardBar(vm, onClose = { keyboardOpen = false })
        }
        Touchpad(
            vm,
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        ToolStrip(
            keyboardOpen = keyboardOpen,
            onKeyboard = { keyboardOpen = !keyboardOpen },
            onLeft = { vm.link.click(Protocol.MOUSE_LEFT) },
            onRight = { vm.link.click(Protocol.MOUSE_RIGHT) },
            onGamepad = { keyboardOpen = false; vm.setMode(Mode.Gamepad) },
            onDisconnect = { vm.disconnect() },
        )
    }
}

// ============================================================================ touch area

private const val LONG_PRESS_MS = 420L
private const val TAP_MS = 280L

@Composable
private fun Touchpad(vm: DeckrViewModel, modifier: Modifier) {
    val view = LocalView.current
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .clip(shape)
            .background(DeckrColors.Surface)
            .border(1.dp, DeckrColors.Outline, shape)
            .pointerInput(Unit) { touchpadGestures(vm, view) },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Touchpad", color = DeckrColors.Outline, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            listOf(
                "Tap to click",
                "Two fingers to scroll or right-click",
                "Hold, then drag to select",
            ).forEach { hint ->
                Text(
                    hint,
                    color = DeckrColors.Outline,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.touchpadGestures(
    vm: DeckrViewModel,
    view: View,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        val start = first.uptimeMillis
        var maxPointers = 1
        var travel = 0f
        var dragging = false
        var multi = false
        var end = start
        val sens = vm.sensitivity

        while (true) {
            val waitingForHold = !dragging && !multi && travel < slop
            val event = if (waitingForHold) {
                val remaining = LONG_PRESS_MS - (SystemClock.uptimeMillis() - start)
                if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                // Finger held still: start click-and-drag.
                dragging = true
                vm.link.button(Protocol.MOUSE_LEFT, true)
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            maxPointers = max(maxPointers, pressed.size)
            if (pressed.size >= 2) multi = true
            if (pressed.isEmpty()) {
                end = event.changes.maxOf { it.uptimeMillis }
                event.changes.forEach { it.consume() }
                break
            }

            if (pressed.size == 1 && !multi) {
                val c = pressed[0]
                val d = c.position - c.previousPosition
                travel += d.getDistance()
                if (travel >= slop || dragging) {
                    val dp = d / density
                    val gain = sens * (1f + (dp.getDistance() / 6f).coerceAtMost(2f))
                    vm.link.move(dp.x * gain, dp.y * gain)
                }
            } else if (pressed.size >= 2) {
                var sum = Offset.Zero
                pressed.forEach { sum += it.position - it.previousPosition }
                val avg = sum / pressed.size.toFloat()
                travel += avg.getDistance()
                if (travel >= slop) {
                    val dp = avg / density
                    // Natural scrolling: content follows the fingers. 120 units = one wheel notch.
                    vm.link.scroll(vertical = dp.y * 5f, horizontal = -dp.x * 5f)
                }
            }
            event.changes.forEach { it.consume() }
        }

        if (dragging) {
            vm.link.button(Protocol.MOUSE_LEFT, false)
        } else if (travel < slop && end - start < TAP_MS + (maxPointers - 1) * 60) {
            when (maxPointers) {
                1 -> vm.link.click(Protocol.MOUSE_LEFT)
                2 -> vm.link.click(Protocol.MOUSE_RIGHT)
                else -> vm.link.click(Protocol.MOUSE_MIDDLE)
            }
        }
    }
}

// ============================================================================ keyboard bar

/**
 * The field always starts with an invisible sentinel character. When the user presses backspace
 * on an "empty" field the sentinel disappears, which tells us to send Backspace to the PC.
 */
private const val SENTINEL = "​"

@Composable
private fun KeyboardBar(vm: DeckrViewModel, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val ime = LocalSoftwareKeyboardController.current
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    LaunchedEffect(Unit) {
        focus.requestFocus()
        ime?.show()
    }

    fun reset() {
        field = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
    }

    fun onChange(new: TextFieldValue) {
        val oldBody = field.text.removePrefix(SENTINEL)
        val gone = !new.text.startsWith(SENTINEL)
        val newBody = new.text.replace(SENTINEL, "")

        var p = 0
        val limit = minOf(oldBody.length, newBody.length)
        while (p < limit && oldBody[p] == newBody[p]) p++
        if (p > 0 && Character.isHighSurrogate(oldBody[p - 1])) p--

        val removed = oldBody.substring(p)
        val added = newBody.substring(p)
        val backspaces = removed.codePointCount(0, removed.length) + if (gone && removed.isEmpty()) 1 else 0
        repeat(backspaces) { vm.link.key(Protocol.Key.Backspace) }
        if (added.isNotEmpty()) vm.link.text(added)

        val text = SENTINEL + newBody
        field = TextFieldValue(
            text = text,
            selection = TextRange(text.length),
            composition = if (gone) null else new.composition,
        )
    }

    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp)) {
        val pill = RoundedCornerShape(50)
        Row(
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(pill)
                .background(DeckrColors.SurfaceHigh)
                .border(1.dp, DeckrColors.Accent.copy(alpha = 0.6f), pill)
                .padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Keyboard, null, tint = DeckrColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (field.text == SENTINEL) {
                    Text("Type to send to your PC…", color = DeckrColors.TextDim, fontSize = 16.sp)
                }
                BasicTextField(
                    value = field,
                    onValueChange = ::onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = DeckrColors.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(DeckrColors.Accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = KeyboardActions(onSend = {
                        vm.link.key(Protocol.Key.Enter)
                        reset()
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            RoundIcon(Icons.Rounded.Close, "Close keyboard") { ime?.hide(); onClose() }
        }

        // Keys a phone keyboard doesn't have. The edges fade out while there's more to scroll to,
        // so it's clear the row slides.
        val keyScroll = rememberScrollState()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = 40.dp.toPx()
                    if (keyScroll.value > 0) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                            size = Size(fade, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    if (keyScroll.value < keyScroll.maxValue) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                            topLeft = Offset(size.width - fade, 0f),
                            size = Size(fade, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .horizontalScroll(keyScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "Esc" to Protocol.Key.Esc, "Tab" to Protocol.Key.Tab,
                "←" to Protocol.Key.Left, "↑" to Protocol.Key.Up,
                "↓" to Protocol.Key.Down, "→" to Protocol.Key.Right,
                "Del" to Protocol.Key.Delete, "⌫" to Protocol.Key.Backspace, "Enter" to Protocol.Key.Enter,
            ).forEach { (label, key) ->
                KeyChip(label) { vm.link.key(key) }
            }
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DeckrColors.Surface)
            .border(1.dp, DeckrColors.Outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = DeckrColors.Text, fontSize = 14.sp) }
}

// ============================================================================ tool strip

@Composable
private fun ToolStrip(
    keyboardOpen: Boolean,
    onKeyboard: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onGamepad: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(DeckrColors.Surface)
            .border(1.dp, DeckrColors.Outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIcon(
            if (keyboardOpen) Icons.Rounded.KeyboardHide else Icons.Rounded.Keyboard,
            "Keyboard",
            active = keyboardOpen,
            onClick = onKeyboard,
        )
        Spacer(Modifier.width(4.dp))
        TextButtonPill("L", onLeft)
        Spacer(Modifier.width(4.dp))
        TextButtonPill("R", onRight)
        Spacer(Modifier.weight(1f))
        RoundIcon(Icons.Rounded.SportsEsports, "Gamepad mode", onClick = onGamepad)
        RoundIcon(Icons.Rounded.LinkOff, "Disconnect", onClick = onDisconnect)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, description: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (active) DeckrColors.Accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (active) DeckrColors.Accent else DeckrColors.Text)
    }
}

@Composable
private fun TextButtonPill(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 52.dp, height = 36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DeckrColors.SurfaceHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = DeckrColors.Text, fontWeight = FontWeight.SemiBold) }
}
