package local.archivero.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

enum class ConflictChoice { SKIP, REPLACE, KEEP_BOTH }

data class Progress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentName: String,
)

class OpFailure(val kind: Kind, val path: Path, cause: Throwable? = null) :
    Exception("$kind: $path", cause) {
    enum class Kind { NO_SPACE, NO_ACCESS, SOURCE_GONE, INTO_ITSELF, FILE_TOO_LARGE, ALREADY_EXISTS, IO }

    companion object {
        fun from(e: IOException, path: Path): OpFailure {
            val msg = e.message.orEmpty()
            val kind = when {
                e is AccessDeniedException || msg.contains("EACCES") || msg.contains("EPERM") -> Kind.NO_ACCESS
                e is NoSuchFileException -> Kind.SOURCE_GONE
                msg.contains("ENOSPC") || msg.contains("No space left", true) ||
                    msg.contains("not enough space", true) -> Kind.NO_SPACE
                msg.contains("EFBIG") || msg.contains("File too large", true) -> Kind.FILE_TOO_LARGE
                else -> Kind.IO
            }
            return OpFailure(kind, path, e)
        }
    }
}

class CopyEngine(
    private val bufferSize: Int = 1 shl 20,
    private val openOutput: (Path) -> OutputStream = { Files.newOutputStream(it) },
) {
    companion object {
        const val TMP_SUFFIX = ".archivero-tmp"
        fun tmpFor(finalPath: Path): Path = finalPath.resolveSibling("." + finalPath.fileName + TMP_SUFFIX)
    }

    private class Run(
        val onConflict: suspend (Path) -> ConflictChoice,
        val onProgress: (Progress) -> Unit,
        val filesTotal: Int,
        val bytesTotal: Long,
    ) {
        var filesDone = 0
        var bytesDone = 0L
        /** Archivos de origen copiados por completo desde el último corte. */
        val copied = ArrayList<Path>()
        /** Por carpeta destino: nombre en minúsculas -> nombre real. */
        val names = HashMap<Path, HashMap<String, String>>()
        fun report(name: String) = onProgress(Progress(filesDone, filesTotal, bytesDone, bytesTotal, name))
        fun namesOf(dir: Path): HashMap<String, String> = names.getOrPut(dir) {
            val m = HashMap<String, String>()
            try {
                Files.newDirectoryStream(dir).use { s -> for (p in s) p.fileName.toString().let { m[it.lowercase()] = it } }
            } catch (e: IOException) { /* se descubrirá al escribir */ }
            m
        }
    }

    /** Copia archivos o carpetas (recursivo) a [destDir]. Lanza [OpFailure]; respeta la cancelación. */
    suspend fun copy(
        sources: List<Path>,
        destDir: Path,
        onConflict: suspend (existing: Path) -> ConflictChoice,
        onProgress: (Progress) -> Unit,
    ): List<Path> = copyTracked(sources, destDir, onConflict, onProgress) { _, _ -> }

    /**
     * Igual que [copy], y tras terminar cada origen de primer nivel llama a [afterSource] con
     * los archivos de ese origen que sí se copiaron (los omitidos no aparecen).
     */
    suspend fun copyTracked(
        sources: List<Path>,
        destDir: Path,
        onConflict: suspend (existing: Path) -> ConflictChoice,
        onProgress: (Progress) -> Unit,
        afterSource: (source: Path, copiedFiles: List<Path>) -> Unit,
    ): List<Path> = withContext(Dispatchers.IO) {
        for (s in sources) {
            if (!Files.exists(s, LinkOption.NOFOLLOW_LINKS)) throw OpFailure(OpFailure.Kind.SOURCE_GONE, s)
            if (Files.isDirectory(s) && Names.isSameOrDescendant(s, destDir)) throw OpFailure(OpFailure.Kind.INTO_ITSELF, s)
        }
        var files = 0
        var bytes = 0L
        for (s in sources) {
            try {
                Files.walk(s).use { st ->
                    st.forEach { p ->
                        if (Files.isRegularFile(p)) { files++; bytes += runCatching { Files.size(p) }.getOrDefault(0L) }
                    }
                }
            } catch (e: IOException) { throw OpFailure.from(e, s) }
            coroutineContext.ensureActive()
        }
        val run = Run(onConflict, onProgress, files, bytes)
        val made = ArrayList<Path>()
        for (s in sources) {
            run.copied.clear()
            copyOne(s, destDir, run)?.let { made.add(it) }
            afterSource(s, run.copied.toList())
        }
        run.report("")
        made
    }

    /** Devuelve la ruta creada, o null si se omitió. */
    private suspend fun copyOne(src: Path, destDir: Path, run: Run): Path? {
        coroutineContext.ensureActive()
        val name = src.fileName.toString()
        val srcIsDir = Files.isDirectory(src)
        val known = run.namesOf(destDir)
        var targetName = name
        var merge = false
        var replace = false
        val existingName = known[name.lowercase()]
        if (existingName != null) {
            val existing = destDir.resolve(existingName)
            val same = runCatching { Files.isSameFile(existing, src) }.getOrDefault(false)
            var choice = run.onConflict(existing)
            // Reemplazar un archivo por sí mismo lo destruiría; y no se mezcla archivo con carpeta.
            if (choice == ConflictChoice.REPLACE && same) choice = ConflictChoice.SKIP
            if (choice == ConflictChoice.REPLACE && Files.isDirectory(existing) != srcIsDir) choice = ConflictChoice.KEEP_BOTH
            when (choice) {
                ConflictChoice.SKIP -> { skip(src, run); return null }
                ConflictChoice.KEEP_BOTH -> targetName = Names.keepBoth(name) { known.containsKey(it.lowercase()) }
                ConflictChoice.REPLACE -> { targetName = existingName; if (srcIsDir) merge = true else replace = true }
            }
        }
        val target = destDir.resolve(targetName)
        if (srcIsDir) {
            if (!merge) {
                try { Files.createDirectory(target) } catch (e: IOException) { throw OpFailure.from(e, target) }
                known[targetName.lowercase()] = targetName
            }
            val children = try {
                Files.newDirectoryStream(src).use { it.sortedBy { p -> p.fileName.toString() } }
            } catch (e: IOException) { throw OpFailure.from(e, src) }
            for (c in children) copyOne(c, target, run)
            runCatching { Files.setLastModifiedTime(target, Files.getLastModifiedTime(src)) }
        } else {
            copyFile(src, target, replace, run)
            known[targetName.lowercase()] = targetName
        }
        return target
    }

    private fun skip(src: Path, run: Run) {
        // Lo omitido cuenta como hecho para que el progreso llegue al total.
        runCatching {
            Files.walk(src).use { st ->
                st.forEach { p -> if (Files.isRegularFile(p)) { run.filesDone++; run.bytesDone += runCatching { Files.size(p) }.getOrDefault(0L) } }
            }
        }
        run.report(src.fileName.toString())
    }

    private suspend fun copyFile(src: Path, target: Path, replace: Boolean, run: Run) {
        val ctx: CoroutineContext = coroutineContext
        val tmp = tmpFor(target)
        val name = src.fileName.toString()
        var ok = false
        try {
            val expected: Long
            try {
                expected = Files.size(src)
                Files.newInputStream(src).use { input ->
                    openOutput(tmp).use { out ->
                        val buf = ByteArray(bufferSize)
                        while (true) {
                            ctx.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            run.bytesDone += n
                            run.report(name)
                        }
                    }
                }
                if (Files.size(tmp) != expected) throw IOException("tamaño distinto tras copiar")
                runCatching { Files.setLastModifiedTime(tmp, Files.getLastModifiedTime(src)) }
                if (replace) Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                else Files.move(tmp, target)
            } catch (e: NoSuchFileException) {
                throw OpFailure(if (Files.exists(src)) OpFailure.Kind.IO else OpFailure.Kind.SOURCE_GONE, src, e)
            } catch (e: IOException) {
                throw OpFailure.from(e, target)
            }
            ok = true
            run.copied.add(src)
            run.filesDone++
            run.report(name)
        } finally {
            if (!ok) runCatching { Files.deleteIfExists(tmp) }
        }
    }
}
