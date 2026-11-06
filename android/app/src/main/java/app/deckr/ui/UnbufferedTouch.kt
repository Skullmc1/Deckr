package app.deckr.ui

import android.os.Build
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Asks Android to deliver touch events as soon as the screen reports them instead of batching
 * them once per frame. On a 240 Hz touchscreen that is up to four times as many movement updates.
 */
@Composable
fun UnbufferedTouch() {
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.requestUnbufferedDispatch(InputDevice.SOURCE_TOUCHSCREEN)
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.requestUnbufferedDispatch(0)
        }
    }
}
