package local.archivero.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.archivero.ArchiveroApp
import local.archivero.core.Lister
import local.archivero.data.Favorite
import local.archivero.data.Settings
import local.archivero.data.moveItem
import java.nio.file.Paths

/** Pantalla principal. Espera a tener los ajustes y arranca en la carpeta inicial. */
@Composable
fun BrowserScreen() {
    val app = LocalContext.current.applicationContext as ArchiveroApp
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = null)
    settings?.let { Browser(app, it) }
}

@Composable
private fun Browser(app: ArchiveroApp, settings: Settings) {
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(settings)
    val pane = remember {
        PaneState(scope, Paths.get(settings.startPath), app.volumes::rootOf) { dir ->
            withContext(Dispatchers.IO) { Lister.list(dir, current.showHidden, current.sort) }
        }
    }
    val path by pane.path.collectAsStateWithLifecycle()
    val canBack by pane.canGoBack.collectAsStateWithLifecycle()
    val volumes by app.volumes.volumes.collectAsStateWithLifecycle()

    // Cambiar orden u ocultos vuelve a listar; al volver a la app también (pudo cambiar algo fuera).
    var listedWith by remember { mutableStateOf(settings.showHidden to settings.sort) }
    LaunchedEffect(settings.showHidden, settings.sort) {
        val now = settings.showHidden to settings.sort
        if (now != listedWith) { listedWith = now; pane.refresh() }
    }
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) { app.volumes.refresh(); pane.refresh() } else resumedOnce = true
    }

    BackHandler(enabled = canBack) { pane.back() }

    val update: ((Settings) -> Settings) -> Unit = { change -> scope.launch { app.prefs.update(change) } }
    val volume = volumes.filter { path.startsWith(it.root) }.maxByOrNull { it.root.nameCount }
    val crumbs = remember(path, volume) {
        pane.breadcrumbs(volume?.root ?: app.volumes.rootOf(path), volume?.label ?: "Interno")
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            crumbs = crumbs,
            canBack = canBack,
            canUp = remember(path, volumes) { pane.canGoUp() },
            settings = settings,
            dual = false,
            onBack = { pane.back() },
            onUp = { pane.up() },
            onCrumb = { pane.navigate(it) },
            onSort = { sort -> update { it.copy(sort = sort) } },
            onToggleGrid = { update { it.copy(gridView = !it.gridView) } },
            onToggleHidden = { update { it.copy(showHidden = !it.showHidden) } },
            onSetStart = { val here = path.toString(); update { it.copy(startPath = here) } },
        )
        Row(Modifier.weight(1f)) {
            Sidebar(
                compact = false,
                favorites = settings.favorites,
                volumes = volumes,
                currentPath = path,
                onNavigate = { pane.navigate(it) },
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
            PaneView(pane, active = true, grid = settings.gridView, onOpenFile = {}, modifier = Modifier.weight(1f))
        }
    }
}
