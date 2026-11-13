package io.github.v0uer.vfiles.ui

import android.view.KeyEvent
import kotlin.math.abs

enum class PadCommand { OPEN, BACK, SELECT, MENU, PREV_PANE, NEXT_PANE, TOGGLE_DUAL, FAVORITES, SHRINK_PANE, GROW_PANE }

object GamepadMap {
    fun commandFor(keyCode: Int): PadCommand? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> PadCommand.OPEN
        KeyEvent.KEYCODE_BUTTON_B -> PadCommand.BACK
        KeyEvent.KEYCODE_BUTTON_X -> PadCommand.SELECT
        KeyEvent.KEYCODE_BUTTON_Y -> PadCommand.MENU
        KeyEvent.KEYCODE_BUTTON_L1 -> PadCommand.PREV_PANE
        KeyEvent.KEYCODE_BUTTON_R1 -> PadCommand.NEXT_PANE
        KeyEvent.KEYCODE_BUTTON_START -> PadCommand.TOGGLE_DUAL
        KeyEvent.KEYCODE_BUTTON_SELECT -> PadCommand.FAVORITES
        KeyEvent.KEYCODE_BUTTON_L2 -> PadCommand.SHRINK_PANE
        KeyEvent.KEYCODE_BUTTON_R2 -> PadCommand.GROW_PANE
        else -> null
    }
}

enum class PadDirection(val keyCode: Int) {
    UP(KeyEvent.KEYCODE_DPAD_UP),
    DOWN(KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT(KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT(KeyEvent.KEYCODE_DPAD_RIGHT),
}

/**
 * Convierte la posición de la palanca en pasos de dirección: uno al inclinarla y,
 * si se sostiene, otro tras [firstRepeatMs] y luego cada [repeatMs].
 */
class StickRepeater(
    private val deadZone: Float = 0.5f,
    private val firstRepeatMs: Long = 350,
    private val repeatMs: Long = 120,
) {
    var held: PadDirection? = null
        private set
    private var nextAt = 0L

    /** Nueva lectura de la palanca. Devuelve la dirección a emitir ahora, si toca. */
    fun update(x: Float, y: Float, nowMs: Long): PadDirection? {
        val direction = when {
            maxOf(abs(x), abs(y)) < deadZone -> null
            abs(x) > abs(y) -> if (x > 0) PadDirection.RIGHT else PadDirection.LEFT
            else -> if (y > 0) PadDirection.DOWN else PadDirection.UP
        }
        if (direction == held) return null
        held = direction
        nextAt = nowMs + firstRepeatMs
        return direction
    }

    /** Paso del reloj mientras la palanca sigue inclinada. */
    fun tick(nowMs: Long): PadDirection? {
        val direction = held ?: return null
        if (nowMs < nextAt) return null
        nextAt = nowMs + repeatMs
        return direction
    }

    fun reset() { held = null }
}

/**
 * Puente entre la actividad, que es quien recibe los botones, y la pantalla, que sabe qué hacer.
 * El manejador devuelve false si no le toca (y el botón sigue su camino normal).
 */
object PadBus {
    @Volatile var handler: ((PadCommand) -> Boolean)? = null
}
