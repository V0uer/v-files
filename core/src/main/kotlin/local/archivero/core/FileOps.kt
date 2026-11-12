package local.archivero.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class Properties(
    val path: Path,
    val totalBytes: Long,
    val fileCount: Int,
    val folderCount: Int,
    val modifiedMillis: Long,
)

class FileOps(
    private val copier: CopyEngine,
    private val sameVolume: (Path, Path) -> Boolean = { a, b ->
        runCatching { Files.getFileStore(a) == Files.getFileStore(b) }.getOrDefault(false)
    },
) {
    /**
     * Mueve a [destDir]. En el mismo volumen es un renombrado; entre volúmenes copia y, solo
     * cuando un origen terminó de copiarse, borra de él lo que sí se copió.
     */
    suspend fun move(
        sources: List<Path>,
        destDir: Path,
        onConflict: suspend (Path) -> ConflictChoice,
        onProgress: (Progress) -> Unit,
    ): List<Path> = withContext(Dispatchers.IO) {
        for (s in sources) {
            if (!Files.exists(s, LinkOption.NOFOLLOW_LINKS)) throw OpFailure(OpFailure.Kind.SOURCE_GONE, s)
            if (Files.isDirectory(s) && Names.isSameOrDescendant(s, destDir)) throw OpFailure(OpFailure.Kind.INTO_ITSELF, s)
        }
        val made = ArrayList<Path>()
        val viaCopy = ArrayList<Path>()
        val decided = HashMap<Path, ConflictChoice>()
        var done = 0
        for (s in sources) {
            coroutineContext.ensureActive()
            val name = s.fileName.toString()
            if (!sameVolume(s, destDir)) { viaCopy.add(s); continue }
            val existing = Names.existsIgnoreCase(destDir, name)
            if (existing == null) {
                made.add(rawMove(s, destDir.resolve(name), false))
            } else if (runCatching { Files.isSameFile(existing, s) }.getOrDefault(false)) {
                // Ya está ahí: mover a su propia carpeta no hace nada.
            } else {
                val choice = onConflict(existing)
                val bothFiles = !Files.isDirectory(existing) && !Files.isDirectory(s)
                when {
                    choice == ConflictChoice.SKIP -> {}
                    choice == ConflictChoice.KEEP_BOTH -> made.add(rawMove(s, destDir.resolve(Names.keepBoth(destDir, name)), false))
                    bothFiles -> made.add(rawMove(s, existing, true))
                    // Fusionar carpetas (o tipos distintos): por copia, que sabe preguntar por cada archivo.
                    else -> { decided[existing] = choice; viaCopy.add(s) }
                }
            }
            done++
            onProgress(Progress(done, sources.size, 0, 0, name))
        }
        if (viaCopy.isNotEmpty()) {
            val ask: suspend (Path) -> ConflictChoice = { existing -> decided.remove(existing) ?: onConflict(existing) }
            made += copier.copyTracked(viaCopy, destDir, ask, onProgress) { _, copied ->
                // Postorden: al llegar a una carpeta ya se borró lo suyo que se copió; si aún
                // tiene algo (lo omitido), el borrado falla y se queda.
                for (p in copied) runCatching { Files.deleteIfExists(p) }
            }
        }
        made
    }

    private fun rawMove(from: Path, to: Path, replace: Boolean): Path = try {
        if (replace) Files.move(from, to, StandardCopyOption.REPLACE_EXISTING) else Files.move(from, to)
    } catch (e: IOException) { throw OpFailure.from(e, from) }

    fun rename(target: Path, newName: String): Path {
        require(Names.isValid(newName)) { "nombre inválido" }
        val parent = target.parent ?: throw OpFailure(OpFailure.Kind.IO, target)
        val current = target.fileName.toString()
        if (current == newName) return target
        val dest = parent.resolve(newName)
        try {
            if (current.equals(newName, ignoreCase = true)) {
                // Solo cambian mayúsculas: en almacenamiento que no las distingue hay que pasar por un nombre intermedio.
                val mid = parent.resolve(".$current.${UUID.randomUUID()}.archivero-tmp")
                Files.move(target, mid)
                try { Files.move(mid, dest) } catch (e: IOException) { Files.move(mid, target); throw e }
                return dest
            }
            if (Names.existsIgnoreCase(parent, newName) != null) throw OpFailure(OpFailure.Kind.ALREADY_EXISTS, dest)
            return Files.move(target, dest)
        } catch (e: IOException) { throw OpFailure.from(e, target) }
    }

    fun createFolder(parent: Path, name: String): Path {
        require(Names.isValid(name)) { "nombre inválido" }
        val dest = parent.resolve(name)
        if (Names.existsIgnoreCase(parent, name) != null) throw OpFailure(OpFailure.Kind.ALREADY_EXISTS, dest)
        return try { Files.createDirectory(dest) } catch (e: IOException) { throw OpFailure.from(e, dest) }
    }

    suspend fun deletePermanently(targets: List<Path>, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val all = ArrayList<Path>()
        for (t in targets) {
            if (!Files.exists(t, LinkOption.NOFOLLOW_LINKS)) continue
            try { Files.walk(t).use { st -> st.forEach { all.add(it) } } } catch (e: IOException) { throw OpFailure.from(e, t) }
            coroutineContext.ensureActive()
        }
        // Lo más profundo primero, para que las carpetas ya estén vacías al llegar a ellas.
        all.sortByDescending { it.nameCount }
        var done = 0
        for (p in all) {
            coroutineContext.ensureActive()
            try { Files.deleteIfExists(p) } catch (e: IOException) { throw OpFailure.from(e, p) }
            done++
            if (done % 50 == 0 || done == all.size) onProgress(Progress(done, all.size, 0, 0, p.fileName?.toString().orEmpty()))
        }
    }

    suspend fun properties(target: Path): Properties = withContext(Dispatchers.IO) {
        var bytes = 0L; var files = 0; var folders = 0
        val stack = ArrayDeque<Path>()
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) stack.add(target)
        else { bytes = runCatching { Files.size(target) }.getOrDefault(0L); files = 1 }
        while (stack.isNotEmpty()) {
            coroutineContext.ensureActive()
            val d = stack.removeLast()
            try {
                Files.newDirectoryStream(d).use { s ->
                    for (p in s) {
                        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) { folders++; stack.add(p) }
                        else { files++; bytes += runCatching { Files.size(p) }.getOrDefault(0L) }
                    }
                }
            } catch (e: IOException) { /* carpeta ilegible: se cuenta lo que se pueda */
            } catch (e: java.nio.file.DirectoryIteratorException) { /* igual, si falla a media lista */ }
        }
        Properties(target, bytes, files, folders, runCatching { Files.getLastModifiedTime(target).toMillis() }.getOrDefault(0L))
    }
}
