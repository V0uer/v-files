package local.archivero.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

data class Volume(
    val root: Path,
    val label: String,
    val isRemovable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

class Volumes(private val context: Context) {
    private val _volumes = MutableStateFlow<List<Volume>>(emptyList())
    val volumes: StateFlow<List<Volume>> = _volumes.asStateFlow()

    private val internalRoot: Path = Environment.getExternalStorageDirectory().toPath()

    init {
        refresh()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED); addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED); addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL); addDataScheme("file")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = refresh()
        }, filter)
    }

    fun refresh() {
        val sm = context.getSystemService(StorageManager::class.java)
        val list = sm.storageVolumes.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            if (v.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
            Volume(
                root = dir.toPath(),
                label = if (v.isPrimary) "Interno" else (v.getDescription(context) ?: "Tarjeta SD"),
                isRemovable = v.isRemovable,
                freeBytes = dir.usableSpace,
                totalBytes = dir.totalSpace,
            )
        }
        _volumes.value = list.ifEmpty {
            val f = internalRoot.toFile()
            listOf(Volume(internalRoot, "Interno", false, f.usableSpace, f.totalSpace))
        }
    }

    /** Raíz del volumen que contiene [path]; si ninguno, el interno. */
    fun rootOf(path: Path): Path =
        _volumes.value.map { it.root }.filter { path.startsWith(it) }.maxByOrNull { it.nameCount } ?: internalRoot

    fun volumeOf(path: Path): Volume? = _volumes.value.filter { path.startsWith(it.root) }.maxByOrNull { it.root.nameCount }
}
