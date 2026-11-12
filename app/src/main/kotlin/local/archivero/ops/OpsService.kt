package local.archivero.ops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.archivero.ArchiveroApp
import local.archivero.MainActivity
import local.archivero.R
import local.archivero.core.OpRequest
import local.archivero.core.OpState
import local.archivero.core.OpStatus
import local.archivero.core.Progress
import java.nio.file.Path

/** Textos de una operación, compartidos por la notificación y la tira de progreso. */
object OpText {
    private fun paths(r: OpRequest): List<Path> = when (r) {
        is OpRequest.Copy -> r.sources
        is OpRequest.Move -> r.sources
        is OpRequest.ToTrash -> r.targets
        is OpRequest.Delete -> r.targets
    }

    fun verb(r: OpRequest): String = when (r) {
        is OpRequest.Copy -> "Copiando"
        is OpRequest.Move -> "Moviendo"
        is OpRequest.ToTrash -> "Enviando a la papelera"
        is OpRequest.Delete -> "Borrando"
    }

    fun infinitive(r: OpRequest): String = when (r) {
        is OpRequest.Copy -> "copiar"
        is OpRequest.Move -> "mover"
        is OpRequest.ToTrash, is OpRequest.Delete -> "borrar"
    }

    fun done(r: OpRequest): String = when (r) {
        is OpRequest.Copy -> "Copia terminada"
        is OpRequest.Move -> "Movimiento terminado"
        is OpRequest.ToTrash, is OpRequest.Delete -> "Borrado terminado"
    }

    fun subject(r: OpRequest): String {
        val p = paths(r)
        return if (p.size == 1) p[0].fileName?.toString().orEmpty() else "${p.size} elementos"
    }

    /** Avance de 0 a 1 por bytes, o por archivos si no hay bytes que contar. */
    fun fraction(p: Progress): Float = when {
        p.bytesTotal > 0 -> (p.bytesDone.toDouble() / p.bytesTotal).toFloat()
        p.filesTotal > 0 -> p.filesDone.toFloat() / p.filesTotal
        else -> 0f
    }.coerceIn(0f, 1f)
}

/**
 * Servicio en primer plano que mantiene viva la cola mientras haya operaciones.
 * No hace el trabajo (eso es de OperationQueue): solo sostiene el proceso y muestra el avance.
 */
class OpsService : Service() {
    companion object {
        private const val CHANNEL = "operaciones"
        private const val ONGOING_ID = 1
        private const val RESULT_ID = 2
        private const val ACTION_CANCEL = "local.archivero.CANCELAR"
        private const val EXTRA_ID = "id"

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Operaciones de archivos", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun start(context: Context) {
            // Si el sistema no deja arrancarlo, la operación corre igual mientras la app siga abierta.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, OpsService::class.java)) }
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Aviso de que una operación terminó mientras la app no estaba a la vista. */
        fun notifyFinished(context: Context, state: OpState) {
            val text = when (state.status) {
                OpStatus.DONE -> OpText.done(state.request)
                OpStatus.FAILED -> "No se pudo ${OpText.infinitive(state.request)}"
                else -> return
            }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_folder)
                .setContentTitle(text)
                .setContentText(OpText.subject(state.request))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
            runCatching { context.getSystemService(NotificationManager::class.java).notify(RESULT_ID, n) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val queue = (application as ArchiveroApp).queue
        if (intent?.action == ACTION_CANCEL) queue.cancel(intent.getLongExtra(EXTRA_ID, -1))
        runCatching {
            startForeground(ONGOING_ID, build(queue.states.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        if (!watching) {
            watching = true
            scope.launch {
                val manager = getSystemService(NotificationManager::class.java)
                while (queue.states.value.any { !it.isFinished }) {
                    runCatching { manager.notify(ONGOING_ID, build(queue.states.value)) }
                    delay(500)
                }
                watching = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun build(states: List<OpState>): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_folder)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp(this))
        val op = states.firstOrNull { it.status == OpStatus.RUNNING || it.status == OpStatus.WAITING_CONFLICT }
            ?: states.firstOrNull { !it.isFinished }
            ?: return b.setContentTitle("Archivero").setContentText("Terminando…").build()
        val queued = states.count { it.status == OpStatus.QUEUED && it.id != op.id }
        val tail = if (queued > 0) " · $queued en cola" else ""
        val p = op.progress
        b.setContentTitle("${OpText.verb(op.request)} ${OpText.subject(op.request)}")
        when {
            op.status == OpStatus.WAITING_CONFLICT ->
                b.setContentText("Esperando tu respuesta: ya existe \"${op.conflict?.fileName}\"")
            p == null -> b.setContentText("Preparando…$tail").setProgress(0, 0, true)
            else -> {
                val percent = (OpText.fraction(p) * 100).toInt()
                b.setContentText("$percent% · ${p.filesDone} de ${p.filesTotal}$tail").setProgress(100, percent, false)
            }
        }
        val cancel = PendingIntent.getService(
            this, op.id.toInt(),
            Intent(this, OpsService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, op.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return b.addAction(0, "Cancelar", cancel).build()
    }
}
