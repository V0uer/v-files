package local.archivero

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.archivero.core.ListResult
import local.archivero.ui.Action
import local.archivero.ui.BrowserState
import local.archivero.ui.PaneState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserLogicTest {
    private val root: Path = Paths.get("/storage/emulated/0")

    private fun TestScope.browser(dual: Boolean = false): BrowserState {
        val pane = { start: Path -> PaneState(backgroundScope, start, { root }) { ListResult.Ok(emptyList()) } }
        return BrowserState(pane(root.resolve("Download")), pane(root.resolve("Roms")), dual)
    }

    @Test fun sin_seleccion_ofrece_crear_pegar_y_seleccionar_todo() = runTest {
        assertEquals(listOf(Action.NEW_FOLDER, Action.PASTE, Action.SELECT_ALL), browser().actionsFor(0, dual = false))
    }

    @Test fun uno_seleccionado_en_dos_paneles() = runTest {
        val a = browser().actionsFor(1, dual = true)
        assertTrue(a.containsAll(listOf(Action.COPY_TO_OTHER, Action.MOVE_TO_OTHER, Action.RENAME)))
    }

    @Test fun varios_seleccionados_no_renombran_ni_abren() = runTest {
        val a = browser().actionsFor(3, dual = true)
        assertFalse(Action.RENAME in a)
        assertFalse(Action.OPEN_WITH in a)
        assertTrue(Action.TRASH in a)
    }

    @Test fun un_panel_no_ofrece_copiar_al_otro() = runTest {
        val a = browser().actionsFor(2, dual = false)
        assertFalse(Action.COPY_TO_OTHER in a)
        assertFalse(Action.MOVE_TO_OTHER in a)
        assertTrue(a.containsAll(listOf(Action.COPY, Action.CUT)))
    }

    @Test fun toggleDual_conserva_la_ruta_del_panel_izquierdo() = runTest {
        val b = browser()
        runCurrent()
        val before = b.left.path.value
        b.toggleDual()
        assertTrue(b.dual.value)
        assertEquals(before, b.left.path.value)
        b.toggleDual()
        assertEquals(before, b.left.path.value)
    }

    @Test fun el_panel_derecho_solo_se_activa_en_dos_paneles() = runTest {
        val b = browser()
        b.setActive(left = false)
        assertSame(b.left, b.active)
        b.toggleDual()
        b.setActive(left = false)
        assertSame(b.right, b.active)
        assertSame(b.left, b.other)
        b.toggleDual()
        assertSame(b.left, b.active)
    }
}
