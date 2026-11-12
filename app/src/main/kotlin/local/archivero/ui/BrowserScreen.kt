package local.archivero.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.archivero.ArchiveroApp
import local.archivero.core.FileEntry
import local.archivero.core.ListResult
import local.archivero.core.Lister
import local.archivero.core.OpFailure
import local.archivero.core.OpRequest
import local.archivero.data.Favorite
import local.archivero.data.Settings
import local.archivero.data.moveItem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Pantalla principal. Espera a tener los ajustes y arranca en la carpeta inicial. */
@Composable
fun BrowserScreen() {
    val app = LocalContext.current.applicationContext as ArchiveroApp
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    settings?.let { Browser(app, it) }
}

/** Diálogo abierto en este momento. */
private sealed interface Ask {
    data class Rename(val target: Path) : Ask
    data class NewFolder(val dir: Path) : Ask
    data class Delete(val targets: List<Path>) : Ask
    data class Props(val targets: List<Path>) : Ask
}

@Composable
private fun Browser(app: ArchiveroApp, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(settings)
    val browser = remember {
        val lister: suspend (Path) -> ListResult = { dir ->
            withContext(Dispatchers.IO) { Lister.list(dir, current.showHidden, current.sort) }
        }
        BrowserState(
            PaneState(scope, Paths.get(settings.startPath), app.volumes::rootOf, lister),
            PaneState(scope, Paths.get(settings.secondPanePath ?: settings.startPath), app.volumes::rootOf, lister),
            settings.dualPane,
        )
    }
    val dual by browser.dual.collectAsStateWithLifecycle()
    val activeIsLeft by browser.activeIsLeft.collectAsStateWithLifecycle()
    val active = if (activeIsLeft || !dual) browser.left else browser.right
    val path by active.path.collectAsStateWithLifecycle()
    val canBack by active.canGoBack.collectAsStateWithLifecycle()
    val selection by active.selection.collectAsStateWithLifecycle()
    val clip by browser.clipboard.collectAsStateWithLifecycle()
    val volumes by app.volumes.volumes.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val say: (String) -> Unit = { text ->
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
    }
    val update: ((Settings) -> Settings) -> Unit = { change -> scope.launch { app.prefs.update(change) } }
    val refreshBoth = { browser.left.refresh(); browser.right.refresh() }

    // Cambiar orden u ocultos vuelve a listar; al volver a la app también (pudo cambiar algo fuera).
    var listedWith by remember { mutableStateOf(settings.showHidden to settings.sort) }
    LaunchedEffect(settings.showHidden, settings.sort) {
        val now = settings.showHidden to settings.sort
        if (now != listedWith) { listedWith = now; refreshBoth() }
    }
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) { app.volumes.refresh(); refreshBoth() } else resumedOnce = true
    }
    // Los dos paneles y la ruta del segundo se recuerdan entre sesiones.
    LaunchedEffect(dual) { if (dual != current.dualPane) app.prefs.update { it.copy(dualPane = dual) } }
    LaunchedEffect(browser) {
        browser.right.path.drop(1).collect { p -> app.prefs.update { it.copy(secondPanePath = p.toString()) } }
    }
    // Al terminar una operación, lo que se ve puede haber cambiado.
    LaunchedEffect(browser) { app.queue.finished.collect { app.volumes.refresh(); refreshBoth() } }

    BackHandler(enabled = selection.isNotEmpty() || canBack) {
        if (active.selection.value.isNotEmpty()) active.clearSelection() else active.back()
    }

    val openFile: (FileEntry) -> Unit = { e ->
        if (!Opener.open(context, e.path, chooser = false)) say("No hay app para abrir este archivo")
    }

    val perform: (Action) -> Unit = { action ->
        val pane = browser.active
        val picked = pane.selectedInOrder()
        val here = pane.path.value
        when (action) {
            Action.COPY_TO_OTHER -> if (picked.isNotEmpty()) {
                app.enqueue(OpRequest.Copy(picked, browser.other.path.value)); pane.clearSelection()
            }
            Action.MOVE_TO_OTHER -> if (picked.isNotEmpty()) {
                if (browser.other.path.value == here) say("Los dos paneles están en la misma carpeta")
                else { app.enqueue(OpRequest.Move(picked, browser.other.path.value)); pane.clearSelection() }
            }
            Action.COPY, Action.CUT -> if (picked.isNotEmpty()) {
                browser.clipboard.value = Clip(picked, cut = action == Action.CUT); pane.clearSelection()
            }
            Action.PASTE -> browser.clipboard.value?.let { c ->
                when {
                    !c.cut -> app.enqueue(OpRequest.Copy(c.paths, here))
                    c.paths.all { it.parent == here } -> say("Ya están en esta carpeta")
                    else -> app.enqueue(OpRequest.Move(c.paths, here))
                }
                browser.clipboard.value = null
            }
            Action.RENAME -> picked.singleOrNull()?.let { ask = Ask.Rename(it) }
            Action.TRASH -> if (picked.isNotEmpty()) { app.enqueue(OpRequest.ToTrash(picked)); pane.clearSelection() }
            Action.DELETE -> if (picked.isNotEmpty()) ask = Ask.Delete(picked)
            Action.SHARE -> if (!Opener.share(context, picked)) say("No hay archivos que compartir; las carpetas no se comparten")
            Action.OPEN_WITH -> picked.singleOrNull()?.let { p ->
                if (Files.isDirectory(p)) pane.navigate(p)
                else if (!Opener.open(context, p, chooser = true)) say("No hay app para abrir este archivo")
            }
            Action.PROPERTIES -> if (picked.isNotEmpty()) ask = Ask.Props(picked)
            Action.NEW_FOLDER -> ask = Ask.NewFolder(here)
            Action.SELECT_ALL -> pane.selectAll()
        }
    }

    /** Corre [block] fuera del hilo principal; si sale bien, vuelve a listar dejando a la vista el resultado. */
    val runOp: (suspend () -> Path) -> Unit = { block ->
        val pane = browser.active
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            result.fold(
                onSuccess = { made ->
                    pane.clearSelection(); browser.other.refresh()
                    pane.navigate(pane.path.value, highlight = made)
                },
                onFailure = { e -> say(Fmt.failure((e as? OpFailure)?.kind ?: OpFailure.Kind.IO)) },
            )
        }
    }

    val volume = volumes.filter { path.startsWith(it.root) }.maxByOrNull { it.root.nameCount }
    val crumbs = remember(path, volume) {
        active.breadcrumbs(volume?.root ?: app.volumes.rootOf(path), volume?.label ?: "Interno")
    }
    val actions = browser.actionsFor(selection.size, dual)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                crumbs = crumbs,
                canBack = canBack,
                canUp = remember(path, volumes) { active.canGoUp() },
                settings = settings,
                dual = dual,
                onBack = { active.back() },
                onUp = { active.up() },
                onCrumb = { active.navigate(it) },
                onSort = { sort -> update { it.copy(sort = sort) } },
                onToggleGrid = { update { it.copy(gridView = !it.gridView) } },
                onToggleHidden = { update { it.copy(showHidden = !it.showHidden) } },
                onSetStart = { val here = path.toString(); update { it.copy(startPath = here) }; say("Archivero abrirá aquí") },
                onToggleDual = { browser.toggleDual() },
                onNewFolder = { perform(Action.NEW_FOLDER) },
                onSelectAll = { perform(Action.SELECT_ALL) },
            )
            Row(Modifier.weight(1f)) {
                Sidebar(
                    compact = dual,
                    favorites = settings.favorites,
                    volumes = volumes,
                    currentPath = path,
                    onNavigate = { active.navigate(it) },
                    onAddCurrent = {
                        val here = path
                        val label = if (here == volume?.root) volume.label else here.fileName?.toString() ?: here.toString()
                        update { s ->
                            if (s.favorites.any { it.path == here.toString() }) s
                            else s.copy(favorites = s.favorites + Favorite(here.toString(), label))
                        }
                    },
                    onRemove = { fav -> update { s -> s.copy(favorites = s.favorites - fav) } },
                    onMove = { from, to -> update { s -> s.copy(favorites = moveItem(s.favorites, from, to)) } },
                )
                PaneSlot(
                    browser.left, active === browser.left, dual, settings.gridView,
                    { browser.setActive(left = true) }, openFile, Modifier.weight(1f),
                )
                if (dual) {
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Tone.Line))
                    PaneSlot(
                        browser.right, active === browser.right, true, settings.gridView,
                        { browser.setActive(left = false) }, openFile, Modifier.weight(1f),
                    )
                }
            }
            if (selection.isNotEmpty() || clip != null) {
                ActionBar(
                    actions = actions, selectionCount = selection.size, clip = clip, dual = dual,
                    onAction = perform,
                    onClear = { if (selection.isNotEmpty()) active.clearSelection() else browser.clipboard.value = null },
                    onMore = { menuOpen = true },
                )
            }
        }
        Box(Modifier.align(Alignment.BottomEnd)) {
            ActionsMenu(menuOpen, actions, clip != null, { menuOpen = false }, perform)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp))
    }

    when (val a = ask) {
        null -> Unit
        is Ask.Rename -> NameDialog(
            "Renombrar", a.target.fileName.toString(), "Renombrar", selectStem = !Files.isDirectory(a.target),
            onDismiss = { ask = null },
            onConfirm = { name -> ask = null; runOp { app.fileOps.rename(a.target, name) } },
        )
        is Ask.NewFolder -> NameDialog(
            "Nueva carpeta", "", "Crear", selectStem = false,
            onDismiss = { ask = null },
            onConfirm = { name -> ask = null; runOp { app.fileOps.createFolder(a.dir, name) } },
        )
        is Ask.Delete -> ConfirmDialog(
            "Borrar definitivo",
            (if (a.targets.size == 1) "\"${a.targets[0].fileName}\" se borrará" else "${a.targets.size} elementos se borrarán") +
                " sin pasar por la papelera. No se puede deshacer.",
            "Borrar",
            onDismiss = { ask = null },
            onConfirm = { ask = null; app.enqueue(OpRequest.Delete(a.targets)); browser.active.clearSelection() },
        )
        is Ask.Props -> PropertiesDialog(a.targets, app.fileOps) { ask = null }
    }
}

/** Un panel con su marca de activo; tocarlo en cualquier punto lo activa. */
@Composable
private fun PaneSlot(
    state: PaneState, active: Boolean, dual: Boolean, grid: Boolean,
    onActivate: () -> Unit, onOpenFile: (FileEntry) -> Unit, modifier: Modifier,
) {
    val path by state.path.collectAsStateWithLifecycle()
    val activate by rememberUpdatedState(onActivate)
    Column(
        modifier.fillMaxHeight().pointerInput(Unit) {
            awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); activate() }
        },
    ) {
        if (dual) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) Tone.Accent else Tone.Line))
            Text(
                path.fileName?.toString() ?: path.toString(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (active) Tone.Text else Tone.Muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        PaneView(state, active, grid, onOpenFile, Modifier.weight(1f), onActivate)
    }
}
