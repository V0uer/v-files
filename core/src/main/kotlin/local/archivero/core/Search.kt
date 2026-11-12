package local.archivero.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer

object Search {
    /** Minúsculas y sin acentos, para comparar "Canción" con "cancion". */
    fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }.lowercase()

    /**
     * Emite coincidencias conforme las encuentra. Recorre en anchura, no sigue enlaces,
     * salta carpetas ilegibles y nunca entra a la papelera.
     */
    fun byName(root: Path, query: String, includeHidden: Boolean): Flow<FileEntry> = flow {
        val needle = fold(query.trim())
        if (needle.isEmpty()) return@flow
        val queue = ArrayDeque<Path>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            val batch = ArrayList<FileEntry>()
            try {
                Files.newDirectoryStream(dir).use { s ->
                    for (p in s) {
                        val name = p.fileName.toString()
                        if (name == Trash.DIR_NAME) continue
                        if (!includeHidden && name.startsWith(".")) continue
                        val isDir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                        if (isDir) queue.add(p)
                        if (fold(name).contains(needle)) Lister.entryOf(p)?.let { batch.add(it) }
                    }
                }
            } catch (e: IOException) { continue } catch (e: java.nio.file.DirectoryIteratorException) { continue } catch (e: SecurityException) { continue }
            for (e in batch) emit(e)
        }
    }.flowOn(Dispatchers.IO)
}
