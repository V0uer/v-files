package local.archivero.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TrashTest {
    @TempDir lateinit var root: Path
    private var clock = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private fun trash() = Trash({ root }, { clock })
    private fun names(d: Path) = Files.list(d).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

    @Test fun a_papelera_y_restaurar_devuelve_contenido_identico() {
        val dir = Files.createDirectories(root.resolve("docs").resolve("sub")).parent
        Files.write(dir.resolve("sub").resolve("a.txt"), "hola".toByteArray())
        val t = trash()
        val item = t.moveToTrash(listOf(dir)).single()
        assertFalse(Files.exists(dir))
        assertEquals(dir, item.originalPath); assertTrue(item.isDirectory); assertEquals(4L, item.sizeBytes)
        assertEquals(listOf(item), t.list(root))
        val back = t.restore(item, root)
        assertEquals(dir, back)
        assertEquals("hola", Files.readString(dir.resolve("sub").resolve("a.txt")))
        assertEquals(emptyList<TrashItem>(), t.list(root))
    }

    @Test fun restaurar_con_ruta_ocupada_crea_nombre_2() {
        val f = Files.write(root.resolve("a.txt"), "viejo".toByteArray())
        val t = trash(); val item = t.moveToTrash(listOf(f)).single()
        Files.write(f, "nuevo".toByteArray())
        val back = t.restore(item, root)
        assertEquals("a (2).txt", back.fileName.toString())
        assertEquals("viejo", Files.readString(back)); assertEquals("nuevo", Files.readString(f))
    }

    @Test fun restaurar_recrea_carpeta_padre_si_ya_no_existe() {
        val parent = Files.createDirectories(root.resolve("x").resolve("y"))
        val f = Files.write(parent.resolve("a.txt"), "a".toByteArray())
        val t = trash(); val item = t.moveToTrash(listOf(f)).single()
        Files.delete(parent); Files.delete(parent.parent)
        assertEquals("a", Files.readString(t.restore(item, root)))
    }

    @Test fun purgeExpired_borra_31_dias_y_conserva_29() {
        val t = trash()
        val viejo = t.moveToTrash(listOf(Files.write(root.resolve("viejo"), ByteArray(1)))).single()
        clock += 2 * day
        val nuevo = t.moveToTrash(listOf(Files.write(root.resolve("nuevo"), ByteArray(1)))).single()
        clock += 29 * day
        assertEquals(1, t.purgeExpired(root))
        assertEquals(listOf(nuevo), t.list(root))
        assertFalse(Files.exists(root.resolve(Trash.DIR_NAME).resolve(viejo.id)))
    }

    @Test fun list_va_del_mas_reciente_al_mas_viejo() {
        val t = trash()
        val a = t.moveToTrash(listOf(Files.write(root.resolve("a"), ByteArray(1)))).single()
        clock += 1000
        val b = t.moveToTrash(listOf(Files.write(root.resolve("b"), ByteArray(1)))).single()
        assertEquals(listOf(b, a), t.list(root))
    }

    @Test fun list_ignora_entradas_con_meta_corrupta_sin_lanzar() {
        val t = trash()
        val ok = t.moveToTrash(listOf(Files.write(root.resolve("a"), ByteArray(1)))).single()
        val bad = Files.createDirectories(root.resolve(Trash.DIR_NAME).resolve("basura"))
        Files.write(bad.resolve("meta"), "no soy meta".toByteArray())
        Files.createDirectories(root.resolve(Trash.DIR_NAME).resolve("sin-meta"))
        assertEquals(listOf(ok), t.list(root))
    }

    @Test fun purge_borra_definitivamente() {
        val t = trash()
        val item = t.moveToTrash(listOf(Files.write(root.resolve("a"), ByteArray(1)))).single()
        t.purge(item, root)
        assertEquals(emptyList<TrashItem>(), t.list(root))
        assertFalse(Files.exists(root.resolve(Trash.DIR_NAME).resolve(item.id)))
    }

    @Test fun la_papelera_no_aparece_con_showHidden_false() {
        trash().moveToTrash(listOf(Files.write(root.resolve("a"), ByteArray(1))))
        Files.write(root.resolve("b"), ByteArray(1))
        assertEquals(listOf("b"), (Lister.list(root, false, SortSpec()) as ListResult.Ok).entries.map { it.name })
        assertTrue(Files.exists(root.resolve(Trash.DIR_NAME).resolve(".nomedia")))
    }

    @Test fun list_en_volumen_sin_papelera_devuelve_vacio() = assertEquals(emptyList<TrashItem>(), trash().list(root))
}
