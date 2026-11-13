package io.github.v0uer.vfiles.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

class OperationQueueTest {
    @TempDir lateinit var tmp: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val src get() = tmp.resolve("src").also { Files.createDirectories(it) }
    private val dst get() = tmp.resolve("dst").also { Files.createDirectories(it) }
    private fun queue(): OperationQueue {
        val copier = CopyEngine()
        return OperationQueue(scope, copier, FileOps(copier), Trash({ tmp }))
    }
    @AfterEach fun close() = scope.cancel()

    private suspend fun OperationQueue.await(id: Long, vararg status: OpStatus): OpState = withTimeout(10_000) {
        states.first { l -> l.any { it.id == id && it.status in status } }.first { it.id == id }
    }

    @Test fun copia_pasa_por_QUEUED_RUNNING_DONE() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "a".toByteArray())
        val q = queue()
        val seen = Collections.synchronizedList(ArrayList<OpStatus>())
        val watcher = scope.launch(Dispatchers.Unconfined) { q.states.collect { l -> l.firstOrNull()?.let { if (seen.lastOrNull() != it.status) seen.add(it.status) } } }
        val id = q.enqueue(OpRequest.Copy(listOf(f), dst))
        val done = q.await(id, OpStatus.DONE)
        watcher.cancel()
        assertEquals("a", Files.readString(dst.resolve("a.txt")))
        assertEquals(OpStatus.QUEUED, seen.first()); assertEquals(OpStatus.DONE, seen.last())
        assertTrue(OpStatus.RUNNING in seen)
        assertEquals(1, done.progress!!.filesDone)
    }

    @Test fun dos_operaciones_corren_en_orden() = runBlocking {
        val a = Files.write(src.resolve("a.bin"), ByteArray(8 shl 20))
        val b = Files.write(src.resolve("b.txt"), "b".toByteArray())
        val q = queue()
        val id1 = q.enqueue(OpRequest.Copy(listOf(a), dst))
        val id2 = q.enqueue(OpRequest.Copy(listOf(b), dst))
        val order = ArrayList<Long>()
        val job = scope.launch(Dispatchers.Unconfined) { q.finished.collect { synchronized(order) { order.add(it.id) } } }
        q.await(id2, OpStatus.DONE)
        delay(50); job.cancel()
        assertEquals(OpStatus.DONE, q.states.value.first { it.id == id1 }.status)
        assertTrue(synchronized(order) { order.indexOf(id1) < order.indexOf(id2) || order == listOf(id2) && false })
    }

    @Test fun conflicto_pone_WAITING_CONFLICT_y_resolver_continua() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "nuevo".toByteArray())
        Files.write(dst.resolve("a.txt"), "viejo".toByteArray())
        val q = queue()
        val id = q.enqueue(OpRequest.Copy(listOf(f), dst))
        val waiting = q.await(id, OpStatus.WAITING_CONFLICT)
        assertEquals(dst.resolve("a.txt"), waiting.conflict)
        q.resolveConflict(id, ConflictChoice.REPLACE, applyToAll = false)
        val done = q.await(id, OpStatus.DONE)
        assertNull(done.conflict)
        assertEquals("nuevo", Files.readString(dst.resolve("a.txt")))
    }

    @Test fun applyToAll_no_vuelve_a_preguntar() = runBlocking {
        val files = listOf("a", "b", "c").map { Files.write(src.resolve(it), "nuevo".toByteArray()) }
        listOf("a", "b", "c").forEach { Files.write(dst.resolve(it), "viejo".toByteArray()) }
        val q = queue()
        var waits = 0
        val job = scope.launch(Dispatchers.Unconfined) {
            var prev: OpStatus? = null
            q.states.collect { l -> val s = l.firstOrNull()?.status; if (s == OpStatus.WAITING_CONFLICT && prev != s) waits++; prev = s }
        }
        val id = q.enqueue(OpRequest.Copy(files, dst))
        q.await(id, OpStatus.WAITING_CONFLICT)
        q.resolveConflict(id, ConflictChoice.REPLACE, applyToAll = true)
        q.await(id, OpStatus.DONE); job.cancel()
        assertEquals(1, waits)
        listOf("a", "b", "c").forEach { assertEquals("nuevo", Files.readString(dst.resolve(it))) }
    }

    @Test fun sin_applyToAll_pregunta_por_cada_uno() = runBlocking {
        val files = listOf("a", "b").map { Files.write(src.resolve(it), "nuevo".toByteArray()) }
        listOf("a", "b").forEach { Files.write(dst.resolve(it), "viejo".toByteArray()) }
        val q = queue()
        val id = q.enqueue(OpRequest.Copy(files, dst))
        assertEquals(dst.resolve("a"), q.await(id, OpStatus.WAITING_CONFLICT).conflict)
        q.resolveConflict(id, ConflictChoice.SKIP, applyToAll = false)
        withTimeout(10_000) { q.states.first { l -> l.first().let { it.status == OpStatus.WAITING_CONFLICT && it.conflict == dst.resolve("b") } } }
        q.resolveConflict(id, ConflictChoice.REPLACE, applyToAll = false)
        q.await(id, OpStatus.DONE)
        assertEquals("viejo", Files.readString(dst.resolve("a"))); assertEquals("nuevo", Files.readString(dst.resolve("b")))
    }

    @Test fun cancelar_en_espera_de_conflicto_termina_CANCELLED() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "nuevo".toByteArray())
        Files.write(dst.resolve("a.txt"), "viejo".toByteArray())
        val q = queue()
        val id = q.enqueue(OpRequest.Copy(listOf(f), dst))
        q.await(id, OpStatus.WAITING_CONFLICT)
        q.cancel(id)
        q.await(id, OpStatus.CANCELLED)
        assertEquals("viejo", Files.readString(dst.resolve("a.txt")))
    }

    @Test fun cancelar_una_en_cola_no_la_ejecuta() = runBlocking {
        val a = Files.write(src.resolve("a.txt"), "n".toByteArray())
        Files.write(dst.resolve("a.txt"), "v".toByteArray())
        val b = Files.write(src.resolve("b.txt"), "b".toByteArray())
        val q = queue()
        val id1 = q.enqueue(OpRequest.Copy(listOf(a), dst))
        val id2 = q.enqueue(OpRequest.Copy(listOf(b), dst))
        q.await(id1, OpStatus.WAITING_CONFLICT)
        q.cancel(id2)
        q.resolveConflict(id1, ConflictChoice.SKIP, false)
        q.await(id1, OpStatus.DONE); q.await(id2, OpStatus.CANCELLED)
        delay(100)
        assertFalse(Files.exists(dst.resolve("b.txt")))
    }

    @Test fun fallo_deja_FAILED_con_kind_y_la_siguiente_operacion_corre() = runBlocking {
        val b = Files.write(src.resolve("b.txt"), "b".toByteArray())
        val q = queue()
        val id1 = q.enqueue(OpRequest.Copy(listOf(src.resolve("no-existe")), dst))
        val id2 = q.enqueue(OpRequest.Copy(listOf(b), dst))
        val failed = q.await(id1, OpStatus.FAILED)
        assertEquals(OpFailure.Kind.SOURCE_GONE, failed.failure); assertEquals(src.resolve("no-existe"), failed.failurePath)
        q.await(id2, OpStatus.DONE)
    }

    @Test fun finished_emite_una_vez_por_operacion() = runBlocking {
        val q = queue()
        val got = Collections.synchronizedList(ArrayList<Long>())
        val job = scope.launch(Dispatchers.Unconfined) { q.finished.collect { got.add(it.id) } }
        val ids = (1..3).map { q.enqueue(OpRequest.Copy(listOf(Files.write(src.resolve("f$it"), ByteArray(1))), dst)) }
        q.await(ids.last(), OpStatus.DONE); delay(100); job.cancel()
        assertEquals(ids, got.toList())
    }

    @Test fun mover_a_papelera_borrar_y_clearFinished() = runBlocking {
        val a = Files.write(src.resolve("a"), ByteArray(1)); val b = Files.write(src.resolve("b"), ByteArray(1))
        val c = Files.write(src.resolve("c"), ByteArray(1))
        val q = queue()
        val t = q.enqueue(OpRequest.ToTrash(listOf(a))); val d = q.enqueue(OpRequest.Delete(listOf(b)))
        val m = q.enqueue(OpRequest.Move(listOf(c), dst))
        q.await(t, OpStatus.DONE); q.await(d, OpStatus.DONE); q.await(m, OpStatus.DONE)
        assertFalse(Files.exists(a)); assertFalse(Files.exists(b)); assertTrue(Files.exists(dst.resolve("c")))
        assertEquals(1, Trash({ tmp }).list(tmp).size)
        q.clearFinished()
        assertEquals(emptyList<OpState>(), q.states.value)
    }
}
