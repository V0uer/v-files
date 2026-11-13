package local.archivero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.archivero.core.ConflictChoice
import local.archivero.core.OpFailure
import local.archivero.core.OpState
import local.archivero.core.OpStatus
import local.archivero.ops.OpText

/**
 * Tira inferior con las operaciones en curso y las que fallaron. Plegada muestra una;
 * tocarla la despliega. Si alguna espera por un conflicto de nombre, abre el diálogo.
 */
@Composable
fun OpsPanel(
    states: List<OpState>,
    onCancel: (Long) -> Unit,
    onResolve: (Long, ConflictChoice, Boolean) -> Unit,
    onDismissFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    states.firstOrNull { it.status == OpStatus.WAITING_CONFLICT && it.conflict != null }?.let { waiting ->
        ConflictDialog(
            existing = waiting.conflict!!,
            onResolve = { choice, all -> onResolve(waiting.id, choice, all) },
            onCancelOperation = { onCancel(waiting.id) },
        )
    }
    val shown = states.filter { !it.isFinished } + states.filter { it.status == OpStatus.FAILED }
    if (shown.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(Tone.SurfaceHigh)) {
        val rows = if (expanded) shown else shown.take(1)
        for (state in rows) {
            if (state.status == OpStatus.FAILED) FailedRow(state, onDismissFailed) else RunningRow(state, onCancel)
        }
        if (shown.size > 1) {
            Text(
                if (expanded) "Ver menos" else "y ${shown.size - 1} más…",
                color = Tone.Muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().focusRing().clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun RunningRow(state: OpState, onCancel: (Long) -> Unit) {
    val p = state.progress
    val bytes by rememberUpdatedState(p?.bytesDone ?: 0L)
    var speed by remember(state.id) { mutableLongStateOf(0L) }
    LaunchedEffect(state.id) {
        var last = bytes
        while (true) { delay(1000); val now = bytes; speed = (now - last).coerceAtLeast(0); last = now }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${OpText.verb(state.request)} ${OpText.subject(state.request)}",
                    fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        state.status == OpStatus.QUEUED -> "En cola"
                        state.status == OpStatus.WAITING_CONFLICT -> "Esperando tu respuesta"
                        p == null -> "Preparando…"
                        else -> "${p.filesDone} de ${p.filesTotal}" +
                            (if (p.bytesTotal > 0) " · ${Fmt.size(p.bytesDone)} de ${Fmt.size(p.bytesTotal)}" else "") +
                            (if (speed > 0) " · ${Fmt.size(speed)}/s" else "")
                    },
                    fontSize = 11.sp, color = Tone.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            GlyphButton(Ico.close, "Cancelar", { onCancel(state.id) })
        }
        if (p != null && state.status != OpStatus.QUEUED) {
            LinearProgressIndicator(
                progress = { OpText.fraction(p) }, modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Tone.Accent, trackColor = Tone.Line,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Tone.Accent, trackColor = Tone.Line)
        }
    }
}

@Composable
private fun FailedRow(state: OpState, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "No se pudo ${OpText.infinitive(state.request)} ${OpText.subject(state.request)}",
                color = Tone.Danger, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                Fmt.failure(state.failure ?: OpFailure.Kind.IO) +
                    (state.failurePath?.fileName?.let { " · $it" } ?: ""),
                fontSize = 11.sp, color = Tone.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        ChipButton("Cerrar", onDismiss)
    }
}
