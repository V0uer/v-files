package local.archivero.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import local.archivero.core.EntryKind
import local.archivero.core.FileEntry
import local.archivero.core.ListError
import local.archivero.core.OpFailure
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")
    private val day = DateTimeFormatter.ofPattern("dd/MM/yy HH:mm")

    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var v = bytes.toDouble(); var u = 0
        while (v >= 1024 && u < units.lastIndex) { v /= 1024; u++ }
        return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[u])
    }

    fun date(millis: Long): String =
        if (millis <= 0) "" else day.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

    fun glyph(kind: EntryKind): String = when (kind) {
        EntryKind.FOLDER -> "📁"
        EntryKind.IMAGE -> "🖼"
        EntryKind.VIDEO -> "🎬"
        EntryKind.AUDIO -> "🎵"
        EntryKind.ARCHIVE -> "🗜"
        EntryKind.TEXT -> "📄"
        EntryKind.APK -> "📦"
        EntryKind.OTHER -> "▫"
    }

    fun failure(kind: OpFailure.Kind): String = when (kind) {
        OpFailure.Kind.NO_SPACE -> "No hay espacio suficiente"
        OpFailure.Kind.FILE_TOO_LARGE -> "El archivo es demasiado grande para este almacenamiento"
        OpFailure.Kind.NO_ACCESS -> "Sin permiso para escribir aquí"
        OpFailure.Kind.SOURCE_GONE -> "El archivo de origen ya no está"
        OpFailure.Kind.INTO_ITSELF -> "No se puede copiar una carpeta dentro de sí misma"
        OpFailure.Kind.ALREADY_EXISTS -> "Ya existe algo con ese nombre"
        OpFailure.Kind.IO -> "Falló la lectura o la escritura"
    }

    fun listError(e: ListError): String = when (e) {
        ListError.NOT_FOUND -> "Esta carpeta ya no existe"
        ListError.NO_ACCESS -> "Android no deja entrar a esta carpeta"
        ListError.NOT_A_DIRECTORY -> "Esto no es una carpeta"
        ListError.IO -> "No se pudo leer la carpeta"
    }
}

/**
 * La lista de un panel. Tocar una carpeta navega, tocar un archivo llama [onOpenFile];
 * pulsación larga selecciona, y con selección activa un toque la cambia.
 */
@Composable
fun PaneView(
    state: PaneState,
    active: Boolean,
    grid: Boolean,
    onOpenFile: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit = {},
) {
    val content by state.content.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        when (val c = content) {
            PaneContent.Loading -> Unit
            is PaneContent.Failed -> Notice(Fmt.listError(c.reason), "Reintentar") { onActivate(); state.refresh() }
            is PaneContent.Ready ->
                if (c.entries.isEmpty()) Notice("Carpeta vacía", null) {}
                else Entries(state, c.entries, active, grid, onOpenFile, onActivate)
        }
    }
}

@Composable
private fun Notice(text: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Tone.Muted, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction, modifier = Modifier.focusRing(RoundedCornerShape(50))) { Text(action) }
        }
    }
}

@Composable
private fun Entries(
    state: PaneState,
    entries: List<FileEntry>,
    active: Boolean,
    grid: Boolean,
    onOpenFile: (FileEntry) -> Unit,
    onActivate: () -> Unit,
) {
    val path by state.path.collectAsStateWithLifecycle()
    val selection by state.selection.collectAsStateWithLifecycle()
    val tick by state.focusTick.collectAsStateWithLifecycle()
    val listState = remember(path) { LazyListState() }
    val gridState = remember(path) { LazyGridState() }
    val keyboard = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    var pendingFocus by remember { mutableStateOf<Path?>(null) }

    // Al cargar: deja a la vista el elemento del que se vino; con control físico, además le da el foco.
    LaunchedEffect(entries, tick, active, keyboard, grid) {
        val highlight = state.highlight.value
        val wantFocus = keyboard && active
        if (highlight == null && !wantFocus) return@LaunchedEffect
        val target = highlight ?: state.focused
        val index = entries.indexOfFirst { it.path == target }.coerceAtLeast(0)
        val visible = if (grid) gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
        else listState.layoutInfo.visibleItemsInfo.any { it.index == index }
        if (!visible) { if (grid) gridState.scrollToItem(index) else listState.scrollToItem(index) }
        state.consumeHighlight()
        if (wantFocus) pendingFocus = entries[index].path
    }

    val onClick: (FileEntry) -> Unit = { e ->
        onActivate()
        when {
            state.selection.value.isNotEmpty() -> state.toggleSelect(e.path)
            e.isDirectory -> state.navigate(e.path)
            else -> onOpenFile(e)
        }
    }
    val onLong: (FileEntry) -> Unit = { e -> onActivate(); state.toggleSelect(e.path) }
    val onFocused: (FileEntry) -> Unit = { e -> state.focused = e.path; onActivate() }

    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp), state = gridState,
            contentPadding = PaddingValues(6.dp), modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.path.toString() }) { e ->
                EntryCell(e, e.path in selection, pendingFocus == e.path, { pendingFocus = null },
                    { onFocused(e) }, { onClick(e) }, { onLong(e) })
            }
        }
    } else {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(entries, key = { it.path.toString() }) { e ->
                EntryRow(e, e.path in selection, pendingFocus == e.path, { pendingFocus = null },
                    { onFocused(e) }, { onClick(e) }, { onLong(e) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    e: FileEntry, selected: Boolean, wantsFocus: Boolean, onFocusTaken: () -> Unit,
    onFocused: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    if (wantsFocus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() }; onFocusTaken() }
    Row(
        Modifier.fillMaxWidth().height(52.dp)
            .background(if (selected) Tone.Selected else Color.Transparent)
            .focusRequester(requester)
            .focusRing(onFocus = onFocused)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(e, 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(e.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
        Text(
            if (e.isDirectory) "" else Fmt.size(e.size),
            Modifier.width(76.dp), color = Tone.Muted, fontSize = 12.sp, textAlign = TextAlign.End, maxLines = 1,
        )
        Text(
            Fmt.date(e.modifiedMillis),
            Modifier.width(112.dp), color = Tone.Muted, fontSize = 12.sp, textAlign = TextAlign.End, maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryCell(
    e: FileEntry, selected: Boolean, wantsFocus: Boolean, onFocusTaken: () -> Unit,
    onFocused: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    val shape = RoundedCornerShape(8.dp)
    if (wantsFocus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() }; onFocusTaken() }
    Column(
        Modifier.padding(3.dp).fillMaxWidth().clip(shape)
            .background(if (selected) Tone.Selected else Color.Transparent)
            .focusRequester(requester)
            .focusRing(shape, onFocus = onFocused)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EntryIcon(e, 56.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            e.name, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center,
        )
    }
}

/** Icono por tipo; las imágenes llevan miniatura, decodificada a tamaño fijo. */
@Composable
fun EntryIcon(e: FileEntry, size: Dp) {
    if (e.kind == EntryKind.IMAGE) {
        val context = LocalContext.current
        val request = remember(e.path, e.modifiedMillis) {
            ImageRequest.Builder(context).data(e.path.toFile()).size(160).build()
        }
        AsyncImage(
            model = request, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(4.dp)).background(Tone.SurfaceHigh),
        )
    } else {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Text(Fmt.glyph(e.kind), fontSize = (size.value * 0.6f).sp)
        }
    }
}
