package local.archivero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import local.archivero.core.FileEntry
import local.archivero.core.Search
import java.nio.file.Path

private const val MAX_RESULTS = 1000

/** El campo que ocupa el lugar de las migas mientras se busca. Toma el foco al aparecer. */
@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(8.dp)
    BasicTextField(
        value = query, onValueChange = onQuery, singleLine = true,
        textStyle = TextStyle(color = Tone.Text, fontSize = 15.sp),
        cursorBrush = SolidColor(Tone.Accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth().height(38.dp).clip(shape).background(Tone.SurfaceHigh)
            .focusRing(shape).focusRequester(focus),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(placeholder, color = Tone.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

/**
 * Resultados de buscar [query] por nombre desde [root]. Espera 300 ms tras la última tecla;
 * cada consulta nueva cancela la anterior y los resultados van apareciendo.
 */
@Composable
fun SearchView(
    root: Path,
    query: String,
    showHidden: Boolean,
    onPick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val results = remember { mutableStateListOf<FileEntry>() }
    var searching by remember { mutableStateOf(false) }
    var finishedFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(root, query, showHidden) {
        finishedFor = null
        if (query.isBlank()) { results.clear(); searching = false; return@LaunchedEffect }
        searching = true
        delay(300)
        results.clear()
        Search.byName(root, query, showHidden).take(MAX_RESULTS).collect { results.add(it) }
        searching = false
        finishedFor = query
    }

    Column(modifier.fillMaxSize()) {
        val status = when {
            query.isBlank() -> "Escribe para buscar en esta carpeta y las de adentro"
            searching -> "Buscando…" + if (results.isNotEmpty()) " ${results.size}" else ""
            results.isEmpty() && finishedFor == query -> "Sin resultados"
            results.size >= MAX_RESULTS -> "Primeros $MAX_RESULTS resultados"
            else -> "${results.size} resultados"
        }
        Text(status, color = Tone.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.path.toString() }) { e ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).focusRing().clickable { onPick(e) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EntryIcon(e, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        val where = e.path.parent?.let { runCatching { root.relativize(it).toString() }.getOrNull() }.orEmpty()
                        Text(
                            if (where.isEmpty()) "en esta carpeta" else where,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Tone.Muted,
                        )
                    }
                }
            }
        }
    }
}
