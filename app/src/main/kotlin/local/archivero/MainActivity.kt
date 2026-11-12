package local.archivero

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import local.archivero.ui.ArchiveroTheme
import local.archivero.ui.BrowserScreen
import local.archivero.ui.GamepadMap
import local.archivero.ui.PadBus
import local.archivero.ui.PadCommand
import local.archivero.ui.PadDirection
import local.archivero.ui.PermissionGate
import local.archivero.ui.StickRepeater
import local.archivero.ui.Tone
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val stick = StickRepeater()
    private val clock = Handler(Looper.getMainLooper())
    private val swallowedUps = HashSet<Int>()
    private val repeat = object : Runnable {
        override fun run() {
            stick.tick(SystemClock.uptimeMillis())?.let(::step)
            if (stick.held != null) clock.postDelayed(this, 30)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 dibuja la app debajo de las barras del sistema; el contenido se aparta solo.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            ArchiveroTheme {
                Surface(Modifier.fillMaxSize(), color = Tone.Background) {
                    Box(Modifier.safeDrawingPadding()) { PermissionGate { BrowserScreen() } }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); (application as ArchiveroApp).uiVisible = true }

    override fun onStop() {
        (application as ArchiveroApp).uiVisible = false
        clock.removeCallbacks(repeat); stick.reset(); swallowedUps.clear()
        super.onStop()
    }

    /** Con el dedo no hay foco visible; al tocar el control se sale de ese modo para que aparezca. */
    private fun leaveTouchMode(): Boolean {
        if (!window.decorView.isInTouchMode) return false
        findViewById<View>(android.R.id.content)?.requestFocusFromTouch()
        return true
    }

    private fun recode(e: KeyEvent, keyCode: Int) =
        KeyEvent(e.downTime, e.eventTime, e.action, keyCode, e.repeatCount, e.metaState, e.deviceId, 0, e.flags, e.source)

    /**
     * Los botones del control llegan aquí antes que a la interfaz. A se vuelve "aceptar" sobre lo
     * enfocado, B es atrás, y el resto se le ofrece a la pantalla. En diálogos y menús (ventanas
     * aparte) esto no corre: ahí Android ya trata A como aceptar y B como atrás.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val command = GamepadMap.commandFor(code)
        val isArrow = code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER
        if (event.action == KeyEvent.ACTION_UP && swallowedUps.remove(code)) return true
        val woke = (command != null || isArrow) && leaveTouchMode()
        // La primera pulsación tras usar el dedo solo despierta el foco: todavía no hay nada
        // enfocado a la vista, y aceptar o moverse a ciegas haría algo que no se pidió.
        if (woke && (command == PadCommand.OPEN || isArrow)) {
            if (event.action == KeyEvent.ACTION_DOWN) swallowedUps.add(code)
            return true
        }
        return when (command) {
            null -> super.dispatchKeyEvent(event)
            PadCommand.OPEN -> super.dispatchKeyEvent(recode(event, KeyEvent.KEYCODE_DPAD_CENTER))
            PadCommand.BACK -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> when (event.action) {
                KeyEvent.ACTION_DOWN ->
                    if (event.repeatCount > 0) code in swallowedUps
                    else if (PadBus.handler?.invoke(command) == true) { swallowedUps.add(code); true }
                    else super.dispatchKeyEvent(event)
                else -> super.dispatchKeyEvent(event)
            }
        }
    }

    /** La palanca izquierda y la cruceta (cuando llega como eje) mueven el foco como flechas. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val x = if (abs(hatX) > 0.5f) hatX else event.getAxisValue(MotionEvent.AXIS_X)
        val y = if (abs(hatY) > 0.5f) hatY else event.getAxisValue(MotionEvent.AXIS_Y)
        stick.update(x, y, SystemClock.uptimeMillis())?.let(::step)
        clock.removeCallbacks(repeat)
        if (stick.held != null) clock.postDelayed(repeat, 30)
        return true
    }

    private fun step(direction: PadDirection) {
        if (leaveTouchMode()) return
        val now = SystemClock.uptimeMillis()
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, direction.keyCode, 0))
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, direction.keyCode, 0))
    }
}
