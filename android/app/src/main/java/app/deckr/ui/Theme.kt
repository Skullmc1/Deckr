package app.deckr.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object DeckrColors {
    val Bg = Color(0xFF121010)
    val Surface = Color(0xFF1C1917)
    val SurfaceHigh = Color(0xFF26221F)
    val Outline = Color(0xFF3A332D)
    val Accent = Color(0xFFFF8A2B)
    val Text = Color(0xFFF2EEEA)
    val TextDim = Color(0xFF9E958D)
    val Danger = Color(0xFFFF5C6C)

    // Face button symbol colours
    val Triangle = Color(0xFF3DDCB0)
    val Circle = Color(0xFFFF5C6C)
    val Cross = Color(0xFF6CA8FF)
    val Square = Color(0xFFF58BD8)
}

@Composable
fun DeckrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DeckrColors.Accent,
            onPrimary = Color.White,
            background = DeckrColors.Bg,
            onBackground = DeckrColors.Text,
            surface = DeckrColors.Surface,
            onSurface = DeckrColors.Text,
            surfaceVariant = DeckrColors.SurfaceHigh,
            onSurfaceVariant = DeckrColors.TextDim,
            outline = DeckrColors.Outline,
            error = DeckrColors.Danger,
        ),
        content = content,
    )
}
