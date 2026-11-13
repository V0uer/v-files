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
import androidx.compose.ui.unit.Dp
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
 * Favoritos y almacenamiento; sale encima de un panel con ☰ y el botón de arriba
 * ([onClose]) lo cierra. Los favoritos se agregan desde la lista (pulsación larga en
 * una carpeta), no desde aquí.
 * Pulsación larga (o X / Y en el control) sobre un favorito: quitar, subir, bajar.
 */
@Composable
fun Sidebar(
    favorites: List<Favorite>,
    volumes: List<Volume>,
    currentPath: Path,
    onNavigate: (Path) -> Unit,
    onClose: () -> Unit,
    onRemove: (Favorite) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocus: FocusRequester? = null,
    width: Dp = 280.dp,
) {
    val here = currentPath.toString()
    val compact = false
    Column(
        modifier.width(width).fillMaxHeight().background(Tone.Sidebar)
            .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        Item(
            icon = { MonoIcon(Ico.menu, Tone.Muted, 20.dp) }, title = "Cerrar panel", subtitle = null, compact = compact,
            current = false, dimmed = false, onClick = onClose, onLongClick = null, muted = true,
        )
        Header("Favoritos")
        if (favorites.isEmpty()) Text(
            "Deja presionada una carpeta y elige Favorito", color = Tone.Muted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        favorites.forEachIndexed { i, fav ->
            val exists by produceState(true, fav.path, volumes) {
                value = withContext(Dispatchers.IO) { File(fav.path).isDirectory }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                Item(
                    icon = { ColorIcon(Ico.star, 20.dp) },
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
        Spacer(Modifier.height(10.dp))
        Header("Almacenamiento")
        volumes.forEachIndexed { i, v ->
            Item(
                modifier = if (i == 0 && favorites.isEmpty() && firstItemFocus != null) Modifier.focusRequester(firstItemFocus) else Modifier,
                icon = { if (v.isRemovable) MonoIcon(Ico.drive, size = 22.dp) else ColorIcon(Ico.phone, 22.dp) }, title = v.label,
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
    icon: @Composable () -> Unit, title: String, subtitle: String?, compact: Boolean, current: Boolean, dimmed: Boolean,
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
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { icon() }
        if (!compact) Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (muted) Tone.Muted else Tone.Text)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = Tone.Muted, maxLines = 1)
        }
    }
}
