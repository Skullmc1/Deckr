package app.deckr

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.deckr.ui.DeckrTheme
import app.deckr.ui.GamepadScreen
import app.deckr.ui.TouchpadScreen

/**
 * Debug-only screen for screenshots: opens the touchpad or gamepad screen without a PC connected.
 * Input is dropped because nothing is connected.
 *
 *   adb shell am start -n app.deckr/.DemoActivity --es screen gamepad
 */
class DemoActivity : ComponentActivity() {
    private val vm: DeckrViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deckr is always dark, so keep the status and navigation bar icons light even when the
        // phone itself is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val gamepad = intent.getStringExtra("screen") == "gamepad"
        if (gamepad) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        setContent {
            DeckrTheme {
                if (gamepad) GamepadScreen(vm) else TouchpadScreen(vm)
            }
        }
    }
}
