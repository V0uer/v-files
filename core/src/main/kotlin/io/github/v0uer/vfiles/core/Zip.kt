package io.github.v0uer.vfiles.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Un lugar dentro de un zip: el archivo y la ruta interna con "/" ("" = la raíz). */
data class ZipLocation(val zip: Path, val inner: String)

/**
 * Zips como carpetas de solo lectura: listar por dentro, extraer y comprimir.
 * Nada se escribe fuera de la carpeta destino (las rutas con ".." se descartan) y todo
 * archivo se escribe primero como temporal, igual que en [CopyEngine].
 */
object Zip {
    private const val BUFFER = 1 shl 16

    fun isZipName(name: String): Boolean = name.endsWith(".zip", ignoreCase = true)

    /** Si [path] es un zip o está dentro de uno, dónde. Mira la ruta hacia arriba. */
    fun locate(path: Path): ZipLocation? {
        var p: Path? = path
        while (p != null) {
            val name = p.fileName?.toString()
            if (name != null && isZipName(name) && Files.isRegularFile(p)) {
                val rel = p.relativize(path)
                val inner = if (rel.toString().isEmpty()) "" else rel.joinToString("/") { it.toString() }
                return ZipLocation(p, inner)
            }
            p = p.parent
        }
        return null
    }

    /** Ruta interna limpia ("a/b"), o null si es peligrosa (".."). Quita "/" inicial y "\". */
    internal fun clean(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    /**
     * Abre el zip y lee sus entradas. Prueba nombres UTF-8 y, si no se entienden, la
     * página de códigos de los zip viejos de Windows (IBM437).
     */
    private fun open(zip: Path): Pair<ZipFile, List<ZipEntry>> {
        for (charset in listOf(Charsets.UTF_8, Charset.forName("IBM437"))) {
            var file: ZipFile? = null
            try {
                file = ZipFile(zip.toFile(), charset)
                val entries = file.entries().toList().onEach { it.name }
                return file to entries
            } catch (e: IllegalArgumentException) {
                file?.close() // nombre que no es UTF-8: probar la otra
            } catch (e: ZipException) {
                file?.close()
                if (charset != Charsets.UTF_8) throw e
            }
        }
        throw ZipException("no se pudo leer")
    }

    private fun openForOp(zip: Path): Pair<ZipFile, List<ZipEntry>> = try {
        open(zip)
    } catch (e: ZipException) {
        throw OpFailure(OpFailure.Kind.BAD_ARCHIVE, zip, e)
    } catch (e: IOException) {
        throw OpFailure.from(e, zip)
    }

    fun list(loc: ZipLocation, showHidden: Boolean, sort: SortSpec): ListResult {
        val (file, entries) = try { open(loc.zip) } catch (e: ZipException) {
            return ListResult.Error(ListError.BAD_ARCHIVE)
        } catch (e: IOException) {
            return ListResult.Error(ListError.IO)
        }
        file.use {
            val prefix = if (loc.inner.isEmpty()) "" else loc.inner + "/"
            val dirs = LinkedHashMap<String, Long>()
            val files = LinkedHashMap<String, ZipEntry>()
            var found = loc.inner.isEmpty()
            for (e in entries) {
                val name = clean(e.name) ?: continue
                if (name == loc.inner && loc.inner.isNotEmpty()) {
                    if (!e.isDirectory) return ListResult.Error(ListError.NOT_A_DIRECTORY)
                    found = true; continue
                }
                if (name.isEmpty() || !name.startsWith(prefix)) continue
                found = true
                val rest = name.substring(prefix.length)
                val slash = rest.indexOf('/')
                when {
                    slash >= 0 -> dirs.putIfAbsent(rest.substring(0, slash), 0L)
                    e.isDirectory -> dirs[rest] = e.time
                    else -> files[rest] = e
                }
            }
            if (!found) return ListResult.Error(ListError.NOT_FOUND)
            val base = if (loc.inner.isEmpty()) loc.zip else loc.zip.resolve(loc.inner)
            val out = ArrayList<FileEntry>()
            for ((name, time) in dirs) {
                out.add(FileEntry(base.resolve(name), name, true, 0, maxOf(time, 0), name.startsWith("."), EntryKind.FOLDER, inArchive = true))
            }
            for ((name, e) in files) {
                if (dirs.containsKey(name)) continue
                out.add(FileEntry(base.resolve(name), name, false, maxOf(e.size, 0), maxOf(e.time, 0), name.startsWith("."),
                    Lister.kindOf(name, false), inArchive = true))
            }
            val shown = if (showHidden) out else out.filterNot { it.isHidden }
            return ListResult.Ok(shown.sortedWith(Lister.comparator(sort)))
        }
    }

    /**
     * Extrae de [zip] lo indicado en [inners] (rutas internas; "" = todo) dentro de [destDir].
     * Cada elemento queda con su propio nombre, como al copiar; los conflictos se preguntan
     * por elemento y, al fusionar carpetas, por archivo. Devuelve lo creado en [destDir].
     */
    suspend fun extract(
        zip: Path,
        inners: List<String>,
        destDir: Path,
        onConflict: suspend (existing: Path) -> ConflictChoice,
        onProgress: (Progress) -> Unit,
    ): List<Path> = withContext(Dispatchers.IO) {
        val (file, entries) = openForOp(zip)
        file.use {
            // Por elemento de primer nivel en el destino: sus entradas con la ruta relativa a ese nivel.
            val plan = LinkedHashMap<String, MutableList<Pair<ZipEntry, String>>>()
            for (rawSel in inners) {
                val sel = clean(rawSel) ?: continue
                val base = if (sel.contains('/')) sel.substringBeforeLast('/') + "/" else ""
                for (e in entries) {
                    val name = clean(e.name) ?: continue
                    if (name.isEmpty()) continue
                    val inside = sel.isEmpty() || name == sel || name.startsWith("$sel/")
                    if (!inside) continue
                    val rel = name.substring(base.length)
                    plan.getOrPut(rel.substringBefore('/')) { ArrayList() }.add(e to rel)
                }
            }
            val ctx = coroutineContext
            var filesDone = 0
            var bytesDone = 0L
            val fileItems = plan.values.flatten().filter { !it.first.isDirectory }
            val filesTotal = fileItems.size
            val bytesTotal = fileItems.sumOf { maxOf(it.first.size, 0) }
            val report: (String) -> Unit = { onProgress(Progress(filesDone, filesTotal, bytesDone, bytesTotal, it)) }
            val root = destDir.toAbsolutePath().normalize()
            val made = ArrayList<Path>()
            for ((top, items) in plan) {
                ctx.ensureActive()
                val topIsDir = items.any { it.second.contains('/') || it.first.isDirectory }
                var topName = top
                var merge = false
                val existing = Names.existsIgnoreCase(destDir, top)
                if (existing != null) {
                    when (onConflict(existing)) {
                        ConflictChoice.SKIP -> {
                            items.filter { !it.first.isDirectory }.forEach { filesDone++; bytesDone += maxOf(it.first.size, 0) }
                            report(top); continue
                        }
                        ConflictChoice.KEEP_BOTH -> topName = Names.keepBoth(destDir, top)
                        ConflictChoice.REPLACE ->
                            if (Files.isDirectory(existing) == topIsDir) { topName = existing.fileName.toString(); merge = true }
                            else topName = Names.keepBoth(destDir, top)
                    }
                }
                for ((entry, rel) in items) {
                    ctx.ensureActive()
                    var target = destDir.resolve(topName + rel.substring(top.length))
                    if (!target.toAbsolutePath().normalize().startsWith(root)) continue
                    try {
                        if (entry.isDirectory) { Files.createDirectories(target); continue }
                        Files.createDirectories(target.parent)
                    } catch (e: IOException) { throw OpFailure.from(e, target) }
                    var replace = false
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        val choice = if (merge && rel == top) ConflictChoice.REPLACE
                        else if (merge) onConflict(target) else ConflictChoice.REPLACE
                        when (choice) {
                            ConflictChoice.SKIP -> { filesDone++; bytesDone += maxOf(entry.size, 0); report(rel); continue }
                            ConflictChoice.KEEP_BOTH -> target = target.resolveSibling(Names.keepBoth(target.parent, target.fileName.toString()))
                            ConflictChoice.REPLACE -> replace = true
                        }
                    }
                    write(file, entry, zip, target, replace, ctx) { n -> bytesDone += n; report(entry.name) }
                    filesDone++
                    report(entry.name)
                }
                made.add(destDir.resolve(topName))
            }
            if (plan.isEmpty() && inners.isNotEmpty()) throw OpFailure(OpFailure.Kind.SOURCE_GONE, zip)
            report("")
            made
        }
    }

