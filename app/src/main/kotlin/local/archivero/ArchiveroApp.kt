package local.archivero

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import local.archivero.core.CopyEngine
import local.archivero.core.FileOps
import local.archivero.core.OperationQueue
import local.archivero.core.Trash
import local.archivero.data.Prefs
import local.archivero.data.Volumes

class ArchiveroApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: Prefs private set
    lateinit var volumes: Volumes private set
    lateinit var fileOps: FileOps private set
    lateinit var trash: Trash private set
    lateinit var queue: OperationQueue private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        volumes = Volumes(this)
        val copier = CopyEngine()
        fileOps = FileOps(copier) { a, b -> volumes.rootOf(a) == volumes.rootOf(b) }
        trash = Trash({ volumes.rootOf(it) })
        queue = OperationQueue(appScope, copier, fileOps, trash)
    }
}
