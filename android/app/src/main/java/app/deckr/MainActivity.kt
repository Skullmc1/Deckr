package app.deckr

import android.Manifest
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.deckr.ble.LinkState
import app.deckr.ui.ConnectScreen
import app.deckr.ui.DeckrTheme
import app.deckr.ui.GamepadScreen
import app.deckr.ui.TouchpadScreen

class MainActivity : ComponentActivity() {
    private val vm: DeckrViewModel by viewModels()

    val bluetoothPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DeckrTheme {
                val state by vm.link.state.collectAsStateWithLifecycle()
                val mode by vm.mode.collectAsStateWithLifecycle()
                val inGamepad = state == LinkState.Connected && mode == Mode.Gamepad

                LaunchedEffect(inGamepad) { setGamepadWindow(inGamepad) }
                LaunchedEffect(state) {
                    // Don't let the phone sleep while it's being used as a controller.
                    if (state == LinkState.Connected) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }

                when {
                    state != LinkState.Connected -> ConnectScreen(vm, bluetoothPermissions)
                    mode == Mode.Touchpad -> TouchpadScreen(vm)
                    else -> GamepadScreen(vm)
                }
            }
        }
    }

    /** Gamepad mode is landscape and full screen; touchpad mode follows the phone. */
    private fun setGamepadWindow(gamepad: Boolean) {
        requestedOrientation = if (gamepad) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (gamepad) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
