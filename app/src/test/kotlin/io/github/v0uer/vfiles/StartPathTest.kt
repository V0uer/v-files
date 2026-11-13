package io.github.v0uer.vfiles

import io.github.v0uer.vfiles.data.StartPath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StartPathTest {
    private val root = "/storage/emulated/0"
    private val download = "$root/Download"

    @Test fun la_carpeta_por_defecto_no_se_guarda_para_que_siga_al_valor_por_defecto() {
        assertEquals(null, StartPath.toStore(root, default = root))
        assertEquals("$root/ROMs", StartPath.toStore("$root/ROMs", default = root))
    }

    @Test fun descargas_guardada_sola_por_versiones_viejas_se_olvida_una_vez() {
        assertEquals(null, StartPath.migrate(download, oldDefault = download))
        assertEquals("$root/ROMs", StartPath.migrate("$root/ROMs", oldDefault = download))
        assertEquals(null, StartPath.migrate(null, oldDefault = download))
    }
}
