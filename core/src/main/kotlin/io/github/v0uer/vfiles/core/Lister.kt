package io.github.v0uer.vfiles.core

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

object Lister {
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")
    private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp")
    private val AUDIO = setOf("mp3", "flac", "ogg", "opus", "wav", "m4a", "aac")
    private val ARCHIVE = setOf("zip", "7z", "rar", "tar", "gz", "xz", "bz2")
    private val TEXT = setOf("txt", "md", "log", "ini", "json", "xml", "cfg", "conf", "csv", "yml", "yaml")

    fun kindOf(name: String, isDirectory: Boolean): EntryKind {
        if (isDirectory) return EntryKind.FOLDER
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return EntryKind.OTHER
        return when (name.substring(dot + 1).lowercase()) {
            in IMAGE -> EntryKind.IMAGE
            in VIDEO -> EntryKind.VIDEO
            in AUDIO -> EntryKind.AUDIO
            in ARCHIVE -> EntryKind.ARCHIVE
            in TEXT -> EntryKind.TEXT
            "apk" -> EntryKind.APK
            else -> EntryKind.OTHER
        }
    }

    /** Lee una entrada; null si desapareció mientras se listaba. */
    fun entryOf(path: Path): FileEntry? = try {
        val a = Files.readAttributes(path, BasicFileAttributes::class.java)
        val name = path.fileName?.toString() ?: path.toString()
        FileEntry(path, name, a.isDirectory, if (a.isDirectory) 0 else a.size(),
            a.lastModifiedTime().toMillis(), name.startsWith("."), kindOf(name, a.isDirectory))
    } catch (e: IOException) { null }

    /** Bloqueante: llamar desde un hilo de E/S. */
    fun list(dir: Path, showHidden: Boolean, sort: SortSpec): ListResult {
        // Un zip, o una carpeta dentro de uno, se lista por dentro.
        if (!Files.exists(dir) || (Zip.isZipName(dir.fileName?.toString().orEmpty()) && Files.isRegularFile(dir))) {
            Zip.locate(dir)?.let { return Zip.list(it, showHidden, sort) }
        }
        val out = ArrayList<FileEntry>()
        try {
            if (!Files.exists(dir)) return ListResult.Error(ListError.NOT_FOUND)
            if (!Files.isDirectory(dir)) return ListResult.Error(ListError.NOT_A_DIRECTORY)
            Files.newDirectoryStream(dir).use { stream ->
                for (p in stream) {
                    val e = entryOf(p) ?: continue
                    if (showHidden || !e.isHidden) out.add(e)
                }
            }
        } catch (e: NoSuchFileException) { return ListResult.Error(ListError.NOT_FOUND)
        } catch (e: AccessDeniedException) { return ListResult.Error(ListError.NO_ACCESS)
        } catch (e: NotDirectoryException) { return ListResult.Error(ListError.NOT_A_DIRECTORY)
        } catch (e: SecurityException) { return ListResult.Error(ListError.NO_ACCESS)
        } catch (e: IOException) { return ListResult.Error(ListError.IO)
        } catch (e: java.nio.file.DirectoryIteratorException) { return ListResult.Error(ListError.IO) }
        out.sortWith(comparator(sort))
        return ListResult.Ok(out)
    }

    fun comparator(sort: SortSpec): Comparator<FileEntry> {
        val byKey: Comparator<FileEntry> = when (sort.key) {
            SortKey.NAME -> NaturalName
            SortKey.DATE -> compareBy<FileEntry> { it.modifiedMillis }.then(NaturalName)
            SortKey.SIZE -> compareBy<FileEntry> { it.size }.then(NaturalName)
            SortKey.TYPE -> compareBy<FileEntry> { it.name.substringAfterLast('.', "").lowercase() }.then(NaturalName)
        }
        val directed = if (sort.ascending) byKey else byKey.reversed()
        return compareByDescending<FileEntry> { it.isDirectory }.then(directed)
    }

    private object NaturalName : Comparator<FileEntry> {
        override fun compare(a: FileEntry, b: FileEntry) = naturalCompare(a.name, b.name)
    }

    /** Orden natural sin distinguir mayúsculas: "a2" antes que "a10". */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
                var je = j; while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0'); val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb); if (c != 0) return c
                i = ie; j = je
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
                i++; j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }
}
