package local.archivero.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

data class TrashItem(
    val id: String,
    val originalPath: Path,
    val deletedAtMillis: Long,
    val sizeBytes: Long,
    val isDirectory: Boolean,
)

/**
 * Papelera por volumen: `<raíz>/.archivero-papelera/<id>/payload` más `<id>/meta`.
 * Mandar a la papelera es un renombrado dentro del mismo volumen, así que es inmediato.
 */
class Trash(
    private val volumeRootOf: (Path) -> Path,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DIR_NAME = ".archivero-papelera"
        const val RETENTION_DAYS = 30L
        private const val PAYLOAD = "payload"
        private const val META = "meta"
    }

    private fun trashDir(volumeRoot: Path) = volumeRoot.resolve(DIR_NAME)

    fun moveToTrash(targets: List<Path>): List<TrashItem> {
        val out = ArrayList<TrashItem>()
        for (t in targets) {
            if (!Files.exists(t, LinkOption.NOFOLLOW_LINKS)) continue
            val abs = t.toAbsolutePath()
            try {
                val dir = trashDir(volumeRootOf(abs))
                if (!Files.isDirectory(dir)) {
                    Files.createDirectories(dir)
                    runCatching { Files.createFile(dir.resolve(".nomedia")) }
                }
                val isDir = Files.isDirectory(abs, LinkOption.NOFOLLOW_LINKS)
                val item = TrashItem(UUID.randomUUID().toString(), abs, now(), sizeOf(abs), isDir)
                val slot = Files.createDirectory(dir.resolve(item.id))
                Files.write(slot.resolve(META), encode(item).toByteArray(Charsets.UTF_8))
                try { Files.move(abs, slot.resolve(PAYLOAD)) } catch (e: IOException) {
                    runCatching { Files.deleteIfExists(slot.resolve(META)); Files.deleteIfExists(slot) }
                    throw e
                }
                out.add(item)
            } catch (e: IOException) { throw OpFailure.from(e, t) }
        }
        return out
    }

    fun list(volumeRoot: Path): List<TrashItem> {
        val dir = trashDir(volumeRoot)
        if (!Files.isDirectory(dir)) return emptyList()
        val out = ArrayList<TrashItem>()
        try {
            Files.newDirectoryStream(dir).use { s ->
                for (slot in s) {
                    if (!Files.isDirectory(slot)) continue
                    val item = runCatching {
                        decode(slot.fileName.toString(), String(Files.readAllBytes(slot.resolve(META)), Charsets.UTF_8))
                    }.getOrNull() ?: continue
                    if (Files.exists(slot.resolve(PAYLOAD), LinkOption.NOFOLLOW_LINKS)) out.add(item)
                }
            }
        } catch (e: IOException) { return emptyList()
        } catch (e: java.nio.file.DirectoryIteratorException) { return emptyList() }
        out.sortByDescending { it.deletedAtMillis }
        return out
    }

    /** Devuelve a su sitio; si la ruta original está ocupada, usa un nombre "(2)". */
    fun restore(item: TrashItem, volumeRoot: Path): Path {
        val slot = trashDir(volumeRoot).resolve(item.id)
        val parent = item.originalPath.parent ?: volumeRoot
        try {
            Files.createDirectories(parent)
            val name = item.originalPath.fileName.toString()
            val finalName = if (Names.existsIgnoreCase(parent, name) != null) Names.keepBoth(parent, name) else name
            val dest = Files.move(slot.resolve(PAYLOAD), parent.resolve(finalName))
            runCatching { Files.deleteIfExists(slot.resolve(META)); Files.deleteIfExists(slot) }
            return dest
        } catch (e: IOException) { throw OpFailure.from(e, item.originalPath) }
    }

    fun purge(item: TrashItem, volumeRoot: Path) = deleteTree(trashDir(volumeRoot).resolve(item.id))

    fun purgeExpired(volumeRoot: Path): Int {
        val limit = now() - RETENTION_DAYS * 24 * 60 * 60 * 1000
        var n = 0
        for (item in list(volumeRoot)) if (item.deletedAtMillis < limit) { runCatching { purge(item, volumeRoot); n++ } }
        return n
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        try {
            val all = ArrayList<Path>().also { out -> Files.walk(root).use { st -> st.forEach { out.add(it) } } }
            for (p in all.sortedByDescending { it.nameCount }) Files.deleteIfExists(p)
        } catch (e: IOException) { throw OpFailure.from(e, root) }
    }

    private fun sizeOf(p: Path): Long = runCatching {
        Files.walk(p).use { st -> st.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum() }
    }.getOrDefault(0L)

    private fun encode(i: TrashItem) = "v1\n${i.originalPath}\n${i.deletedAtMillis}\n${i.sizeBytes}\n${i.isDirectory}\n"

    private fun decode(id: String, raw: String): TrashItem? {
        val l = raw.split('\n')
        if (l.size < 5 || l[0] != "v1" || l[1].isBlank()) return null
        return TrashItem(id, Paths.get(l[1]), l[2].toLong(), l[3].toLong(), l[4].toBooleanStrict())
    }
}
