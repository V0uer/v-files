package local.archivero.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class NamesTest {
    @TempDir lateinit var dir: Path

    @Test fun keepBoth_numera_antes_de_la_extension() {
        Files.createFile(dir.resolve("foto.jpg"))
        assertEquals("foto (2).jpg", Names.keepBoth(dir, "foto.jpg"))
        Files.createFile(dir.resolve("foto (2).jpg"))
        assertEquals("foto (3).jpg", Names.keepBoth(dir, "foto.jpg"))
    }

    @Test fun keepBoth_en_carpeta_no_parte_por_el_punto() {
        Files.createDirectory(dir.resolve("v1.2"))
        assertEquals("v1.2 (2)", Names.keepBoth(dir, "v1.2"))
    }

    @Test fun keepBoth_archivo_oculto_sin_extension() {
        Files.createFile(dir.resolve(".config"))
        assertEquals(".config (2)", Names.keepBoth(dir, ".config"))
    }

    @Test fun existsIgnoreCase_encuentra_con_otras_mayusculas() {
        Files.createFile(dir.resolve("foto.jpg"))
        assertEquals("foto.jpg", Names.existsIgnoreCase(dir, "FOTO.JPG")?.fileName.toString())
        assertNull(Names.existsIgnoreCase(dir, "otra.jpg"))
    }

    @Test fun isSameOrDescendant() {
        val a = Paths.get("a").toAbsolutePath()
        assertTrue(Names.isSameOrDescendant(a, a.resolve("b").resolve("c")))
        assertTrue(Names.isSameOrDescendant(a, a))
        assertFalse(Names.isSameOrDescendant(a, a.resolveSibling("ab")))
    }

    @Test fun isValid() {
        val nul = "a" + 0.toChar() + "b"
        assertFalse(Names.isValid("")); assertFalse(Names.isValid("a/b")); assertFalse(Names.isValid(".."))
        assertFalse(Names.isValid(".")); assertFalse(Names.isValid(nul)); assertFalse(Names.isValid("   "))
        assertTrue(Names.isValid("Foto de ayer.jpg")); assertTrue(Names.isValid(".oculto"))
    }
}
