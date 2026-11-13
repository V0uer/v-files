package local.archivero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Reparto del espacio entre los dos paneles: la fracción que ocupa el primero. */
object Split {
    const val MIN = 0.2f
    const val MAX = 0.8f
    const val STEP = 0.1f
    const val EVEN = 0.5f
    /** Qué tan cerca de la mitad tiene que quedar para que el imán la pegue. */
    const val SNAP = 0.04f

    fun clamp(fraction: Float): Float = fraction.coerceIn(MIN, MAX)

    /** Un paso de agrandar ([grow]) o achicar el panel activo. */
    fun step(fraction: Float, activeIsFirst: Boolean, grow: Boolean): Float {
        val towardFirst = activeIsFirst == grow
        return clamp(fraction + if (towardFirst) STEP else -STEP)
    }

    /** Con el imán prendido, lo que queda cerca de la mitad se pega a la mitad. */
    fun snap(fraction: Float, enabled: Boolean): Float =
        if (enabled && kotlin.math.abs(fraction - EVEN) < SNAP) EVEN else fraction

    /** Arrastrar la división [deltaPx] sobre un largo total de [totalPx]. */
    fun drag(fraction: Float, deltaPx: Float, totalPx: Float): Float =
        if (totalPx <= 0f) fraction else clamp(fraction + deltaPx / totalPx)
}

/**
 * División entre los paneles. Con [adjustable] es una franja que se arrastra (doble toque
 * la vuelve a la mitad) con un candado en medio que prende o apaga el imán ([snap]);
 * si no, una línea fina. [sideBySide]: paneles lado a lado.
 */
@Composable
fun SplitHandle(
    sideBySide: Boolean,
    adjustable: Boolean,
    snap: Boolean,
    onDrag: (deltaPx: Float) -> Unit,
    onDragStopped: () -> Unit,
    onReset: () -> Unit,
    onToggleSnap: () -> Unit,
) {
    val line = if (sideBySide) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth()
    if (!adjustable) {
        Box(line.background(Tone.Line))
        return
    }
    val drag by rememberUpdatedState(onDrag)
    val stopped by rememberUpdatedState(onDragStopped)
    val reset by rememberUpdatedState(onReset)
    val state = rememberDraggableState { drag(it) }
    Box(
        (if (sideBySide) Modifier.width(22.dp).fillMaxHeight() else Modifier.height(22.dp).fillMaxWidth())
            .background(Tone.Background)
            .draggable(state, if (sideBySide) Orientation.Horizontal else Orientation.Vertical, onDragStopped = { stopped() })
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
            .semantics { contentDescription = "Ajustar el tamaño de los paneles" },
        contentAlignment = Alignment.Center,
    ) {
        Box(line.background(Tone.Line))
        val grip: @Composable () -> Unit = {
            Box(
                (if (sideBySide) Modifier.size(5.dp, 28.dp) else Modifier.size(28.dp, 5.dp))
                    .clip(RoundedCornerShape(50)).background(Tone.Muted),
            )
        }
        val lock: @Composable () -> Unit = {
            Box(
                Modifier.size(22.dp).clip(CircleShape).background(if (snap) Tone.Accent else Tone.SurfaceHigh)
                    .clickable(onClickLabel = if (snap) "Apagar el imán a la mitad" else "Prender el imán a la mitad", onClick = onToggleSnap),
                contentAlignment = Alignment.Center,
            ) { MonoIcon(if (snap) Ico.lock else Ico.unlock, Tone.Text, 14.dp) }
        }
        if (sideBySide) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                grip(); Spacer(Modifier.height(8.dp)); lock(); Spacer(Modifier.height(8.dp)); grip()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                grip(); Spacer(Modifier.width(8.dp)); lock(); Spacer(Modifier.width(8.dp)); grip()
            }
        }
    }
}
