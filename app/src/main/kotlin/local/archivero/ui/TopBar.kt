package local.archivero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.archivero.core.SortKey
import local.archivero.core.SortSpec
import local.archivero.data.Settings
import java.nio.file.Path

private val Round = RoundedCornerShape(8.dp)

/** Botón cuadrado con un icono monocromo; [label] es lo que lee la accesibilidad. */
@Composable
fun GlyphButton(
    icon: Int, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Tone.Text, size: Dp = 44.dp,
) {
    Box(
        modifier.size(size).clip(Round).focusRing(Round)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { MonoIcon(icon, if (enabled) tint else Tone.Muted.copy(alpha = 0.4f), size = 22.dp) }
}

/** Botón con texto corto, del alto de la barra. */
@Composable
fun ChipButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.height(36.dp).clip(Round).background(if (on) Tone.Accent else Tone.SurfaceHigh).focusRing(Round)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 13.sp, maxLines = 1, color = if (enabled) Tone.Text else Tone.Muted.copy(alpha = 0.5f))
    }
}

/**
 * Cabecera de un panel en dos renglones: arriba los botones (☰ favoritos sobre este panel,
 * atrás, subir, buscar, abrir/cerrar el segundo panel y menú) y abajo la ruta, para que se
 * lea completa aunque el panel sea angosto. Si [searchField] no es null ocupa el lugar de
 * la ruta. Con [showActive] (dos paneles) una raya arriba marca el activo.
 */
@Composable
fun PaneHeader(
    crumbs: List<Pair<String, Path>>,
    canBack: Boolean,
    canUp: Boolean,
    active: Boolean,
    showActive: Boolean,
    settings: Settings,
    onMenu: () -> Unit,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onCrumb: (Path) -> Unit,
    onSearch: () -> Unit,
    onSort: (SortSpec) -> Unit,
    onToggleGrid: () -> Unit,
    onToggleHidden: () -> Unit,
    onSetStart: () -> Unit,
    onTrash: () -> Unit,
    onNewFolder: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleSplit: () -> Unit,
    paneToggle: PaneToggle?,
    searchField: (@Composable () -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().background(Tone.Surface)) {
        if (showActive) Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) Tone.Accent else Tone.Line))
        val dim = if (showActive && !active) Tone.Muted else Tone.Text
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(Ico.menu, "Favoritos y almacenamiento", onMenu, size = HeaderButton, tint = dim)
            GlyphButton(Ico.back, "Atrás", onBack, enabled = canBack || searchField != null, size = HeaderButton, tint = dim)
            GlyphButton(Ico.up, "Subir una carpeta", onUp, enabled = canUp && searchField == null, size = HeaderButton, tint = dim)
            Spacer(Modifier.weight(1f))
            if (searchField == null) GlyphButton(Ico.search, "Buscar", onSearch, size = HeaderButton, tint = dim)
            if (paneToggle != null) GlyphButton(paneToggle.icon, paneToggle.label, paneToggle.onClick, size = HeaderButton, tint = dim)
            OverflowMenu(settings, onSort, onToggleGrid, onToggleHidden, onSetStart, onTrash, onNewFolder, onSelectAll, onToggleSplit)
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = 6.dp, end = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (searchField != null) searchField()
            else Crumbs(crumbs, onCrumb, Modifier.fillMaxWidth(), dimmed = showActive && !active)
        }
    }
}

/** Botón de la cabecera para abrir el segundo panel (◫) o cerrar este (✕). */
class PaneToggle(val icon: Int, val label: String, val onClick: () -> Unit)

private val HeaderButton = 40.dp

@Composable
private fun OverflowMenu(
    settings: Settings,
    onSort: (SortSpec) -> Unit,
    onToggleGrid: () -> Unit,
    onToggleHidden: () -> Unit,
    onSetStart: () -> Unit,
    onTrash: () -> Unit,
    onNewFolder: () -> Unit,
    onSelectAll: () -> Unit,
    onToggleSplit: () -> Unit,
) {
    Box {
        var open by remember { mutableStateOf(false) }
        GlyphButton(Ico.more, "Menú", { open = true }, size = HeaderButton)
        DropdownMenu(open, { open = false }) {
            val item: @Composable (String, Boolean, () -> Unit) -> Unit = { text, checked, action ->
                DropdownMenuItem(
                    text = { Text(text) },
                    leadingIcon = { if (checked) MonoIcon(Ico.check, Tone.Accent, 20.dp) else Spacer(Modifier.size(20.dp)) },
                    onClick = { open = false; action() },
                )
            }
            item("Nueva carpeta", false, onNewFolder)
            item("Seleccionar todo", false, onSelectAll)
            HorizontalDivider(color = Tone.Line)
            val sort = settings.sort
            item("Por nombre", sort.key == SortKey.NAME) { onSort(sort.copy(key = SortKey.NAME)) }
            item("Por fecha", sort.key == SortKey.DATE) { onSort(sort.copy(key = SortKey.DATE)) }
            item("Por tamaño", sort.key == SortKey.SIZE) { onSort(sort.copy(key = SortKey.SIZE)) }
            item("Por tipo", sort.key == SortKey.TYPE) { onSort(sort.copy(key = SortKey.TYPE)) }
            item("Orden inverso", !sort.ascending) { onSort(sort.copy(ascending = !sort.ascending)) }
            HorizontalDivider(color = Tone.Line)
            item("Cuadrícula", settings.gridView, onToggleGrid)
            item("Mostrar ocultos", settings.showHidden, onToggleHidden)
            item("Ajustar tamaño de paneles", settings.splitAdjustable, onToggleSplit)
            HorizontalDivider(color = Tone.Line)
            item("Abrir aquí al iniciar", false, onSetStart)
            item("Papelera", false, onTrash)
        }
    }
}

@Composable
private fun Crumbs(crumbs: List<Pair<String, Path>>, onCrumb: (Path) -> Unit, modifier: Modifier, dimmed: Boolean = false) {
    val state = rememberLazyListState()
    LaunchedEffect(crumbs) { if (crumbs.isNotEmpty()) state.scrollToItem(crumbs.lastIndex) }
    LazyRow(modifier.padding(horizontal = 4.dp), state = state, verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(crumbs) { i, (label, target) ->
            val last = i == crumbs.lastIndex
            if (i > 0) Text("›", color = Tone.Muted, modifier = Modifier.padding(horizontal = 2.dp))
            Text(
                label, maxLines = 1, fontSize = 14.sp,
                color = if (last && !dimmed) Tone.Text else Tone.Muted,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(Round).focusRing(Round).clickable { onCrumb(target) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}
