package io.github.v0uer.vfiles

import io.github.v0uer.vfiles.ui.Split
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SplitTest {
    private fun near(expected: Float, actual: Float) = assertEquals(expected, actual, 0.0001f)

    @Test fun ningun_panel_baja_del_20_por_ciento() {
        near(0.2f, Split.clamp(0.05f))
        near(0.8f, Split.clamp(0.97f))
        near(0.5f, Split.clamp(0.5f))
    }

    @Test fun agrandar_el_primer_panel_sube_su_parte() {
        near(0.6f, Split.step(0.5f, activeIsFirst = true, grow = true))
        near(0.4f, Split.step(0.5f, activeIsFirst = true, grow = false))
    }

    @Test fun agrandar_el_segundo_panel_baja_la_parte_del_primero() {
        near(0.4f, Split.step(0.5f, activeIsFirst = false, grow = true))
        near(0.6f, Split.step(0.5f, activeIsFirst = false, grow = false))
    }

    @Test fun los_pasos_se_detienen_en_los_limites() {
        near(0.8f, Split.step(0.75f, activeIsFirst = true, grow = true))
        near(0.2f, Split.step(0.25f, activeIsFirst = false, grow = true))
    }

    @Test fun arrastrar_suma_la_fraccion_recorrida() {
        near(0.6f, Split.drag(0.5f, deltaPx = 100f, totalPx = 1000f))
        near(0.2f, Split.drag(0.5f, deltaPx = -900f, totalPx = 1000f))
        near(0.5f, Split.drag(0.5f, deltaPx = 40f, totalPx = 0f))
    }
}
