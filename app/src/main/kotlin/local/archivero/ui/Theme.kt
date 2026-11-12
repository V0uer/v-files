package local.archivero.ui

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object Tone {
    val Background = Color(0xFF14171C)
    val Surface = Color(0xFF1B1F26)
    val SurfaceHigh = Color(0xFF232830)
    val Sidebar = Color(0xFF181C22)
    val Line = Color(0xFF2A2F37)
    val Accent = Color(0xFF3B82F6)
    val Selected = Color(0xFF263247)
    val Text = Color(0xFFE6E8EB)
    val Muted = Color(0xFF7D8590)
    val Danger = Color(0xFFEF4444)
}

private val Scheme = darkColorScheme(
    primary = Tone.Accent,
    onPrimary = Color.White,
    background = Tone.Background,
    onBackground = Tone.Text,
    surface = Tone.Surface,
    onSurface = Tone.Text,
    surfaceVariant = Tone.SurfaceHigh,
    onSurfaceVariant = Tone.Muted,
    surfaceContainer = Tone.Surface,
    surfaceContainerHigh = Tone.SurfaceHigh,
    surfaceContainerHighest = Tone.SurfaceHigh,
    outline = Tone.Line,
    error = Tone.Danger,
)

@Composable
fun ArchiveroTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)

/** Borde de acento de 2 dp mientras el elemento tiene el foco (control físico o teclado). */
fun Modifier.focusRing(shape: Shape = RectangleShape, onFocus: (() -> Unit)? = null): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus?.invoke() }
        .then(if (focused) Modifier.border(2.dp, Tone.Accent, shape) else Modifier)
}
