package io.github.v0uer.vfiles.core

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SearchTest {
    @TempDir lateinit var root: Path
    private fun found(q: String, hidden: Boolean = false) = runBlocking { Search.byName(root, q, hidden).toList().map { it.name }.sorted() }

    @Test fun encuentra_en_subcarpetas() {
        val deep = Files.createDirectories(root.resolve("a").resolve("b").resolve("c"))
        Files.write(deep.resolve("informe final.pdf"), ByteArray(1)); Files.write(root.resolve("otro.txt"), ByteArray(1))
        assertEquals(listOf("informe final.pdf"), found("final"))
    }

    @Test fun ignora_mayusculas_y_acentos() {
        Files.write(root.resolve("Canción.mp3"), ByteArray(1))
        assertEquals(listOf("Canción.mp3"), found("cancion"))
        assertEquals(listOf("Canción.mp3"), found("CANCIÓN"))
    }

    @Test fun tambien_encuentra_carpetas() {
        Files.createDirectories(root.resolve("x").resolve("Smash Ultimate"))
        assertEquals(listOf("Smash Ultimate"), found("smash"))
    }

    @Test fun consulta_vacia_no_emite() {
        Files.write(root.resolve("a"), ByteArray(1))
        assertEquals(emptyList<String>(), found("")); assertEquals(emptyList<String>(), found("   "))
    }

    @Test fun ocultos_solo_si_se_piden() {
        val h = Files.createDirectories(root.resolve(".secreto"))
        Files.write(h.resolve("clave.txt"), ByteArray(1))
        assertEquals(emptyList<String>(), found("clave"))
        assertEquals(listOf("clave.txt"), found("clave", hidden = true))
    }

    @Test fun no_entra_a_la_papelera() {
        val t = Files.createDirectories(root.resolve(Trash.DIR_NAME).resolve("id"))
        Files.write(t.resolve("payload"), ByteArray(1)); Files.write(root.resolve("payload real"), ByteArray(1))
        assertEquals(listOf("payload real"), found("payload", hidden = true))
    }

    @Test fun carpeta_ilegible_no_interrumpe() {
        val closed = Files.createDirectories(root.resolve("cerrada"))
        Files.write(closed.resolve("dato.txt"), ByteArray(1)); Files.write(root.resolve("dato2.txt"), ByteArray(1))
        assumeTrue(closed.toFile().setReadable(false, false) && !Files.isReadable(closed))
        try { assertEquals(listOf("dato2.txt"), found("dato")) } finally { closed.toFile().setReadable(true, false) }
    }

    @Test fun cancelar_la_recoleccion_detiene_el_recorrido() = runBlocking {
        repeat(200) { Files.write(root.resolve("f$it.txt"), ByteArray(1)) }
        assertEquals(3, Search.byName(root, "f", false).take(3).toList().size)
    }
}
