package io.github.v0uer.vfiles.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.Color
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.v0uer.vfiles.VFilesApp
import io.github.v0uer.vfiles.core.FileEntry
import io.github.v0uer.vfiles.core.ListResult
import io.github.v0uer.vfiles.core.Lister
import io.github.v0uer.vfiles.core.OpFailure
import io.github.v0uer.vfiles.core.OpRequest
import io.github.v0uer.vfiles.core.OpStatus
import io.github.v0uer.vfiles.core.Names
import io.github.v0uer.vfiles.core.Zip
import io.github.v0uer.vfiles.core.ZipLocation
import io.github.v0uer.vfiles.data.Favorite
import io.github.v0uer.vfiles.data.Settings
import io.github.v0uer.vfiles.data.moveItem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Pantalla principal. Espera a tener los ajustes y arranca en la carpeta inicial. */
@Composable
fun BrowserScreen() {
    val app = LocalContext.current.applicationContext as VFilesApp
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    settings?.let { Browser(app, it) }
}

/** Diálogo abierto en este momento. */
private sealed interface Ask {
    data class Rename(val target: Path) : Ask
    data class NewFolder(val dir: Path) : Ask
    data class Delete(val targets: List<Path>) : Ask
    data class Props(val targets: List<Path>) : Ask
    data class Compress(val sources: List<Path>, val dir: Path, val suggested: String) : Ask
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Browser(app: VFilesApp, settings: Settings) {
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
    val canBack by active.canGoBack.collectAsStateWithLifecycle()
    val selection by active.selection.collectAsStateWithLifecycle()
    val path by active.path.collectAsStateWithLifecycle()
    /** Si el panel activo está dentro de un zip, cuál (null: carpeta normal). */
    val activeZip by produceState<ZipLocation?>(null, path) { value = withContext(Dispatchers.IO) { zipAt(path) } }
    val clip by browser.clipboard.collectAsStateWithLifecycle()
    val volumes by app.volumes.volumes.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var ask by remember { mutableStateOf<Ask?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var showTrash by remember { mutableStateOf(false) }
    /** Panel donde se está buscando (null: ninguno) y el texto de la búsqueda. */
    var searchPane by remember { mutableStateOf<PaneState?>(null) }
    var search by remember { mutableStateOf("") }
    /** Panel sobre el que están abiertos los favoritos (null: cerrados). */
    var drawerPane by remember { mutableStateOf<PaneState?>(null) }
    var drawerWantsFocus by remember { mutableStateOf(false) }
    val ops by app.queue.states.collectAsStateWithLifecycle()
    // Reparto entre los dos paneles: se mueve en vivo al arrastrar y se guarda al soltar.
    var split by remember { mutableFloatStateOf(Split.clamp(settings.split)) }
    var dragging by remember { mutableStateOf(false) }
    /** Posición sin imán mientras se arrastra: el imán solo cambia lo que se ve. */
    var rawSplit by remember { mutableFloatStateOf(split) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(settings.split) { if (!dragging) split = Split.clamp(settings.split) }
    val say: (String) -> Unit = { text ->
        scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(text) }
    }
    val update: ((Settings) -> Settings) -> Unit = { change -> scope.launch { app.prefs.update(change) } }
    val refreshBoth = { browser.left.refresh(); browser.right.refresh() }
    val saveSplit: () -> Unit = { val value = split; update { it.copy(split = value) } }

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
    // Las que terminan bien se quitan solas de la lista; las que fallan se quedan hasta que se cierren.
    LaunchedEffect(browser) {
        app.queue.finished.collect {
            app.volumes.refresh(); refreshBoth()
            if (app.queue.states.value.none { it.status == OpStatus.FAILED }) app.queue.clearFinished()
        }
    }
    // Al abrir, se limpia de la papelera lo que ya cumplió su plazo.
    LaunchedEffect(Unit) {
        app.prefs.ensureQuickAccess()
        withContext(Dispatchers.IO) {
            app.volumes.volumes.value.forEach { v -> runCatching { app.trash.purgeExpired(v.root) } }
            // Lo extraído para abrir en la sesión anterior ya no hace falta.
            runCatching { context.cacheDir.resolve(OPENED_DIR).deleteRecursively() }
        }
    }

    // El permiso de notificaciones se pide la primera vez que se encola algo; si se niega, la operación corre igual.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var askedNotifications by remember { mutableStateOf(false) }
    val enqueue: (OpRequest) -> Unit = { request ->
        if (!askedNotifications && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        app.enqueue(request)
    }

    val closeDrawer: () -> Unit = {
        drawerPane?.let { pane -> drawerPane = null; pane.requestFocus() }
    }
    // Si se cierra el segundo panel, se cierra lo que tenía abierto encima.
    LaunchedEffect(dual) {
        if (!dual) {
            if (searchPane === browser.right) searchPane = null
            if (drawerPane === browser.right) drawerPane = null
        }
    }

    BackHandler(enabled = drawerPane != null || selection.isNotEmpty() || searchPane != null || canBack) {
        when {
            drawerPane != null -> closeDrawer()
            active.selection.value.isNotEmpty() -> active.clearSelection()
            searchPane != null -> searchPane = null
            else -> active.back()
        }
    }

    val openFile: (FileEntry) -> Unit = { e ->
        if (!e.inArchive) {
            if (!Opener.open(context, e.path, chooser = false)) say("No hay app para abrir este archivo")
        } else scope.launch {
            // Lo de adentro de un zip se extrae a la caché de la app y se abre desde ahí.
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val loc = Zip.locate(e.path) ?: throw OpFailure(OpFailure.Kind.SOURCE_GONE, e.path)
                    val dir = context.cacheDir.toPath().resolve(OPENED_DIR).resolve(Integer.toHexString(e.path.toString().hashCode()))
                    Zip.extractOne(loc.zip, loc.inner, dir.resolve(e.name))
                }
            }
            out.fold(
                onSuccess = { p -> if (!Opener.open(context, p, chooser = false)) say("No hay app para abrir este archivo") },
                onFailure = { err -> say(Fmt.failure((err as? OpFailure)?.kind ?: OpFailure.Kind.IO)) },
            )
        }
    }

    /**
     * Extrae lo seleccionado en [destBase]. Dentro de un zip, cada elemento sale tal cual;
     * zips seleccionados desde fuera se extraen cada uno a una carpeta con su nombre.
     */
    val extractTo: (PaneState, List<Path>, Path) -> Unit = { pane, picked, destBase ->
        val loc = zipAt(pane.path.value)
        when {
            picked.isEmpty() -> Unit
            zipAt(destBase) != null -> say(ZIP_READ_ONLY)
            loc != null -> {
                val inners = picked.map { p -> p.fileName.toString().let { n -> if (loc.inner.isEmpty()) n else "${loc.inner}/$n" } }
                enqueue(OpRequest.Extract(loc.zip, inners, destBase)); pane.clearSelection()
            }
            else -> {
                for (zip in picked) {
                    val stem = zip.fileName.toString().dropLast(4).ifEmpty { "zip" }
                    val folder = if (Names.existsIgnoreCase(destBase, stem) != null) Names.keepBoth(destBase, stem) else stem
                    enqueue(OpRequest.Extract(zip, listOf(""), destBase.resolve(folder)))
                }
                pane.clearSelection()
            }
        }
    }

    val perform: (Action) -> Unit = { action ->
        val pane = browser.active
        val picked = pane.selectedInOrder()
        val here = pane.path.value
        val otherPath = browser.other.path.value
        // Lo que escribiría dentro de un zip no se hace: son de solo lectura.
        val writesTo = when (action) {
            Action.COPY_TO_OTHER, Action.MOVE_TO_OTHER -> otherPath
            Action.PASTE, Action.NEW_FOLDER -> here
            else -> null
        }
        if (writesTo != null && zipAt(writesTo) != null) say(ZIP_READ_ONLY)
        else when (action) {
            Action.COPY_TO_OTHER -> if (picked.isNotEmpty()) {
                enqueue(OpRequest.Copy(picked, browser.other.path.value)); pane.clearSelection()
            }
            Action.MOVE_TO_OTHER -> if (picked.isNotEmpty()) {
                if (browser.other.path.value == here) say("Los dos paneles están en la misma carpeta")
                else { enqueue(OpRequest.Move(picked, browser.other.path.value)); pane.clearSelection() }
            }
            Action.COPY, Action.CUT -> if (picked.isNotEmpty()) {
                browser.clipboard.value = Clip(picked, cut = action == Action.CUT); pane.clearSelection()
            }
            Action.PASTE -> browser.clipboard.value?.let { c ->
                when {
                    !c.cut -> enqueue(OpRequest.Copy(c.paths, here))
                    c.paths.all { it.parent == here } -> say("Ya están en esta carpeta")
                    else -> enqueue(OpRequest.Move(c.paths, here))
                }
                browser.clipboard.value = null
            }
            Action.FAVORITE -> picked.singleOrNull()?.let { folder ->
                val target = folder.toString()
                if (current.favorites.any { it.path == target }) say("Ya está en Acceso rápido")
                else {
                    update { s -> s.copy(favorites = s.favorites + Favorite(target, folder.fileName?.toString() ?: target)) }
                    say("Anclada a Acceso rápido")
                }
                pane.clearSelection()
            }
            Action.RENAME -> picked.singleOrNull()?.let { ask = Ask.Rename(it) }
            Action.TRASH -> if (picked.isNotEmpty()) { enqueue(OpRequest.ToTrash(picked)); pane.clearSelection() }
            Action.DELETE -> if (picked.isNotEmpty()) ask = Ask.Delete(picked)
            Action.SHARE -> if (!Opener.share(context, picked)) say("No hay archivos que compartir; las carpetas no se comparten")
            Action.OPEN_WITH -> picked.singleOrNull()?.let { p ->
                if (Files.isDirectory(p)) pane.navigate(p)
                else if (!Opener.open(context, p, chooser = true)) say("No hay app para abrir este archivo")
            }
            Action.PROPERTIES -> if (picked.isNotEmpty()) ask = Ask.Props(picked)
            Action.NEW_FOLDER -> ask = Ask.NewFolder(here)
            Action.SELECT_ALL -> pane.selectAll()
            Action.EXTRACT -> extractTo(pane, picked, zipAt(here)?.zip?.parent ?: here)
            Action.EXTRACT_TO_OTHER -> extractTo(pane, picked, otherPath)
            Action.COMPRESS -> if (picked.isNotEmpty()) {
                val first = picked[0]
                val base = if (picked.size == 1) {
                    first.fileName.toString().let { n -> if (Files.isDirectory(first)) n else n.substringBeforeLast('.', n) }
                } else here.fileName?.toString() ?: "Archivos"
                val name = "$base.zip".let { if (Names.existsIgnoreCase(here, it) != null) Names.keepBoth(here, it) else it }
                ask = Ask.Compress(picked, here, name)
            }
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

    // Control físico: X selecciona lo enfocado, Y abre el menú, L1/R1 cambian de panel,
    // Start alterna los dos paneles y Select abre los favoritos sobre el panel activo.
    val sidebarFocus = remember { FocusRequester() }
    var sidebarFocused by remember { mutableStateOf(false) }
    DisposableEffect(browser) {
        PadBus.handler = handler@{ command ->
            if (showTrash || ask != null) return@handler false
            val drawerFocused = drawerPane != null && sidebarFocused
            when (command) {
                PadCommand.OPEN, PadCommand.BACK -> false
                PadCommand.SELECT ->
                    if (drawerFocused || searchPane != null) false
                    else { browser.active.focused?.let { browser.active.toggleSelect(it) }; true }
                PadCommand.MENU -> if (drawerFocused) false else { menuOpen = true; true }
                PadCommand.PREV_PANE -> { drawerPane = null; browser.setActive(left = true); browser.active.requestFocus(); true }
                PadCommand.NEXT_PANE -> { drawerPane = null; browser.setActive(left = false); browser.active.requestFocus(); true }
                PadCommand.TOGGLE_DUAL -> { drawerPane = null; browser.toggleDual(); browser.active.requestFocus(); true }
                PadCommand.GROW_PANE, PadCommand.SHRINK_PANE ->
                    if (!browser.dual.value || !current.splitAdjustable) false
                    else {
                        split = Split.step(split, browser.activeIsLeft.value, grow = command == PadCommand.GROW_PANE)
                        saveSplit(); true
                    }
                PadCommand.FAVORITES -> {
                    if (drawerPane === browser.active) closeDrawer()
                    else { drawerWantsFocus = true; drawerPane = browser.active }
                    true
                }
            }
        }
        onDispose { PadBus.handler = null }
    }

    // "Favorito" solo se ofrece cuando lo seleccionado es una carpeta.
    val folderPicked = remember(selection) { selection.singleOrNull()?.let { Files.isDirectory(it) } == true }
    val zipsPicked = remember(selection) { selection.isNotEmpty() && selection.all { Zip.isZipName(it.fileName.toString()) } }
    val actions = browser.actionsFor(selection.size, dual, inArchive = activeZip != null, zipsOnly = zipsPicked && activeZip == null)
        .filter { it != Action.FAVORITE || folderPicked }

    /** Un panel completo: su cabecera, su lista (o búsqueda) y sus favoritos encima al pedirlos. */
    val paneSlot: @Composable (PaneState, Modifier) -> Unit = { pane, modifier ->
        val isActive = pane === active
        val panePath by pane.path.collectAsStateWithLifecycle()
        val paneBack by pane.canGoBack.collectAsStateWithLifecycle()
        val paneVolume = volumes.filter { panePath.startsWith(it.root) }.maxByOrNull { it.root.nameCount }
        val crumbs = remember(panePath, paneVolume) {
            pane.breadcrumbs(paneVolume?.root ?: app.volumes.rootOf(panePath), paneVolume?.label ?: "Interno")
        }
        val activate by rememberUpdatedState { browser.setActive(left = pane === browser.left) }
        val searching = searchPane === pane
        BoxWithConstraints(
            modifier.fillMaxHeight().clipToBounds()
                .pointerInput(Unit) {
                    awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); activate() }
                }
                .onFocusChanged { if (it.hasFocus) activate() },
        ) {
            Column(Modifier.fillMaxSize()) {
                PaneHeader(
                    crumbs = crumbs,
                    canBack = paneBack,
                    canUp = remember(panePath, volumes) { pane.canGoUp() },
                    active = isActive,
                    showActive = dual,
                    settings = settings,
                    onMenu = { activate(); drawerPane = if (drawerPane === pane) null else pane },
                    onBack = { if (searching) searchPane = null else pane.back() },
                    onUp = { pane.up() },
                    onCrumb = { pane.navigate(it) },
                    onSearch = { activate(); search = ""; searchPane = pane },
                    onSort = { sort -> update { it.copy(sort = sort) } },
                    onToggleGrid = { update { it.copy(gridView = !it.gridView) } },
                    onToggleHidden = { update { it.copy(showHidden = !it.showHidden) } },
                    onSetStart = { val here = panePath.toString(); update { it.copy(startPath = here) }; say("V Files abrirá aquí") },
                    onTrash = { showTrash = true },
                    onNewFolder = { activate(); perform(Action.NEW_FOLDER) },
                    onSelectAll = { activate(); perform(Action.SELECT_ALL) },
                    onToggleSplit = { update { it.copy(splitAdjustable = !it.splitAdjustable) } },
                    paneToggle = when {
                        !dual -> PaneToggle(Ico.splitV, "Abrir segundo panel") { browser.toggleDual() }
                        pane === browser.right -> PaneToggle(Ico.close, "Cerrar este panel") { browser.toggleDual() }
                        else -> null
                    },
                    searchField = if (searching) {
                        { SearchField(search, { search = it }, "Buscar en ${panePath.fileName ?: paneVolume?.label ?: panePath}") }
                    } else null,
                )
                if (searching) {
                    SearchView(
                        root = panePath, query = search, showHidden = settings.showHidden,
                        onPick = { e ->
                            searchPane = null
                            if (e.isDirectory) pane.navigate(e.path)
                            else e.path.parent?.let { folder ->
                                // Va a su carpeta y lo deja seleccionado y a la vista.
                                pane.clearSelection()
                                pane.navigate(folder, highlight = e.path)
                                pane.toggleSelect(e.path)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    PaneView(pane, isActive, settings.gridView, openFile, Modifier.weight(1f)) { activate() }
                }
            }
            Drawer(drawerPane === pane, onClose = closeDrawer) {
                LaunchedEffect(Unit) {
                    if (drawerWantsFocus) { drawerWantsFocus = false; runCatching { sidebarFocus.requestFocus() } }
                }
                Sidebar(
                    favorites = settings.favorites,
                    volumes = volumes,
                    currentPath = panePath,
                    onNavigate = { pane.navigate(it); closeDrawer() },
                    onClose = closeDrawer,
                    onRemove = { fav -> update { s -> s.copy(favorites = s.favorites - fav) } },
                    onMove = { from, to -> update { s -> s.copy(favorites = moveItem(s.favorites, from, to)) } },
                    // Con el control, el foco no se sale de los favoritos hasta cerrarlos.
                    modifier = Modifier.onFocusChanged { sidebarFocused = it.hasFocus }
                        .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
                    firstItemFocus = sidebarFocus,
                    width = minOf(maxWidth * 0.85f, 300.dp),
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (showTrash) {
                TrashScreen(app.trash, volumes, onClose = { showTrash = false }, onChanged = { refreshBoth() }, say = say)
            } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val stacked = maxHeight > maxWidth
                val adjustable = settings.splitAdjustable
                val first = if (adjustable) split else Split.EVEN
                val totalPx = (if (stacked) constraints.maxHeight else constraints.maxWidth).toFloat()
                val handle: @Composable () -> Unit = {
                    SplitHandle(
                        sideBySide = !stacked,
                        adjustable = adjustable,
                        onDrag = { delta ->
                            if (!dragging) { dragging = true; rawSplit = split }
                            rawSplit = Split.drag(rawSplit, delta, totalPx)
                            val next = Split.snap(rawSplit)
                            // Un toque leve al pegarse a la mitad, como un imán.
                            if (next == Split.EVEN && split != Split.EVEN) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            split = next
                        },
                        onDragStopped = { dragging = false; saveSplit() },
                        onReset = { split = Split.EVEN; saveSplit() },
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    if (dual && stacked) {
                        Column(Modifier.weight(1f)) {
                            paneSlot(browser.left, Modifier.weight(first))
                            handle()
                            paneSlot(browser.right, Modifier.weight(1f - first))
                        }
                    } else {
                        Row(Modifier.weight(1f)) {
                            paneSlot(browser.left, Modifier.weight(if (dual) first else 1f))
                            if (dual) {
                                handle()
                                paneSlot(browser.right, Modifier.weight(1f - first))
                            }
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
            }
        }
        OpsPanel(
            states = ops,
            onCancel = { app.queue.cancel(it) },
            onResolve = { id, choice, all -> app.queue.resolveConflict(id, choice, all) },
            onDismissFailed = { app.queue.clearFinished() },
        )
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
            onConfirm = { ask = null; enqueue(OpRequest.Delete(a.targets)); browser.active.clearSelection() },
        )
        is Ask.Props -> PropertiesDialog(a.targets, app.fileOps) { ask = null }
        is Ask.Compress -> NameDialog(
            "Comprimir", a.suggested, "Comprimir", selectStem = true, acceptInitial = true,
            onDismiss = { ask = null },
            onConfirm = { typed ->
                ask = null
                val name = if (Zip.isZipName(typed)) typed else "$typed.zip"
                if (Names.existsIgnoreCase(a.dir, name) != null) say("Ya existe algo con ese nombre")
                else { enqueue(OpRequest.Compress(a.sources, a.dir.resolve(name))); browser.active.clearSelection() }
            },
        )
    }
}

/** Cajón que entra desde la izquierda sobre un velo; tocar el velo lo cierra. */
@Composable
private fun Drawer(open: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                    .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
            )
        }
        AnimatedVisibility(open, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
            content()
        }
    }
}

private const val OPENED_DIR = "abiertos"
private const val ZIP_READ_ONLY = "Un zip es de solo lectura: extrae primero lo que necesites"

/** Dónde está [p] dentro de un zip, o null si es una carpeta normal. Toca el disco: usar poco. */
private fun zipAt(p: Path): ZipLocation? = if (Files.isDirectory(p)) null else Zip.locate(p)