    /** Extrae una sola entrada a [out] (para abrir un archivo de adentro). Bloqueante. */
    fun extractOne(zip: Path, inner: String, out: Path): Path {
        val (file, entries) = openForOp(zip)
        file.use {
            val entry = entries.firstOrNull { !it.isDirectory && clean(it.name) == inner }
                ?: throw OpFailure(OpFailure.Kind.SOURCE_GONE, zip)
            try { Files.createDirectories(out.parent) } catch (e: IOException) { throw OpFailure.from(e, out) }
            write(file, entry, zip, out, replace = true, ctx = null) {}
            return out
        }
    }

    private fun write(file: ZipFile, entry: ZipEntry, zip: Path, target: Path, replace: Boolean, ctx: CoroutineContext?, onBytes: (Int) -> Unit) {
        val tmp = CopyEngine.tmpFor(target)
        var ok = false
        try {
            file.getInputStream(entry).use { input ->
                Files.newOutputStream(tmp).use { out ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        ctx?.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        onBytes(n)
                    }
                }
            }
            if (entry.time > 0) runCatching { Files.setLastModifiedTime(tmp, FileTime.fromMillis(entry.time)) }
            if (replace) Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) else Files.move(tmp, target)
            ok = true
        } catch (e: ZipException) {
            throw OpFailure(OpFailure.Kind.BAD_ARCHIVE, zip, e)
        } catch (e: IOException) {
            throw OpFailure.from(e, target)
        } finally {
            if (!ok) runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Comprime [sources] (archivos o carpetas, recursivo) en [destZip], que no debe existir. */
    suspend fun compress(sources: List<Path>, destZip: Path, onProgress: (Progress) -> Unit): Path = withContext(Dispatchers.IO) {
        if (Files.exists(destZip, LinkOption.NOFOLLOW_LINKS)) throw OpFailure(OpFailure.Kind.ALREADY_EXISTS, destZip)
        val tmp = CopyEngine.tmpFor(destZip)
        // Qué va: (ruta, nombre dentro del zip, es carpeta).
        val items = ArrayList<Triple<Path, String, Boolean>>()
        for (s in sources) {
            if (!Files.exists(s, LinkOption.NOFOLLOW_LINKS)) throw OpFailure(OpFailure.Kind.SOURCE_GONE, s)
            val base = s.parent
            try {
                Files.walk(s).use { st ->
                    st.forEach { p ->
                        if (p == tmp || p == destZip) return@forEach
                        val name = (if (base == null) p else base.relativize(p)).joinToString("/") { it.toString() }
                        val dir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                        items.add(Triple(p, if (dir) "$name/" else name, dir))
                    }
                }
            } catch (e: IOException) { throw OpFailure.from(e, s) }
        }
        items.sortBy { it.second }
        val filesTotal = items.count { !it.third }
        val bytesTotal = items.filter { !it.third }.sumOf { runCatching { Files.size(it.first) }.getOrDefault(0L) }
        var filesDone = 0
        var bytesDone = 0L
        val ctx = coroutineContext
        var ok = false
        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW))).use { out ->
                val buf = ByteArray(BUFFER)
                for ((path, name, dir) in items) {
                    ctx.ensureActive()
                    val entry = ZipEntry(name)
                    runCatching { entry.time = Files.getLastModifiedTime(path).toMillis() }
                    out.putNextEntry(entry)
                    if (!dir) {
                        Files.newInputStream(path).use { input ->
                            while (true) {
                                ctx.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                bytesDone += n
                                onProgress(Progress(filesDone, filesTotal, bytesDone, bytesTotal, path.fileName.toString()))
                            }
                        }
                        filesDone++
                        onProgress(Progress(filesDone, filesTotal, bytesDone, bytesTotal, path.fileName.toString()))
                    }
                    out.closeEntry()
                }
            }
            Files.move(tmp, destZip)
            ok = true
        } catch (e: FileAlreadyExistsException) {
            throw OpFailure(OpFailure.Kind.ALREADY_EXISTS, destZip, e)
        } catch (e: IOException) {
            throw OpFailure.from(e, destZip)
        } finally {
            if (!ok) runCatching { Files.deleteIfExists(tmp) }
        }
        destZip
    }
}
