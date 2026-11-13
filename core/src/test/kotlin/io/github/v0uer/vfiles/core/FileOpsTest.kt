package io.github.v0uer.vfiles.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileOpsTest {
    @TempDir lateinit var tmp: Path
    private val src get() = tmp.resolve("src").also { Files.createDirectories(it) }
    private val dst get() = tmp.resolve("dst").also { Files.createDirectories(it) }
    private val never: suspend (Path) -> ConflictChoice = { fail("no debía haber conflicto") }
    private fun names(d: Path) = Files.list(d).use { s -> s.map { it.fileName.toString() }.sorted().collect(java.util.stream.Collectors.toList()) }
    private fun same() = FileOps(CopyEngine()) { _, _ -> true }
    private fun cross() = FileOps(CopyEngine()) { _, _ -> false }
    private fun allTmp(d: Path) = Files.walk(d).use { s -> s.filter { it.fileName.toString().endsWith(".vfiles-tmp") }.count() }

    @Test fun mover_mismo_volumen_es_renombrado() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "hola".toByteArray())
        val made = same().move(listOf(f), dst, never) {}
        assertEquals(listOf(dst.resolve("a.txt")), made)
        assertFalse(Files.exists(f)); assertEquals("hola", Files.readString(dst.resolve("a.txt")))
    }

    @Test fun mover_mismo_volumen_con_conflicto_KEEP_BOTH() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "nuevo".toByteArray())
        Files.write(dst.resolve("a.txt"), "viejo".toByteArray())
        same().move(listOf(f), dst, { ConflictChoice.KEEP_BOTH }) {}
        assertEquals(listOf("a (2).txt", "a.txt"), names(dst)); assertFalse(Files.exists(f))
    }

    @Test fun mover_mismo_volumen_con_conflicto_SKIP_deja_el_origen() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "nuevo".toByteArray())
        Files.write(dst.resolve("a.txt"), "viejo".toByteArray())
        same().move(listOf(f), dst, { ConflictChoice.SKIP }) {}
        assertTrue(Files.exists(f)); assertEquals("viejo", Files.readString(dst.resolve("a.txt")))
    }

    @Test fun mover_a_la_misma_carpeta_no_hace_nada() = runBlocking {
        val f = Files.write(src.resolve("a.txt"), "x".toByteArray())
        same().move(listOf(f), src, never) {}
        assertEquals(listOf("a.txt"), names(src))
    }

    @Test fun mover_entre_volumenes_copia_y_borra() = runBlocking {
        val root = Files.createDirectories(src.resolve("c").resolve("sub")).parent
        Files.write(root.resolve("a.txt"), "a".toByteArray())
        Files.write(root.resolve("sub").resolve("b.txt"), "b".toByteArray())
        cross().move(listOf(root), dst, never) {}
        assertFalse(Files.exists(root))
        assertEquals("b", Files.readString(dst.resolve("c").resolve("sub").resolve("b.txt")))
    }

    @Test fun cancelar_mover_entre_volumenes_no_borra_origenes_sin_copiar() {
        val a = Files.write(src.resolve("a.bin"), ByteArray(1 shl 20))
        val b = Files.write(src.resolve("b.bin"), ByteArray(4 shl 20))
        val c = Files.write(src.resolve("c.bin"), ByteArray(1 shl 20))
        val d = dst
        assertThrows(CancellationException::class.java) {
            runBlocking { coroutineScope { cross().move(listOf(a, b, c), d, never) { if (it.currentName == "b.bin") cancel() } } }
        }
        assertEquals(listOf("a.bin"), names(d))
        assertFalse(Files.exists(a))
        assertEquals((4 shl 20).toLong(), Files.size(b)); assertEquals((1 shl 20).toLong(), Files.size(c))
        assertEquals(0, allTmp(tmp))
    }

    @Test fun mover_entre_volumenes_con_omitidos_conserva_en_origen_lo_omitido() = runBlocking {
        val root = Files.createDirectories(src.resolve("c"))
        Files.write(root.resolve("igual.txt"), "nuevo".toByteArray())
        Files.write(root.resolve("otro.txt"), "x".toByteArray())
        Files.createDirectories(dst.resolve("c"))
        Files.write(dst.resolve("c").resolve("igual.txt"), "viejo".toByteArray())
        cross().move(listOf(root), dst, { if (it.fileName.toString() == "c") ConflictChoice.REPLACE else ConflictChoice.SKIP }) {}
        assertEquals(listOf("igual.txt"), names(root))
        assertEquals("nuevo", Files.readString(root.resolve("igual.txt")))
        assertEquals("viejo", Files.readString(dst.resolve("c").resolve("igual.txt")))
        assertEquals("x", Files.readString(dst.resolve("c").resolve("otro.txt")))
    }

    @Test fun mover_entre_volumenes_omitiendo_la_carpeta_no_toca_sus_carpetas_vacias() = runBlocking {
        val root = Files.createDirectories(src.resolve("c").resolve("vacia")).parent
        Files.write(root.resolve("a.txt"), "a".toByteArray())
        Files.createDirectories(dst.resolve("c"))
        cross().move(listOf(root), dst, { ConflictChoice.SKIP }) {}
        assertTrue(Files.isDirectory(root.resolve("vacia")))
        assertEquals(listOf("a.txt", "vacia"), names(root))
    }

    @Test fun mover_entre_volumenes_con_subcarpeta_omitida_conserva_sus_carpetas_vacias() = runBlocking {
        val sub = Files.createDirectories(src.resolve("c").resolve("sub").resolve("vacia")).parent
        Files.write(sub.resolve("x.txt"), "x".toByteArray())
        Files.createDirectories(dst.resolve("c").resolve("sub"))
        cross().move(listOf(src.resolve("c")), dst, { if (it.fileName.toString() == "c") ConflictChoice.REPLACE else ConflictChoice.SKIP }) {}
        assertTrue(Files.isDirectory(sub.resolve("vacia")))
        assertEquals("x", Files.readString(sub.resolve("x.txt")))
    }

    @Test fun mover_entre_volumenes_no_deja_carpetas_vacias_en_el_origen() = runBlocking {
        val root = Files.createDirectories(src.resolve("c").resolve("vacia").resolve("mas")).parent.parent
        Files.write(root.resolve("a.txt"), "a".toByteArray())
        cross().move(listOf(root), dst, never) {}
        assertFalse(Files.exists(root))
        assertTrue(Files.isDirectory(dst.resolve("c").resolve("vacia").resolve("mas")))
    }

    @Test fun mover_mismo_volumen_fusionando_carpeta_conserva_lo_omitido() = runBlocking {
        val root = Files.createDirectories(src.resolve("c"))
        Files.write(root.resolve("igual.txt"), "nuevo".toByteArray())
        Files.createDirectories(dst.resolve("c"))
        Files.write(dst.resolve("c").resolve("igual.txt"), "viejo".toByteArray())
        same().move(listOf(root), dst, { if (it.fileName.toString() == "c") ConflictChoice.REPLACE else ConflictChoice.SKIP }) {}
        assertEquals("nuevo", Files.readString(root.resolve("igual.txt")))
        assertEquals("viejo", Files.readString(dst.resolve("c").resolve("igual.txt")))
    }

    @Test fun mover_carpeta_dentro_de_si_misma_falla_INTO_ITSELF() {
        val root = src.resolve("c"); val sub = Files.createDirectories(root.resolve("sub"))
        val e = assertThrows(OpFailure::class.java) { runBlocking { same().move(listOf(root), sub, never) {} } }
        assertEquals(OpFailure.Kind.INTO_ITSELF, e.kind); assertTrue(Files.isDirectory(sub))
    }

    @Test fun renombrar_solo_mayusculas_funciona() {
        val f = Files.write(src.resolve("foto.jpg"), "x".toByteArray())
        val r = same().rename(f, "Foto.JPG")
        assertEquals("Foto.JPG", r.fileName.toString())
        assertEquals(listOf("Foto.JPG"), names(src)); assertEquals("x", Files.readString(r))
    }

    @Test fun renombrar_a_nombre_existente_falla_y_no_pisa() {
        val f = Files.write(src.resolve("a.txt"), "a".toByteArray())
        Files.write(src.resolve("b.txt"), "b".toByteArray())
        val e = assertThrows(OpFailure::class.java) { same().rename(f, "B.txt") }
        assertEquals(OpFailure.Kind.ALREADY_EXISTS, e.kind)
        assertEquals("a", Files.readString(f)); assertEquals("b", Files.readString(src.resolve("b.txt")))
    }

    @Test fun renombrar_nombre_invalido_lanza_IllegalArgumentException() {
        val f = Files.write(src.resolve("a.txt"), "a".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { same().rename(f, "x/y") }
    }

    @Test fun crear_carpeta_y_existente_falla() {
        assertTrue(Files.isDirectory(same().createFolder(src, "Nueva")))
        val e = assertThrows(OpFailure::class.java) { same().createFolder(src, "nueva") }
        assertEquals(OpFailure.Kind.ALREADY_EXISTS, e.kind)
        assertThrows(IllegalArgumentException::class.java) { same().createFolder(src, "") }
    }

    @Test fun borrar_definitivo_carpeta_con_contenido() = runBlocking {
        val root = Files.createDirectories(src.resolve("c").resolve("sub")).parent
        Files.write(root.resolve("sub").resolve("b.txt"), "b".toByteArray())
        val keep = Files.write(src.resolve("queda.txt"), "q".toByteArray())
        same().deletePermanently(listOf(root)) {}
        assertFalse(Files.exists(root)); assertTrue(Files.exists(keep))
    }

    @Test fun propiedades_suma_recursiva() = runBlocking {
        val root = Files.createDirectories(src.resolve("c").resolve("sub")).parent
        Files.write(root.resolve("a"), ByteArray(100)); Files.write(root.resolve("sub").resolve("b"), ByteArray(250))
        val p = same().properties(root)
        assertEquals(350L, p.totalBytes); assertEquals(2, p.fileCount); assertEquals(1, p.folderCount)
    }
}
