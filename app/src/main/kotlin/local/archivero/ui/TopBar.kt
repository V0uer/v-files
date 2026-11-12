package local.archivero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.archivero.core.SortKey
import local.archivero.core.SortSpec
import local.archivero.data.Settings
import java.nio.file.Path

private val Round = RoundedCornerShape(8.dp)

/** Botón cuadrado con un glifo; [label] es lo que lee la accesibilidad. */
@Composable
fun GlyphButton(
    glyph: String, label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Tone.Text,
) {
    Box(
        modifier.size(44.dp).clip(Round).focusRing(Round)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, fontSize = 18.sp, color = if (enabled) tint else Tone.Muted.copy(alpha = 0.4f)) }
}

/** Botón con texto corto, del alto de la barra. */
@Composable
fun ChipButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, enabled: Boolean = true) {
    Box(
        modifier.height(36.dp).clip(Round).background(if (on) Tone.Accent else Tone.SurfaceHigh).focusRing(Round)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 13.sp, maxLines = 1, color = if (enabled) Tone.Text else Tone.Muted.copy(alpha = 0.5f)) }
}

/**
 * Barra superior: atrás, subir, migas tocables, buscar, "2 paneles" y menú.
 * Si [searchField] no es null ocupa el lugar de las migas. Con [narrow] (celular en
 * vertical) va en dos renglones: botones arriba y la ruta completa abajo; [onMenu]
 * agrega el botón ☰ que abre favoritos y almacenamiento.
 */
@Composable
fun TopBar(
    crumbs: List<Pair<String, Path>>,
    canBack: Boolean,
    canUp: Boolean,
    settings: Settings,
    dual: Boolean,
    onBack: () -> Unit,
    onUp: () -> Unit,
    onCrumb: (Path) -> Unit,
    onSort: (SortSpec) -> Unit,
    onToggleGrid: () -> Unit,
    onToggleHidden: () -> Unit,
    onSetStart: () -> Unit,
    onSearch: (() -> Unit)? = null,
    onToggleDual: (() -> Unit)? = null,
    onTrash: (() -> Unit)? = null,
    onNewFolder: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    searchField: (@Composable () -> Unit)? = null,
    narrow: Boolean = false,
    onMenu: (() -> Unit)? = null,
) {
    val menu: @Composable () -> Unit = {
        OverflowMenu(settings, onSort, onToggleGrid, onToggleHidden, onSetStart, onTrash, onNewFolder, onSelectAll)
    }
    if (narrow) {
        Column(Modifier.fillMaxWidth().background(Tone.Surface)) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onMenu != null) GlyphButton("☰", "Favoritos y almacenamiento", onMenu)
                GlyphButton("←", "Atrás", onBack, enabled = canBack || searchField != null)
                if (searchField == null) GlyphButton("↑", "Subir una carpeta", onUp, enabled = canUp)
                Spacer(Modifier.weight(1f))
                if (searchField == null && onSearch != null) GlyphButton("🔍", "Buscar", onSearch)
                if (onToggleDual != null) {
                    GlyphButton("◫", if (dual) "Un panel" else "2 paneles", onToggleDual, tint = if (dual) Tone.Accent else Tone.Text)
                }
                menu()
            }
            Box(
                Modifier.fillMaxWidth().height(44.dp).padding(start = 4.dp, end = 8.dp, bottom = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (searchField != null) searchField() else Crumbs(crumbs, onCrumb, Modifier.fillMaxWidth())
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(Tone.Surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMenu != null) GlyphButton("☰", "Favoritos y almacenamiento", onMenu)
        GlyphButton("←", "Atrás", onBack, enabled = canBack || searchField != null)
        if (searchField != null) {
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) { searchField() }
        } else {
            GlyphButton("↑", "Subir una carpeta", onUp, enabled = canUp)
            Crumbs(crumbs, onCrumb, Modifier.weight(1f))
            if (onSearch != null) GlyphButton("🔍", "Buscar", onSearch)
        }
        if (onToggleDual != null) ChipButton("2 paneles", onToggleDual, Modifier.padding(horizontal = 4.dp), on = dual)
        menu()
    }
}

@Composable
private fun OverflowMenu(
    settings: Settings,
    onSort: (SortSpec) -> Unit,
    onToggleGrid: () -> Unit,
    onToggleHidden: () -> Unit,
    onSetStart: () -> Unit,
    onTrash: (() -> Unit)?,
    onNewFolder: (() -> Unit)?,
    onSelectAll: (() -> Unit)?,
) {
    Box {
        var open by remember { mutableStateOf(false) }
        GlyphButton("⋮", "Menú", { open = true })
        DropdownMenu(open, { open = false }) {
            val item: @Composable (String, Boolean, () -> Unit) -> Unit = { text, checked, action ->
                DropdownMenuItem(
                    text = { Text((if (checked) "✓  " else "     ") + text) },
                    onClick = { open = false; action() },
                )
            }
            if (onNewFolder != null) item("Nueva carpeta", false, onNewFolder)
            if (onSelectAll != null) item("Seleccionar todo", false, onSelectAll)
            if (onNewFolder != null || onSelectAll != null) HorizontalDivider(color = Tone.Line)
            val sort = settings.sort
            item("Por nombre", sort.key == SortKey.NAME) { onSort(sort.copy(key = SortKey.NAME)) }
            item("Por fecha", sort.key == SortKey.DATE) { onSort(sort.copy(key = SortKey.DATE)) }
            item("Por tamaño", sort.key == SortKey.SIZE) { onSort(sort.copy(key = SortKey.SIZE)) }
            item("Por tipo", sort.key == SortKey.TYPE) { onSort(sort.copy(key = SortKey.TYPE)) }
            item("Orden inverso", !sort.ascending) { onSort(sort.copy(ascending = !sort.ascending)) }
            HorizontalDivider(color = Tone.Line)
            item("Cuadrícula", settings.gridView, onToggleGrid)
            item("Mostrar ocultos", settings.showHidden, onToggleHidden)
            HorizontalDivider(color = Tone.Line)
            item("Abrir aquí al iniciar", false, onSetStart)
            if (onTrash != null) item("Papelera", false, onTrash)
        }
    }
}

@Composable
private fun Crumbs(crumbs: List<Pair<String, Path>>, onCrumb: (Path) -> Unit, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(crumbs) { if (crumbs.isNotEmpty()) state.scrollToItem(crumbs.lastIndex) }
    LazyRow(modifier.padding(horizontal = 4.dp), state = state, verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(crumbs) { i, (label, target) ->
            val last = i == crumbs.lastIndex
            if (i > 0) Text("›", color = Tone.Muted, modifier = Modifier.padding(horizontal = 2.dp))
            Text(
                label, maxLines = 1, fontSize = 14.sp,
                color = if (last) Tone.Text else Tone.Muted,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(Round).focusRing(Round).clickable { onCrumb(target) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
    }
}
