package io.github.v0uer.vfiles.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface OpRequest {
    data class Copy(val sources: List<Path>, val destDir: Path) : OpRequest
    data class Move(val sources: List<Path>, val destDir: Path) : OpRequest
    data class ToTrash(val targets: List<Path>) : OpRequest
    data class Delete(val targets: List<Path>) : OpRequest
    /** Extrae de [zip] las rutas internas [inners] ("" = todo) en [destDir]. */
    data class Extract(val zip: Path, val inners: List<String>, val destDir: Path) : OpRequest
    data class Compress(val sources: List<Path>, val destZip: Path) : OpRequest
}

enum class OpStatus { QUEUED, RUNNING, WAITING_CONFLICT, DONE, CANCELLED, FAILED }

data class OpState(
    val id: Long,
    val request: OpRequest,
    val status: OpStatus,
    val progress: Progress?,
    val conflict: Path? = null,
    val failure: OpFailure.Kind? = null,
    val failurePath: Path? = null,
) {
    val isFinished get() = status == OpStatus.DONE || status == OpStatus.CANCELLED || status == OpStatus.FAILED
}

/** Ejecuta operaciones de una en una, en orden de llegada, y publica su estado. */
class OperationQueue(
    private val scope: CoroutineScope,
    private val copier: CopyEngine,
    private val ops: FileOps,
    private val trash: Trash,
    private val progressIntervalMs: Long = 100,
) {
    private val _states = MutableStateFlow<List<OpState>>(emptyList())
    val states: StateFlow<List<OpState>> = _states.asStateFlow()

    private val _finished = MutableSharedFlow<OpState>(extraBufferCapacity = 256)
    val finished: SharedFlow<OpState> = _finished.asSharedFlow()

    private val nextId = AtomicLong(1)
    private val pending = Channel<Long>(Channel.UNLIMITED)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val answers = ConcurrentHashMap<Long, CompletableDeferred<Pair<ConflictChoice, Boolean>>>()

    init {
        scope.launch {
            for (id in pending) {
                val state = _states.value.firstOrNull { it.id == id } ?: continue
                if (state.status != OpStatus.QUEUED) continue
                val job = launch { run(id, state.request) }
                jobs[id] = job
                job.join()
                jobs.remove(id)
                answers.remove(id)
            }
        }
    }

    fun enqueue(request: OpRequest): Long {
        val id = nextId.getAndIncrement()
        _states.update { it + OpState(id, request, OpStatus.QUEUED, null) }
        pending.trySend(id)
        return id
    }

    fun cancel(id: Long) {
        val job = jobs[id]
        if (job != null) { job.cancel(); return }
        var cancelled: OpState? = null
        _states.update { list ->
            list.map { if (it.id == id && it.status == OpStatus.QUEUED) it.copy(status = OpStatus.CANCELLED).also { c -> cancelled = c } else it }
        }
        cancelled?.let { _finished.tryEmit(it) }
    }

    fun resolveConflict(id: Long, choice: ConflictChoice, applyToAll: Boolean) {
        answers.remove(id)?.complete(choice to applyToAll)
    }

    fun clearFinished() = _states.update { list -> list.filterNot { it.isFinished } }

    private fun change(id: Long, f: (OpState) -> OpState) =
        _states.update { list -> list.map { if (it.id == id) f(it) else it } }

    private suspend fun run(id: Long, request: OpRequest) {
        change(id) { it.copy(status = OpStatus.RUNNING) }
        var sticky: ConflictChoice? = null
        val onConflict: suspend (Path) -> ConflictChoice = { existing ->
            sticky ?: run {
                val answer = CompletableDeferred<Pair<ConflictChoice, Boolean>>()
                answers[id] = answer
                change(id) { it.copy(status = OpStatus.WAITING_CONFLICT, conflict = existing) }
                val (choice, all) = try { answer.await() } finally { answers.remove(id) }
                if (all) sticky = choice
                change(id) { it.copy(status = OpStatus.RUNNING, conflict = null) }
                choice
            }
        }
        var last: Progress? = null
        var lastAt = 0L
        val onProgress: (Progress) -> Unit = { p ->
            last = p
            val now = System.currentTimeMillis()
            if (now - lastAt >= progressIntervalMs) {
                lastAt = now
                // No pisar el estado de espera de conflicto con un progreso que llega tarde.
                change(id) { if (it.status == OpStatus.RUNNING) it.copy(progress = p) else it }
            }
        }
        val end: OpState.() -> OpState = try {
            when (request) {
                is OpRequest.Copy -> copier.copy(request.sources, request.destDir, onConflict, onProgress)
                is OpRequest.Move -> ops.move(request.sources, request.destDir, onConflict, onProgress)
                is OpRequest.ToTrash -> {
                    trash.moveToTrash(request.targets)
                    onProgress(Progress(request.targets.size, request.targets.size, 0, 0, ""))
                }
                is OpRequest.Delete -> ops.deletePermanently(request.targets, onProgress)
                is OpRequest.Extract -> {
                    // Extraer un zip "entero" en una carpeta nueva: se crea aquí, con el nombre libre.
                    Files.createDirectories(request.destDir)
                    Zip.extract(request.zip, request.inners, request.destDir, onConflict, onProgress)
                }
                is OpRequest.Compress -> Zip.compress(request.sources, request.destZip, onProgress)
            }
            ({ copy(status = OpStatus.DONE) })
        } catch (e: CancellationException) {
            ({ copy(status = OpStatus.CANCELLED) })
        } catch (e: OpFailure) {
            ({ copy(status = OpStatus.FAILED, failure = e.kind, failurePath = e.path) })
        } catch (e: Exception) {
            ({ copy(status = OpStatus.FAILED, failure = OpFailure.Kind.IO) })
        }
        var final: OpState? = null
        change(id) { it.end().copy(progress = last ?: it.progress, conflict = null).also { s -> final = s } }
        final?.let { _finished.tryEmit(it) }
    }
}
