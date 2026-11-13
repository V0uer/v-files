package local.archivero

import local.archivero.data.Favorite
import local.archivero.data.QuickAccess
import local.archivero.ui.Split
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QuickAccessTest {
    private val root = "/storage/emulated/0"

    @Test fun por_defecto_solo_las_carpetas_tipicas_que_existen() {
        val exist = setOf("$root/Download", "$root/Documents", "$root/Pictures", "$root/Music")
        val d = QuickAccess.defaults(root) { it in exist }
        assertEquals(listOf("Descargas", "Documentos", "Imágenes", "Música"), d.map { it.label })
        assertEquals("$root/Download", d[0].path)
    }

    @Test fun el_escritorio_va_primero_si_existe() {
        val d = QuickAccess.defaults(root) { true }
        assertEquals(listOf("Escritorio", "Descargas", "Documentos", "Imágenes", "Videos", "Música"), d.map { it.label })
        assertEquals("$root/Movies", d[4].path)
    }

    @Test fun al_actualizar_se_agregan_las_tipicas_y_se_conservan_las_propias_sin_duplicar() {
        val defaults = QuickAccess.defaults(root) { !it.endsWith("Desktop") }
        val mine = listOf(Favorite("$root/Download", "Bajadas"), Favorite("$root/ROMs", "ROMs"))
        val merged = QuickAccess.merge(defaults, mine)
        assertEquals(listOf("Descargas", "Documentos", "Imágenes", "Videos", "Música", "ROMs"), merged.map { it.label })
    }

    @Test fun reconoce_las_carpetas_tipicas_para_su_icono() {
        assertEquals(QuickAccess.Known.DOWNLOADS, QuickAccess.knownOf("$root/Download", root))
        assertEquals(null, QuickAccess.knownOf("$root/ROMs", root))
    }

    @Test fun el_iman_pega_la_division_a_la_mitad_solo_si_esta_activo() {
        assertEquals(0.5f, Split.snap(0.53f, enabled = true), 0.0001f)
        assertEquals(0.5f, Split.snap(0.465f, enabled = true), 0.0001f)
        assertEquals(0.53f, Split.snap(0.53f, enabled = false), 0.0001f)
        assertEquals(0.58f, Split.snap(0.58f, enabled = true), 0.0001f)
    }
}
