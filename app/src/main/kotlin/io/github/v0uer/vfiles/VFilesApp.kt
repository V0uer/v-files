package io.github.v0uer.vfiles

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import io.github.v0uer.vfiles.core.CopyEngine
import io.github.v0uer.vfiles.core.FileOps
import io.github.v0uer.vfiles.core.OpRequest
import io.github.v0uer.vfiles.core.OperationQueue
import io.github.v0uer.vfiles.core.Trash
import io.github.v0uer.vfiles.data.Prefs
import io.github.v0uer.vfiles.data.Volumes
import io.github.v0uer.vfiles.ops.OpsService

class VFilesApp : Application() {
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
        OpsService.ensureChannel(this)
        // Si la app no está a la vista cuando algo termina, se avisa con una notificación.
        appScope.launch { queue.finished.collect { if (!uiVisible) OpsService.notifyFinished(this@VFilesApp, it) } }
    }

    /** La actividad lo pone en true mientras está a la vista. */
    @Volatile var uiVisible = false

    /** Única puerta para encolar operaciones: además arranca el servicio que las mantiene vivas. */
    fun enqueue(request: OpRequest): Long {
        val id = queue.enqueue(request)
        OpsService.start(this)
        return id
    }
}
