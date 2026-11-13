package local.archivero.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.archivero.R
import local.archivero.core.EntryKind
import local.archivero.data.QuickAccess

/**
 * Iconos de Fluent UI System Icons (MIT, Microsoft), los de Windows 11. Los "mono" se tiñen;
 * los de color (carpeta, tipos de archivo, estrella, teléfono) se dibujan tal cual.
 * Se generan con tools/fluent-icons/build_icons.py.
 */
object Ico {
    val menu = R.drawable.fl_menu
    val back = R.drawable.fl_back
    val up = R.drawable.fl_up
    val search = R.drawable.fl_search
    val close = R.drawable.fl_close
    val more = R.drawable.fl_more
    val splitV = R.drawable.fl_split_v
    val check = R.drawable.fl_check
    val drive = R.drawable.fl_drive
    val star = R.drawable.fl_star
    val phone = R.drawable.fl_phone
    val folder = R.drawable.fl_folder
    val document = R.drawable.fl_document
    val pin = R.drawable.fl_pin
    val lock = R.drawable.fl_lock
    val unlock = R.drawable.fl_unlock

    /** Icono de una carpeta de Acceso rápido: las típicas llevan el suyo, como en Windows. */
    fun forQuickAccess(known: QuickAccess.Known?): Int = when (known) {
        QuickAccess.Known.DESKTOP -> R.drawable.fl_desktop
        QuickAccess.Known.DOWNLOADS -> R.drawable.fl_download
        QuickAccess.Known.DOCUMENTS -> R.drawable.fl_document
        QuickAccess.Known.PICTURES -> R.drawable.fl_image
        QuickAccess.Known.VIDEOS -> R.drawable.fl_video
        QuickAccess.Known.MUSIC -> R.drawable.fl_music
        null -> R.drawable.fl_folder
    }

    fun forKind(kind: EntryKind): Int = when (kind) {
        EntryKind.FOLDER -> R.drawable.fl_folder
        EntryKind.IMAGE -> R.drawable.fl_image
        EntryKind.VIDEO -> R.drawable.fl_video
        EntryKind.AUDIO -> R.drawable.fl_music
        EntryKind.ARCHIVE -> R.drawable.fl_zip
        EntryKind.TEXT -> R.drawable.fl_text
        EntryKind.APK -> R.drawable.fl_app
        EntryKind.OTHER -> R.drawable.fl_document
    }
}

/** Icono de un color (el del texto, o [tint]). */
@Composable
fun MonoIcon(@DrawableRes res: Int, tint: Color = Tone.Text, size: Dp = 22.dp, modifier: Modifier = Modifier) =
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))

/** Icono con sus propios colores. */
@Composable
fun ColorIcon(@DrawableRes res: Int, size: Dp = 24.dp, modifier: Modifier = Modifier) =
    Image(painterResource(res), contentDescription = null, modifier = modifier.size(size))
