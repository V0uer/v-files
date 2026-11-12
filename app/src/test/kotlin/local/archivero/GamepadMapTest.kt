package local.archivero

import android.view.KeyEvent
import local.archivero.ui.GamepadMap
import local.archivero.ui.PadCommand
import local.archivero.ui.PadDirection
import local.archivero.ui.StickRepeater
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GamepadMapTest {
    @Test fun cada_boton_tiene_su_comando() {
        val expected = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to PadCommand.OPEN,
            KeyEvent.KEYCODE_BUTTON_B to PadCommand.BACK,
            KeyEvent.KEYCODE_BUTTON_X to PadCommand.SELECT,
            KeyEvent.KEYCODE_BUTTON_Y to PadCommand.MENU,
            KeyEvent.KEYCODE_BUTTON_L1 to PadCommand.PREV_PANE,
            KeyEvent.KEYCODE_BUTTON_R1 to PadCommand.NEXT_PANE,
            KeyEvent.KEYCODE_BUTTON_START to PadCommand.TOGGLE_DUAL,
            KeyEvent.KEYCODE_BUTTON_SELECT to PadCommand.FAVORITES,
        )
        for ((code, command) in expected) assertEquals(command, GamepadMap.commandFor(code))
        assertEquals(PadCommand.entries.toSet(), expected.values.toSet())
    }

    @Test fun otras_teclas_no_son_comandos() {
        assertNull(GamepadMap.commandFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(GamepadMap.commandFor(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test fun palanca_dentro_de_la_zona_muerta_no_mueve() {
        val stick = StickRepeater()
        assertNull(stick.update(0.49f, -0.3f, 0))
        assertNull(stick.held)
    }

    @Test fun palanca_elige_el_eje_dominante() {
        assertEquals(PadDirection.RIGHT, StickRepeater().update(0.9f, 0.6f, 0))
        assertEquals(PadDirection.LEFT, StickRepeater().update(-0.9f, 0.6f, 0))
        assertEquals(PadDirection.DOWN, StickRepeater().update(0.2f, 0.8f, 0))
        assertEquals(PadDirection.UP, StickRepeater().update(0.2f, -0.8f, 0))
    }

    @Test fun palanca_sostenida_repite_tras_la_pausa_inicial_y_luego_cada_120_ms() {
        val stick = StickRepeater()
        assertEquals(PadDirection.DOWN, stick.update(0f, 1f, 1000))
        assertNull(stick.update(0f, 0.9f, 1050), "moverse dentro de la misma dirección no repite")
        assertNull(stick.tick(1000 + 349))
        assertEquals(PadDirection.DOWN, stick.tick(1000 + 350))
        assertNull(stick.tick(1000 + 350 + 119))
        assertEquals(PadDirection.DOWN, stick.tick(1000 + 350 + 120))
    }

    @Test fun soltar_la_palanca_detiene_la_repeticion_y_cambiar_de_direccion_emite_al_momento() {
        val stick = StickRepeater()
        stick.update(1f, 0f, 0)
        assertEquals(PadDirection.UP, stick.update(0f, -1f, 10))
        assertNull(stick.update(0f, 0f, 20))
        assertNull(stick.tick(5000))
    }
}
