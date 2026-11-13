package local.archivero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
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

    fun clamp(fraction: Float): Float = fraction.coerceIn(MIN, MAX)

    /** Un paso de agrandar ([grow]) o achicar el panel activo. */
    fun step(fraction: Float, activeIsFirst: Boolean, grow: Boolean): Float {
        val towardFirst = activeIsFirst == grow
        return clamp(fraction + if (towardFirst) STEP else -STEP)
    }

    /** Arrastrar la división [deltaPx] sobre un largo total de [totalPx]. */
    fun drag(fraction: Float, deltaPx: Float, totalPx: Float): Float =
        if (totalPx <= 0f) fraction else clamp(fraction + deltaPx / totalPx)
}

/**
 * División entre los paneles. Con [adjustable] es una franja que se arrastra (doble toque
 * la vuelve a la mitad); si no, una línea fina. [sideBySide]: paneles lado a lado.
 */
@Composable
fun SplitHandle(
    sideBySide: Boolean,
    adjustable: Boolean,
    onDrag: (deltaPx: Float) -> Unit,
    onDragStopped: () -> Unit,
    onReset: () -> Unit,
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
        (if (sideBySide) Modifier.width(14.dp).fillMaxHeight() else Modifier.height(14.dp).fillMaxWidth())
            .background(Tone.Background)
            .draggable(state, if (sideBySide) Orientation.Horizontal else Orientation.Vertical, onDragStopped = { stopped() })
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { reset() }) }
            .semantics { contentDescription = "Ajustar el tamaño de los paneles" },
        contentAlignment = Alignment.Center,
    ) {
        Box(line.background(Tone.Line))
        Box(
            (if (sideBySide) Modifier.size(6.dp, 44.dp) else Modifier.size(44.dp, 6.dp))
                .clip(RoundedCornerShape(50)).background(Tone.Muted),
        )
    }
}
