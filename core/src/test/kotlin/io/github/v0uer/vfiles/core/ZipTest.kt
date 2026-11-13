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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipTest {
    @TempDir lateinit var tmp: Path
    private val never: suspend (Path) -> ConflictChoice = { fail("no debía haber conflicto") }

    /** Arma un zip con las entradas dadas (nombre → contenido; contenido null = carpeta). */
    private fun zipOf(name: String, vararg entries: Pair<String, String?>): Path {
        val z = tmp.resolve(name)
        ZipOutputStream(Files.newOutputStream(z)).use { out ->
            for ((n, text) in entries) {
                out.putNextEntry(ZipEntry(n))
                if (text != null) out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return z
    }

    private fun sample() = zipOf("muestra.zip", "a.txt" to "A", "dir/b.txt" to "B", "dir/sub/c.txt" to "C")
    private fun names(r: ListResult) = (r as ListResult.Ok).entries.map { (if (it.isDirectory) "d:" else "f:") + it.name }
    private fun dest() = tmp.resolve("destino").also { Files.createDirectories(it) }
    private fun children(d: Path) = Files.list(d).use { s -> s.map { it.fileName.toString() }.sorted().collect(java.util.stream.Collectors.toList()) }

    @Test fun listar_la_raiz_muestra_carpetas_implicitas_y_archivos() {
        val z = sample()
        assertEquals(listOf("d:dir", "f:a.txt"), names(Lister.list(z, false, SortSpec())))
    }

    @Test fun listar_una_carpeta_interna() {
        val z = sample()
        val r = Lister.list(z.resolve("dir"), false, SortSpec())
        assertEquals(listOf("d:sub", "f:b.txt"), names(r))
        val e = (r as ListResult.Ok).entries.first { it.name == "b.txt" }
        assertEquals(z.resolve("dir").resolve("b.txt"), e.path)
        assertTrue(e.inArchive)
        assertEquals(1L, e.size)
    }

    @Test fun localizar_separa_el_zip_de_la_ruta_interna() {
        val z = sample()
        assertEquals(ZipLocation(z, ""), Zip.locate(z))
        assertEquals(ZipLocation(z, "dir/sub"), Zip.locate(z.resolve("dir").resolve("sub")))
        assertNull(Zip.locate(tmp))
    }

    @Test fun carpeta_interna_que_no_existe_da_NOT_FOUND() {
        val z = sample()
        assertEquals(ListResult.Error(ListError.NOT_FOUND), Lister.list(z.resolve("nada"), false, SortSpec()))
    }

    @Test fun zip_danado_da_BAD_ARCHIVE() {
        val z = Files.write(tmp.resolve("roto.zip"), "esto no es un zip".toByteArray())
        assertEquals(ListResult.Error(ListError.BAD_ARCHIVE), Lister.list(z, false, SortSpec()))
        val e = assertThrows(OpFailure::class.java) { runBlocking { Zip.extract(z, listOf(""), dest(), never) {} } }
        assertEquals(OpFailure.Kind.BAD_ARCHIVE, e.kind)
    }

    @Test fun extraer_todo() = runBlocking {
        val d = dest()
        Zip.extract(sample(), listOf(""), d, never) {}
        assertEquals(listOf("a.txt", "dir"), children(d))
        assertEquals("C", Files.readString(d.resolve("dir/sub/c.txt")))
    }

    @Test fun extraer_una_carpeta_seleccionada() = runBlocking {
        val d = dest()
        val made = Zip.extract(sample(), listOf("dir"), d, never) {}
        assertEquals(listOf(d.resolve("dir")), made)
        assertEquals(listOf("dir"), children(d))
        assertEquals("B", Files.readString(d.resolve("dir/b.txt")))
        assertEquals("C", Files.readString(d.resolve("dir/sub/c.txt")))
    }

    @Test fun extraer_un_archivo_de_una_subcarpeta_lo_deja_suelto() = runBlocking {
        val d = dest()
        Zip.extract(sample(), listOf("dir/sub/c.txt"), d, never) {}
        assertEquals(listOf("c.txt"), children(d))
    }

    @Test fun extraer_no_escribe_fuera_del_destino() = runBlocking {
        val z = zipOf("malo.zip", "../fuera.txt" to "x", "/abs.txt" to "y", "bien.txt" to "z")
        val d = dest()
        Zip.extract(z, listOf(""), d, never) {}
        assertFalse(Files.exists(tmp.resolve("fuera.txt")))
        assertEquals(listOf("abs.txt", "bien.txt"), children(d))
    }

    @Test fun conflicto_al_extraer_omitir_y_conservar_ambos() = runBlocking {
        val d = dest()
        Files.write(d.resolve("a.txt"), "viejo".toByteArray())
        Zip.extract(sample(), listOf("a.txt"), d, { ConflictChoice.SKIP }) {}
        assertEquals("viejo", Files.readString(d.resolve("a.txt")))
        Zip.extract(sample(), listOf("a.txt"), d, { ConflictChoice.KEEP_BOTH }) {}
        assertEquals("A", Files.readString(d.resolve("a (2).txt")))
        Zip.extract(sample(), listOf("a.txt"), d, { ConflictChoice.REPLACE }) {}
        assertEquals("A", Files.readString(d.resolve("a.txt")))
    }

    @Test fun comprimir_y_extraer_ida_y_vuelta() = runBlocking {
        val src = Files.createDirectories(tmp.resolve("src"))
        val folder = Files.createDirectories(src.resolve("carpeta").resolve("vacia")).parent
        Files.write(folder.resolve("uno.txt"), "1".toByteArray())
        val suelto = Files.write(src.resolve("suelto.txt"), "2".toByteArray())
        val z = src.resolve("todo.zip")
        Zip.compress(listOf(folder, suelto), z) {}
        assertEquals(listOf("d:carpeta", "f:suelto.txt"), names(Lister.list(z, false, SortSpec())))
        assertEquals(listOf("d:vacia", "f:uno.txt"), names(Lister.list(z.resolve("carpeta"), false, SortSpec())))
        val d = dest()
        Zip.extract(z, listOf(""), d, never) {}
        assertEquals("1", Files.readString(d.resolve("carpeta/uno.txt")))
        assertTrue(Files.isDirectory(d.resolve("carpeta/vacia")))
        assertEquals(0L, Files.walk(src).use { s -> s.filter { it.fileName.toString().endsWith(CopyEngine.TMP_SUFFIX) }.count() })
    }

    @Test fun comprimir_no_pisa_un_zip_existente() {
        val f = Files.write(tmp.resolve("f.txt"), "x".toByteArray())
        val z = Files.write(tmp.resolve("ya.zip"), "previo".toByteArray())
        val e = assertThrows(OpFailure::class.java) { runBlocking { Zip.compress(listOf(f), z) {} } }
        assertEquals(OpFailure.Kind.ALREADY_EXISTS, e.kind)
        assertEquals("previo", Files.readString(z))
    }

    @Test fun cancelar_la_compresion_no_deja_zip_ni_temporal() {
        val src = Files.createDirectories(tmp.resolve("grande"))
        for (i in 1..3) Files.write(src.resolve("f$i.bin"), ByteArray(2 shl 20))
        val z = tmp.resolve("grande.zip")
        assertThrows(CancellationException::class.java) {
            runBlocking { coroutineScope { Zip.compress(listOf(src), z) { if (it.filesDone >= 1) cancel() } } }
        }
        assertFalse(Files.exists(z))
        assertFalse(Files.exists(CopyEngine.tmpFor(z)))
    }

    @Test fun extraer_un_archivo_suelto_para_abrirlo() {
        val out = tmp.resolve("cache").resolve("b.txt")
        Zip.extractOne(sample(), "dir/b.txt", out)
        assertEquals("B", Files.readString(out))
    }
}
