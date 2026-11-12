package local.archivero

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.archivero.core.EntryKind
import local.archivero.core.FileEntry
import local.archivero.core.ListError
import local.archivero.core.ListResult
import local.archivero.ui.PaneContent
import local.archivero.ui.PaneState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

@OptIn(ExperimentalCoroutinesApi::class)
class PaneStateTest {
    private val root: Path = Paths.get("/storage/emulated/0")
    private val download: Path = root.resolve("Download")
    private val smash: Path = download.resolve("Smash")

    private fun entry(p: Path, dir: Boolean = false) =
        FileEntry(p, p.fileName.toString(), dir, 1, 0, false, if (dir) EntryKind.FOLDER else EntryKind.OTHER)

    private fun ok(vararg e: FileEntry) = ListResult.Ok(e.toList())

    private fun TestScope.pane(start: Path = download, list: suspend (Path) -> ListResult) =
        PaneState(backgroundScope, start, { root }, list)

    @Test fun navegar_carga_y_publica_Ready() = runTest {
        val a = entry(smash.resolve("a.txt"))
        val pane = pane { if (it == smash) ok(a) else ok() }
        pane.navigate(smash)
        assertEquals(PaneContent.Loading, pane.content.value)
        runCurrent()
        assertEquals(smash, pane.path.value)
        assertEquals(PaneContent.Ready(listOf(a)), pane.content.value)
    }

    @Test fun back_regresa_a_la_carpeta_anterior() = runTest {
        val pane = pane { ok() }
        runCurrent()
        assertFalse(pane.back())
        pane.navigate(smash); runCurrent()
        assertTrue(pane.back()); runCurrent()
        assertEquals(download, pane.path.value)
        assertEquals(smash, pane.highlight.value)
        assertFalse(pane.back())
    }

    @Test fun up_en_la_raiz_del_volumen_devuelve_false() = runTest {
        val pane = pane { ok() }
        assertTrue(pane.up()); runCurrent()
        assertEquals(root, pane.path.value)
        assertEquals(download, pane.highlight.value)
        assertFalse(pane.up())
        assertEquals(root, pane.path.value)
    }

    @Test fun error_de_listado_publica_Failed_y_refresh_reintenta() = runTest {
        var fail = true
        val pane = pane { if (fail) ListResult.Error(ListError.NO_ACCESS) else ok() }
        runCurrent()
        assertEquals(PaneContent.Failed(ListError.NO_ACCESS), pane.content.value)
        fail = false
        pane.refresh(); runCurrent()
        assertEquals(PaneContent.Ready(emptyList()), pane.content.value)
    }

    @Test fun navegar_limpia_la_seleccion() = runTest {
        val a = entry(download.resolve("a.txt"))
        val pane = pane { if (it == download) ok(a) else ok() }
        runCurrent()
        pane.toggleSelect(a.path)
        assertEquals(setOf(a.path), pane.selection.value)
        pane.navigate(smash)
        assertEquals(emptySet<Path>(), pane.selection.value)
    }

    @Test fun respuesta_lenta_de_una_carpeta_anterior_no_pisa_la_actual() = runTest {
        val slow = CompletableDeferred<ListResult>()
        val viejo = entry(smash.resolve("viejo.txt"))
        val nuevo = entry(root.resolve("nuevo.txt"))
        val pane = pane {
            when (it) { smash -> slow.await(); root -> ok(nuevo); else -> ok() }
        }
        runCurrent()
        pane.navigate(smash); runCurrent()
        pane.navigate(root); runCurrent()
        slow.complete(ok(viejo)); runCurrent()
        assertEquals(root, pane.path.value)
        assertEquals(PaneContent.Ready(listOf(nuevo)), pane.content.value)
    }

    @Test fun breadcrumbs_de_Interno_Download_Smash() = runTest {
        val pane = pane(smash) { ok() }
        assertEquals(
            listOf("Interno" to root, "Download" to download, "Smash" to smash),
            pane.breadcrumbs(root, "Interno"),
        )
    }

    @Test fun refrescar_quita_de_la_seleccion_lo_que_ya_no_existe() = runTest {
        val a = entry(download.resolve("a.txt")); val b = entry(download.resolve("b.txt"))
        var both = true
        val pane = pane { if (both) ok(a, b) else ok(b) }
        runCurrent()
        pane.selectAll()
        assertEquals(setOf(a.path, b.path), pane.selection.value)
        both = false
        pane.refresh(); runCurrent()
        assertEquals(setOf(b.path), pane.selection.value)
    }
}
