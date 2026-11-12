package local.archivero.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class ListerTest {
    @TempDir lateinit var dir: Path

    private fun file(name: String, bytes: Int = 0, mtime: Long? = null): Path {
        val p = dir.resolve(name)
        Files.write(p, ByteArray(bytes))
        if (mtime != null) Files.setLastModifiedTime(p, FileTime.fromMillis(mtime))
        return p
    }
    private fun names(r: ListResult) = (r as ListResult.Ok).entries.map { it.name }

    @Test fun carpetas_primero_y_orden_natural() {
        file("b.txt"); file("a10.txt"); file("a2.txt"); Files.createDirectory(dir.resolve("zeta"))
        assertEquals(listOf("zeta", "a2.txt", "a10.txt", "b.txt"), names(Lister.list(dir, false, SortSpec())))
    }

    @Test fun orden_ignora_mayusculas() {
        file("Beta.txt"); file("alfa.txt")
        assertEquals(listOf("alfa.txt", "Beta.txt"), names(Lister.list(dir, false, SortSpec())))
    }

    @Test fun ocultos_se_filtran() {
        file(".x"); file("y")
        assertEquals(listOf("y"), names(Lister.list(dir, false, SortSpec())))
        assertEquals(listOf(".x", "y"), names(Lister.list(dir, true, SortSpec())))
    }

    @Test fun orden_por_tamano_descendente() {
        file("chico", 1); file("grande", 100); file("medio", 10)
        assertEquals(listOf("grande", "medio", "chico"), names(Lister.list(dir, false, SortSpec(SortKey.SIZE, false))))
    }

    @Test fun orden_por_fecha() {
        file("viejo", 0, 1_000_000L); file("nuevo", 0, 9_000_000L); file("medio", 0, 5_000_000L)
        assertEquals(listOf("viejo", "medio", "nuevo"), names(Lister.list(dir, false, SortSpec(SortKey.DATE, true))))
    }

    @Test fun entrada_lleva_tamano_fecha_y_tipo() {
        file("foto.jpg", 7, 5_000_000L)
        val e = (Lister.list(dir, false, SortSpec()) as ListResult.Ok).entries.single()
        assertEquals(7L, e.size); assertEquals(5_000_000L, e.modifiedMillis)
        assertEquals(EntryKind.IMAGE, e.kind); assertFalse(e.isDirectory); assertFalse(e.isHidden)
    }

    @Test fun carpeta_inexistente_devuelve_NOT_FOUND() =
        assertEquals(ListResult.Error(ListError.NOT_FOUND), Lister.list(dir.resolve("nada"), false, SortSpec()))

    @Test fun archivo_devuelve_NOT_A_DIRECTORY() =
        assertEquals(ListResult.Error(ListError.NOT_A_DIRECTORY), Lister.list(file("a"), false, SortSpec()))

    @Test fun sin_permiso_devuelve_NO_ACCESS() {
        val sub = Files.createDirectory(dir.resolve("cerrada"))
        val f = sub.toFile()
        assumeTrue(f.setReadable(false, false) && !Files.isReadable(sub))
        try { assertEquals(ListResult.Error(ListError.NO_ACCESS), Lister.list(sub, false, SortSpec())) }
        finally { f.setReadable(true, false) }
    }

    @Test fun kindOf_por_extension() {
        assertEquals(EntryKind.IMAGE, Lister.kindOf("a.JPG", false))
        assertEquals(EntryKind.ARCHIVE, Lister.kindOf("a.zip", false))
        assertEquals(EntryKind.ARCHIVE, Lister.kindOf("a.7z", false))
        assertEquals(EntryKind.ARCHIVE, Lister.kindOf("a.rar", false))
        assertEquals(EntryKind.OTHER, Lister.kindOf("juego.nsp", false))
        assertEquals(EntryKind.APK, Lister.kindOf("x.apk", false))
        assertEquals(EntryKind.VIDEO, Lister.kindOf("x.mp4", false))
        assertEquals(EntryKind.AUDIO, Lister.kindOf("x.mp3", false))
        assertEquals(EntryKind.TEXT, Lister.kindOf("x.txt", false))
        assertEquals(EntryKind.FOLDER, Lister.kindOf("carpeta.jpg", true))
        assertEquals(EntryKind.OTHER, Lister.kindOf("sinextension", false))
    }

    @Test fun cinco_mil_archivos_en_menos_de_300ms() {
        repeat(5000) { Files.createFile(dir.resolve("f%05d.bin".format(it))) }
        Lister.list(dir, false, SortSpec())
        val t0 = System.nanoTime()
        val r = Lister.list(dir, false, SortSpec())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(5000, (r as ListResult.Ok).entries.size)
        assertTrue(ms < 300, "tardó $ms ms")
    }
}
