package io.github.v0uer.vfiles.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object Names {
    fun isValid(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.code == 0 }

    /** Devuelve la entrada de [dir] cuyo nombre coincide con [name] sin distinguir mayúsculas, o null. */
    fun existsIgnoreCase(dir: Path, name: String): Path? {
        try {
            Files.newDirectoryStream(dir).use { s ->
                for (p in s) if (p.fileName.toString().equals(name, ignoreCase = true)) return p
            }
        } catch (e: IOException) { /* carpeta ilegible: no hay conflicto que podamos ver */ }
        return null
    }

    /** "foto.jpg" -> "foto (2).jpg", "(3)"... El primer nombre libre en [dir]. */
    fun keepBoth(dir: Path, name: String): String = keepBoth(name) { existsIgnoreCase(dir, it) != null }

    fun keepBoth(name: String, taken: (String) -> Boolean): String {
        val dot = name.lastIndexOf('.')
        // Sin extensión: ocultos (".config"), sin punto, o lo que sigue al punto no parece extensión.
        val hasExt = dot > 0 && dot < name.length - 1 && name.substring(dot + 1).all { it.isLetterOrDigit() } &&
            name.substring(dot + 1).any { it.isLetter() }
        val stem = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (!taken(candidate)) return candidate
            n++
        }
    }

    fun isSameOrDescendant(parent: Path, candidate: Path): Boolean {
        val p = parent.toAbsolutePath().normalize()
        val c = candidate.toAbsolutePath().normalize()
        return c.startsWith(p)
    }
}
