package local.archivero.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import local.archivero.core.ConflictChoice
import local.archivero.core.FileOps
import local.archivero.core.Names
import java.nio.file.Path

private val Pill = RoundedCornerShape(50)

/** Pone el foco en el elemento que lo lleve, al abrirse el diálogo. */
@Composable
private fun rememberInitialFocus(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return requester
}

/**
 * Pide un nombre (renombrar o carpeta nueva). Con [selectStem] el nombre llega
 * preseleccionado sin la extensión. Aceptar se apaga si el nombre no es válido, y
 * también si quedó igual salvo con [acceptInitial] (el nombre llega como sugerencia).
 */
@Composable
fun NameDialog(
    title: String, initial: String, confirm: String, selectStem: Boolean,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit, acceptInitial: Boolean = false,
) {
    var value by remember {
        val dot = initial.lastIndexOf('.')
        val end = if (selectStem && dot > 0) dot else initial.length
        mutableStateOf(TextFieldValue(initial, TextRange(0, end)))
    }
    val name = value.text.trim()
    val ok = Names.isValid(name) && (acceptInitial || name != initial)
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (ok) onConfirm(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { Button({ onConfirm(name) }, Modifier.focusRing(Pill), enabled = ok) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss, Modifier.focusRing(Pill)) { Text("Cancelar") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { Button(onConfirm, Modifier.focusRequester(focus).focusRing(Pill)) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss, Modifier.focusRing(Pill)) { Text("Cancelar") } },
    )
}

/** Tamaño recursivo, cuenta, fecha y ruta; muestra "calculando…" mientras suma. */
@Composable
fun PropertiesDialog(paths: List<Path>, fileOps: FileOps, onDismiss: () -> Unit) {
    data class Sum(val bytes: Long, val files: Int, val folders: Int, val modified: Long)
    val sum by produceState<Sum?>(null, paths) {
        var bytes = 0L; var files = 0; var folders = 0; var modified = 0L
        for (p in paths) {
            val one = fileOps.properties(p)
            bytes += one.totalBytes; files += one.fileCount; folders += one.folderCount
            modified = maxOf(modified, one.modifiedMillis)
        }
        value = Sum(bytes, files, folders, modified)
    }
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (paths.size == 1) paths[0].fileName.toString() else "${paths.size} elementos") },
        text = {
            Column {
                val s = sum
                Line("Tamaño", if (s == null) "calculando…" else Fmt.size(s.bytes))
                Line("Contiene", if (s == null) "calculando…" else "${s.files} archivos, ${s.folders} carpetas")
                if (s != null && s.modified > 0) Line("Modificado", Fmt.date(s.modified))
                Line("Ruta", if (paths.size == 1) paths[0].toString() else paths[0].parent?.toString().orEmpty())
            }
        },
        confirmButton = { Button(onDismiss, Modifier.focusRequester(focus).focusRing(Pill)) { Text("Cerrar") } },
    )
}

@Composable
private fun Line(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Tone.Muted, fontSize = 12.sp)
        Text(value, fontSize = 14.sp)
    }
}

/** Ya existe algo con ese nombre: omitir, reemplazar o conservar ambos; no se cierra sin decidir. */
@Composable
fun ConflictDialog(existing: Path, onResolve: (ConflictChoice, Boolean) -> Unit, onCancelOperation: () -> Unit) {
    var all by remember { mutableStateOf(false) }
    val focus = rememberInitialFocus()
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Ya existe") },
        text = {
            Column {
                Text("\"${existing.fileName}\" ya está en la carpeta de destino.")
                Spacer(Modifier.height(12.dp))
                Button({ onResolve(ConflictChoice.SKIP, all) }, Modifier.fillMaxWidth().focusRequester(focus).focusRing(Pill)) { Text("Omitir") }
                OutlinedButton({ onResolve(ConflictChoice.KEEP_BOTH, all) }, Modifier.fillMaxWidth().focusRing(Pill)) { Text("Conservar ambos") }
                OutlinedButton({ onResolve(ConflictChoice.REPLACE, all) }, Modifier.fillMaxWidth().focusRing(Pill)) { Text("Reemplazar") }
                Row(
                    Modifier.fillMaxWidth().focusRing(RoundedCornerShape(8.dp)).clickable { all = !all }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(all, null)
                    Text("Aplicar a todos", Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onCancelOperation, Modifier.focusRing(Pill)) { Text("Cancelar la operación") } },
    )
}
