package local.archivero.ui

import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.archivero.data.Favorite
import local.archivero.data.Volume
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Panel izquierdo: favoritos y almacenamiento. Con [compact] muestra solo iconos.
 * Pulsación larga (o X / Y en el control) sobre un favorito: quitar, subir, bajar.
 */
@Composable
fun Sidebar(
    compact: Boolean,
    favorites: List<Favorite>,
    volumes: List<Volume>,
    currentPath: Path,
    onNavigate: (Path) -> Unit,
    onAddCurrent: () -> Unit,
    onRemove: (Favorite) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocus: FocusRequester? = null,
) {
    val here = currentPath.toString()
    Column(
        modifier.width(if (compact) 48.dp else 180.dp).fillMaxHeight().background(Tone.Sidebar)
            .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        if (!compact) Header("Favoritos")
        favorites.forEachIndexed { i, fav ->
            val exists by produceState(true, fav.path, volumes) {
                value = withContext(Dispatchers.IO) { File(fav.path).isDirectory }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                Item(
                    glyph = if (compact) fav.label.take(1).uppercase().ifEmpty { "★" } else "★",
                    title = fav.label, subtitle = null, compact = compact,
                    current = fav.path == here, dimmed = !exists,
                    onClick = { if (exists) onNavigate(Paths.get(fav.path)) else menu = true },
                    onLongClick = { menu = true },
                    modifier = if (i == 0 && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier,
                )
                DropdownMenu(menu, { menu = false }) {
                    if (!exists) Text(
                        "Esta carpeta ya no existe", color = Tone.Muted, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    DropdownMenuItem({ Text("Quitar de favoritos") }, { menu = false; onRemove(fav) })
                    if (i > 0) DropdownMenuItem({ Text("Subir") }, { menu = false; onMove(i, i - 1) })
                    if (i < favorites.lastIndex) DropdownMenuItem({ Text("Bajar") }, { menu = false; onMove(i, i + 1) })
                }
            }
        }
        if (favorites.none { it.path == here }) {
            Item(
                glyph = "＋", title = "Agregar", subtitle = null, compact = compact, current = false, dimmed = false,
                onClick = onAddCurrent, onLongClick = null, muted = true,
                modifier = if (favorites.isEmpty() && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier,
            )
        }
        Spacer(Modifier.height(10.dp))
        if (!compact) Header("Almacenamiento")
        for (v in volumes) {
            Item(
                glyph = if (v.isRemovable) "💾" else "📱", title = v.label,
                subtitle = "${Fmt.size(v.freeBytes)} libres", compact = compact,
                current = v.root == currentPath, dimmed = false,
                onClick = { onNavigate(v.root) }, onLongClick = null,
            )
        }
    }
}

@Composable
private fun Header(text: String) = Text(
    text.uppercase(), color = Tone.Muted, fontSize = 11.sp, letterSpacing = 1.sp,
    modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 4.dp),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Item(
    glyph: String, title: String, subtitle: String?, compact: Boolean, current: Boolean, dimmed: Boolean,
    onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier, muted: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().height(if (subtitle != null && !compact) 50.dp else 44.dp)
            .background(if (current) Tone.Selected else Color.Transparent)
            .focusRing()
            .onKeyEvent { e ->
                val key = e.nativeKeyEvent
                val wanted = key.keyCode == KeyEvent.KEYCODE_BUTTON_X || key.keyCode == KeyEvent.KEYCODE_BUTTON_Y
                if (wanted && onLongClick != null) { if (key.action == KeyEvent.ACTION_UP) onLongClick(); true } else false
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (dimmed) 0.4f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
            Text(glyph, fontSize = 16.sp, color = if (muted) Tone.Muted else Tone.Text)
        }
        if (!compact) Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (muted) Tone.Muted else Tone.Text)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = Tone.Muted, maxLines = 1)
        }
    }
}
