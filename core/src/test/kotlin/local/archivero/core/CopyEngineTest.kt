package local.archivero.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class CopyEngineTest {
    @TempDir lateinit var tmp: Path
    private val src get() = tmp.resolve("src").also { Files.createDirectories(it) }
    private val dst get() = tmp.resolve("dst").also { Files.createDirectories(it) }
    private val never: suspend (Path) -> ConflictChoice = { fail("no debía haber conflicto") }
    private fun leftovers(d: Path) = Files.walk(d).use { s -> s.filter { it.fileName.toString().endsWith(".archivero-tmp") }.count() }
    private fun names(d: Path) = Files.list(d).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test fun copia_archivo_conserva_contenido_y_fecha() = runBlocking {
        val f = Files.write(src.resolve("a.bin"), ByteArray(3_000_000) { (it % 251).toByte() })
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_600_000_000_000))
        val made = CopyEngine().copy(listOf(f), dst, never) {}
        assertEquals(listOf(dst.resolve("a.bin")), made)
        assertArrayEquals(Files.readAllBytes(f), Files.readAllBytes(dst.resolve("a.bin")))
        assertEquals(1_600_000_000_000, Files.getLastModifiedTime(dst.resolve("a.bin")).toMillis())
        assertEquals(0, leftovers(dst))
    }

    @Test fun copia_carpeta_recursiva() = runBlocking {
        val root = src.resolve("r")
        Files.createDirectories(root.resolve("n1").resolve("n2"))
        Files.write(root.resolve("uno.txt"), "1".toByteArray())
        Files.write(root.resolve("n1").resolve("n2").resolve("tres.txt"), "333".toByteArray())
        Files.createDirectory(root.resolve("vacia"))
        CopyEngine().copy(listOf(root), dst, never) {}
        assertEquals("1", Files.readString(dst.resolve("r").resolve("uno.txt")))
        assertEquals("333", Files.readString(dst.resolve("r").resolve("n1").resolve("n2").resolve("tres.txt")))
        assertTrue(Files.isDirectory(dst.resolve("r").resolve("vacia")))
    }

    @Test fun conflicto_SKIP_no_toca_destino() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "nuevo".toByteArray())
        Files.write(dst.resolve("a.txt"), "viejo".toByteArray())
        val made = CopyEngine().copy(listOf(f), dst, { ConflictChoice.SKIP }) {}
        assertEquals(emptyList<Path>(), made)
        assertEquals("viejo", Files.readString(dst.resolve("a.txt")))
    }

    @Test fun conflicto_REPLACE_sobrescribe() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "nuevo".toByteArray())
        Files.write(dst.resolve("a.txt"), "viejo".toByteArray())
        CopyEngine().copy(listOf(f), dst, { ConflictChoice.REPLACE }) {}
        assertEquals("nuevo", Files.readString(dst.resolve("a.txt")))
        assertEquals(listOf("a.txt"), names(dst))
    }

    @Test fun conflicto_KEEP_BOTH_crea_nombre_2() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "nuevo".toByteArray())
        Files.write(dst.resolve("a.txt"), "viejo".toByteArray())
        val made = CopyEngine().copy(listOf(f), dst, { ConflictChoice.KEEP_BOTH }) {}
        assertEquals(listOf(dst.resolve("a (2).txt")), made)
        assertEquals("viejo", Files.readString(dst.resolve("a.txt")))
        assertEquals("nuevo", Files.readString(dst.resolve("a (2).txt")))
    }

    @Test fun conflicto_se_detecta_sin_distinguir_mayusculas() = runBlocking {
        val f = Files.write(src.resolve("foto.jpg"), "n".toByteArray())
        Files.write(dst.resolve("Foto.JPG"), "v".toByteArray())
        var asked = 0
        CopyEngine().copy(listOf(f), dst, { asked++; ConflictChoice.SKIP }) {}
        assertEquals(1, asked)
    }

    @Test fun conflicto_de_carpeta_REPLACE_fusiona_y_pregunta_por_cada_archivo() = runBlocking {
        val root = Files.createDirectories(src.resolve("c"))
        Files.write(root.resolve("igual.txt"), "nuevo".toByteArray())
        Files.write(root.resolve("solo_origen.txt"), "x".toByteArray())
        val dc = Files.createDirectories(dst.resolve("c"))
        Files.write(dc.resolve("igual.txt"), "viejo".toByteArray())
        Files.write(dc.resolve("solo_destino.txt"), "y".toByteArray())
        val asked = ArrayList<String>()
        CopyEngine().copy(listOf(root), dst, { asked.add(it.fileName.toString()); ConflictChoice.REPLACE }) {}
        assertEquals(listOf("c", "igual.txt"), asked)
        assertEquals(listOf("igual.txt", "solo_destino.txt", "solo_origen.txt"), names(dc))
        assertEquals("nuevo", Files.readString(dc.resolve("igual.txt")))
    }

    @Test fun copiar_carpeta_dentro_de_si_misma_falla_INTO_ITSELF_sin_crear_nada() {
        val root = src.resolve("c")
        val sub = Files.createDirectories(root.resolve("sub"))
        Files.write(root.resolve("a.txt"), "a".toByteArray())
        val e = assertThrows(OpFailure::class.java) { runBlocking { CopyEngine().copy(listOf(root), sub, never) {} } }
        assertEquals(OpFailure.Kind.INTO_ITSELF, e.kind)
        assertEquals(emptyList<String>(), names(sub))
    }

    @Test fun cancelar_a_media_copia_no_deja_temporal_ni_archivo_final() {
        val f = Files.write(src.resolve("grande.bin"), ByteArray(64 shl 20))
        val d = dst
        assertThrows(CancellationException::class.java) {
            runBlocking { coroutineScope { CopyEngine().copy(listOf(f), d, never) { cancel() } } }
        }
        assertEquals(emptyList<String>(), names(d))
    }

    @Test fun fallo_de_escritura_borra_temporal_y_origen_intacto() {
        val f = Files.write(src.resolve("a.bin"), ByteArray(5 shl 20))
        val failing: (Path) -> OutputStream = { p ->
            val real = Files.newOutputStream(p)
            object : OutputStream() {
                var n = 0L
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (n + len > (2 shl 20)) throw IOException("write failed: ENOSPC (No space left on device)")
                    real.write(b, off, len); n += len
                }
                override fun close() = real.close()
            }
        }
        val d = dst
        val e = assertThrows(OpFailure::class.java) { runBlocking { CopyEngine(openOutput = failing).copy(listOf(f), d, never) {} } }
        assertEquals(OpFailure.Kind.NO_SPACE, e.kind)
        assertEquals(emptyList<String>(), names(d))
        assertEquals((5 shl 20).toLong(), Files.size(f))
    }

    @Test fun archivo_demasiado_grande_da_FILE_TOO_LARGE() {
        val f = Files.write(src.resolve("a.bin"), ByteArray(10))
        val failing: (Path) -> OutputStream = {
            object : OutputStream() {
                override fun write(b: Int) = throw IOException("write failed: EFBIG (File too large)")
            }
        }
        val d = dst
        val e = assertThrows(OpFailure::class.java) { runBlocking { CopyEngine(openOutput = failing).copy(listOf(f), d, never) {} } }
        assertEquals(OpFailure.Kind.FILE_TOO_LARGE, e.kind)
    }

    @Test fun origen_desaparecido_da_SOURCE_GONE() {
        val e = assertThrows(OpFailure::class.java) { runBlocking { CopyEngine().copy(listOf(src.resolve("no-existe")), dst, never) {} } }
        assertEquals(OpFailure.Kind.SOURCE_GONE, e.kind)
    }

    @Test fun progreso_termina_en_totales() = runBlocking {
        val a = Files.write(src.resolve("a"), ByteArray(1000))
        val b = Files.write(src.resolve("b"), ByteArray(2500))
        var last: Progress? = null
        CopyEngine().copy(listOf(a, b), dst, never) { last = it }
        assertEquals(2, last!!.filesTotal); assertEquals(2, last!!.filesDone)
        assertEquals(3500L, last!!.bytesTotal); assertEquals(3500L, last!!.bytesDone)
    }
}
