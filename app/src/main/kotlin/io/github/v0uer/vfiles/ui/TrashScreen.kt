package io.github.v0uer.vfiles.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.v0uer.vfiles.core.OpFailure
import io.github.v0uer.vfiles.core.Trash
import io.github.v0uer.vfiles.core.TrashItem
import io.github.v0uer.vfiles.data.Volume

private data class Trashed(val volume: Volume, val item: TrashItem)

/** Lo borrado en cada volumen: restaurar, borrar definitivo o vaciar todo. */
@Composable
fun TrashScreen(
    trash: Trash,
    volumes: List<Volume>,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    say: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Trashed>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmPurge by remember { mutableStateOf<Trashed?>(null) }
    val closeFocus = remember { FocusRequester() }

    LaunchedEffect(reload, volumes) {
        items = withContext(Dispatchers.IO) {
            volumes.flatMap { v -> trash.list(v.root).map { Trashed(v, it) } }
        }
    }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
    BackHandler(onBack = onClose)

    /** Corre [work] fuera del hilo principal y vuelve a cargar la lista. */
    val act: (suspend () -> Unit) -> Unit = { work ->
        if (!busy) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { work() } }
                result.onFailure { e -> say(Fmt.failure((e as? OpFailure)?.kind ?: OpFailure.Kind.IO)) }
                busy = false; reload++; onChanged()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Tone.Background)) {
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(Tone.Surface).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton(Ico.back, "Cerrar la papelera", onClose, Modifier.focusRequester(closeFocus))
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("Papelera", fontSize = 16.sp)
                Text("Lo borrado se elimina solo a los ${Trash.RETENTION_DAYS} días", fontSize = 11.sp, color = Tone.Muted)
            }
            ChipButton("Vaciar", { confirmEmpty = true }, Modifier.padding(end = 6.dp), enabled = !busy && !items.isNullOrEmpty())
        }
        val list = items
        when {
            list == null -> Unit
            list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("La papelera está vacía", color = Tone.Muted, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.volume.root.toString() + "/" + it.item.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ColorIcon(if (t.item.isDirectory) Ico.folder else Ico.document, 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.item.originalPath.fileName?.toString().orEmpty(),
                                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${t.volume.label} · ${t.item.originalPath.parent ?: ""} · " +
                                    "${Fmt.date(t.item.deletedAtMillis)} · ${Fmt.size(t.item.sizeBytes)}",
                                fontSize = 11.sp, color = Tone.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        ChipButton("Restaurar", { act { trash.restore(t.item, t.volume.root) } }, Modifier.padding(horizontal = 4.dp), enabled = !busy)
                        ChipButton("Borrar", { confirmPurge = t }, enabled = !busy)
                    }
                }
            }
        }
    }

    confirmPurge?.let { t ->
        ConfirmDialog(
            "Borrar para siempre",
            "\"${t.item.originalPath.fileName ?: ""}\" se borrará para siempre. No se puede deshacer.", "Borrar",
            onDismiss = { confirmPurge = null },
            onConfirm = {
                confirmPurge = null
                act { trash.purge(t.item, t.volume.root) }
            },
        )
    }

    if (confirmEmpty) {
        val all = items.orEmpty()
        ConfirmDialog(
            "Vaciar la papelera", "${all.size} elementos se borrarán para siempre. No se puede deshacer.", "Vaciar",
            onDismiss = { confirmEmpty = false },
            onConfirm = {
                confirmEmpty = false
                act { for (t in all) trash.purge(t.item, t.volume.root) }
            },
        )
    }
}
