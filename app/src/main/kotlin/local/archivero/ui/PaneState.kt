package local.archivero.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import local.archivero.core.FileEntry
import local.archivero.core.ListError
import local.archivero.core.ListResult
import java.nio.file.Path

sealed interface PaneContent {
    data object Loading : PaneContent
    data class Ready(val entries: List<FileEntry>) : PaneContent
    data class Failed(val reason: ListError) : PaneContent
}

/**
 * Estado de un panel: carpeta actual, su contenido, la selección y el historial.
 * [rootOf] da la raíz del volumen de una ruta (no se sube por encima de ella).
 * [list] hace el listado; debe cambiar de hilo por su cuenta.
 */
class PaneState(
    private val scope: CoroutineScope,
    startPath: Path,
    private val rootOf: (Path) -> Path,
    private val list: suspend (Path) -> ListResult,
) {
    private val _path = MutableStateFlow(startPath)
    val path: StateFlow<Path> = _path.asStateFlow()

    private val _content = MutableStateFlow<PaneContent>(PaneContent.Loading)
    val content: StateFlow<PaneContent> = _content.asStateFlow()

    private val _selection = MutableStateFlow<Set<Path>>(emptySet())
    val selection: StateFlow<Set<Path>> = _selection.asStateFlow()

    /** Elemento que debe quedar a la vista (y con el foco) cuando termine la carga. */
    private val _highlight = MutableStateFlow<Path?>(null)
    val highlight: StateFlow<Path?> = _highlight.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /** Sube cada vez que alguien pide que este panel tome el foco. */
    private val _focusTick = MutableStateFlow(0)
    val focusTick: StateFlow<Int> = _focusTick.asStateFlow()

    /** Último elemento que tuvo el foco; lo anota la vista. */
    @Volatile var focused: Path? = null

    private val history = ArrayDeque<Path>()
    @Volatile private var generation = 0
    private var job: Job? = null

    init { load(showLoading = true) }

    fun navigate(to: Path, highlight: Path? = null) {
        if (to != _path.value) {
            history.addLast(_path.value)
            if (history.size > 100) history.removeFirst()
            _canGoBack.value = true
        }
        go(to, highlight)
    }

    /** Sube a la carpeta madre; false si ya está en la raíz del volumen. */
    fun up(): Boolean {
        val here = _path.value
        if (here == rootOf(here)) return false
        val parent = here.parent ?: return false
        navigate(parent, highlight = here)
        return true
    }

    /** Regresa por el historial; false si está vacío. */
    fun back(): Boolean {
        val previous = history.removeLastOrNull() ?: return false
        _canGoBack.value = history.isNotEmpty()
        go(previous, highlight = _path.value)
        return true
    }

    fun canGoUp(): Boolean = _path.value.let { it != rootOf(it) && it.parent != null }

    private fun go(to: Path, highlight: Path?) {
        val changed = to != _path.value
        _path.value = to
        _highlight.value = highlight
        if (changed) { _selection.value = emptySet(); focused = null }
        load(showLoading = changed || _content.value !is PaneContent.Ready)
    }

    fun refresh() = load(showLoading = _content.value !is PaneContent.Ready)

    private fun load(showLoading: Boolean) {
        val mine = ++generation
        val target = _path.value
        job?.cancel()
        if (showLoading) _content.value = PaneContent.Loading
        job = scope.launch {
            val result = list(target)
            if (mine != generation) return@launch
            when (result) {
                is ListResult.Ok -> {
                    val present = result.entries.mapTo(HashSet()) { it.path }
                    _selection.update { old -> if (old.all { it in present }) old else old.filterTo(HashSet()) { it in present } }
                    _content.value = PaneContent.Ready(result.entries)
                }
                is ListResult.Error -> {
                    _selection.value = emptySet()
                    _content.value = PaneContent.Failed(result.reason)
                }
            }
        }
    }

    fun toggleSelect(p: Path) = _selection.update { if (p in it) it.minusElement(p) else it.plusElement(p) }

    fun selectAll() {
        val ready = _content.value as? PaneContent.Ready ?: return
        _selection.value = ready.entries.mapTo(HashSet()) { it.path }
    }

    /** La selección en el orden en que se ve en la lista. */
    fun selectedInOrder(): List<Path> {
        val picked = _selection.value
        val ready = _content.value as? PaneContent.Ready ?: return picked.toList()
        return ready.entries.map { it.path }.filter { it in picked }
    }

    fun clearSelection() { _selection.value = emptySet() }

    fun consumeHighlight() { _highlight.value = null }

    fun requestFocus() = _focusTick.update { it + 1 }

    /** Migas desde la raíz del volumen: ("Interno", raíz), ("Download", …), … */
    fun breadcrumbs(volumeRoot: Path, volumeLabel: String): List<Pair<String, Path>> {
        val here = _path.value
        val out = ArrayList<Pair<String, Path>>()
        if (!here.startsWith(volumeRoot)) {
            var cur: Path? = here
            while (cur != null && cur.fileName != null) { out.add(0, cur.fileName.toString() to cur); cur = cur.parent }
            return out
        }
        out.add(volumeLabel to volumeRoot)
        var cur = volumeRoot
        for (part in volumeRoot.relativize(here)) {
            val name = part.toString()
            if (name.isEmpty()) continue
            cur = cur.resolve(name)
            out.add(name to cur)
        }
        return out
    }
}
