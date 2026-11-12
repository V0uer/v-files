package local.archivero.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.Path

enum class Action(val label: String) {
    COPY_TO_OTHER("Copiar al otro panel"),
    MOVE_TO_OTHER("Mover al otro panel"),
    COPY("Copiar"),
    CUT("Cortar"),
    PASTE("Pegar"),
    FAVORITE("Agregar a favoritos"),
    RENAME("Renombrar"),
    TRASH("Borrar"),
    DELETE("Borrar definitivo"),
    SHARE("Compartir"),
    OPEN_WITH("Abrir con"),
    PROPERTIES("Propiedades"),
    NEW_FOLDER("Nueva carpeta"),
    SELECT_ALL("Seleccionar todo"),
}

/** Lo que se copió o cortó con un solo panel, en espera de "Pegar". */
data class Clip(val paths: List<Path>, val cut: Boolean)

/** Los dos paneles, cuál está activo y qué acciones tocan según la selección. */
class BrowserState(val left: PaneState, val right: PaneState, dual: Boolean = false) {
    private val _dual = MutableStateFlow(dual)
    val dual: StateFlow<Boolean> = _dual.asStateFlow()

    private val _activeIsLeft = MutableStateFlow(true)
    val activeIsLeft: StateFlow<Boolean> = _activeIsLeft.asStateFlow()

    val clipboard = MutableStateFlow<Clip?>(null)

    val active: PaneState get() = if (_activeIsLeft.value || !_dual.value) left else right
    val other: PaneState get() = if (active === left) right else left

    fun toggleDual() {
        _dual.value = !_dual.value
        if (!_dual.value) _activeIsLeft.value = true
    }

    fun setActive(left: Boolean) {
        if (left || _dual.value) _activeIsLeft.value = left
    }

    fun actionsFor(selectionCount: Int, dual: Boolean): List<Action> {
        if (selectionCount == 0) return listOf(Action.NEW_FOLDER, Action.PASTE, Action.SELECT_ALL)
        return buildList {
            if (dual) { add(Action.COPY_TO_OTHER); add(Action.MOVE_TO_OTHER) }
            add(Action.COPY); add(Action.CUT)
            if (selectionCount == 1) { add(Action.FAVORITE); add(Action.RENAME) }
            add(Action.TRASH)
            add(Action.SHARE)
            if (selectionCount == 1) add(Action.OPEN_WITH)
            add(Action.PROPERTIES)
            add(Action.SELECT_ALL)
            add(Action.DELETE)
        }
    }
}

private val Secondary = setOf(Action.SHARE, Action.OPEN_WITH, Action.PROPERTIES, Action.SELECT_ALL, Action.DELETE)

/** Tira inferior con las acciones principales; "Más" abre el menú con todas. */
@Composable
fun ActionBar(
    actions: List<Action>,
    selectionCount: Int,
    clip: Clip?,
    dual: Boolean,
    onAction: (Action) -> Unit,
    onClear: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(52.dp).background(Tone.Surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton("✕", if (selectionCount > 0) "Quitar selección" else "Vaciar portapapeles", onClear)
        Text(
            when {
                selectionCount == 1 -> "1 seleccionado"
                selectionCount > 1 -> "$selectionCount seleccionados"
                clip != null -> (if (clip.cut) "Para mover: " else "Para copiar: ") + clip.paths.size
                else -> ""
            },
            fontSize = 13.sp, color = Tone.Muted, maxLines = 1, modifier = Modifier.padding(end = 8.dp),
        )
        val primary = actions.filter { a ->
            a !in Secondary && !(dual && (a == Action.COPY || a == Action.CUT)) && !(a == Action.PASTE && clip == null)
        }
        LazyRow(
            Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(primary) { a ->
                val label = when (a) { Action.PASTE -> "Pegar aquí"; Action.FAVORITE -> "★ Favorito"; else -> a.label }
                ChipButton(label, { onAction(a) })
            }
        }
        ChipButton("Más ⋮", onMore, Modifier.padding(start = 6.dp))
    }
}

/** Menú con todas las acciones disponibles; es el que abre el botón Y del control. */
@Composable
fun ActionsMenu(open: Boolean, actions: List<Action>, canPaste: Boolean, onDismiss: () -> Unit, onAction: (Action) -> Unit) {
    DropdownMenu(open, onDismiss) {
        for (a in actions) {
            DropdownMenuItem(
                text = { Text(a.label, color = if (a == Action.DELETE) Tone.Danger else Tone.Text) },
                enabled = a != Action.PASTE || canPaste,
                onClick = { onDismiss(); onAction(a) },
            )
        }
    }
}

/** Abrir y compartir con otras apps, prestando el archivo mediante FileProvider. */
object Opener {
    fun mime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "apk") return "application/vnd.android.package-archive"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun uri(context: Context, path: Path): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".files", path.toFile())

    /** false si ninguna app lo abre. Con [chooser] siempre pregunta con cuál. */
    fun open(context: Context, path: Path, chooser: Boolean): Boolean {
        val view = try {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri(context, path), mime(path.fileName.toString()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: IllegalArgumentException) { return false }
        return try {
            if (chooser) {
                if (view.resolveActivity(context.packageManager) == null) return false
                context.startActivity(Intent.createChooser(view, "Abrir con"))
            } else context.startActivity(view)
            true
        } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }
    }

    /** Comparte los archivos de [paths] (las carpetas se saltan); false si no quedó nada que compartir. */
    fun share(context: Context, paths: List<Path>): Boolean {
        val files = paths.filter { Files.isRegularFile(it) }
        if (files.isEmpty()) return false
        val uris = try { files.map { uri(context, it) } } catch (e: IllegalArgumentException) { return false }
        val types = files.map { mime(it.fileName.toString()) }.distinct()
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = types.singleOrNull() ?: "*/*"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try { context.startActivity(Intent.createChooser(send, "Compartir")); true }
        catch (e: ActivityNotFoundException) { false }
    }
}
