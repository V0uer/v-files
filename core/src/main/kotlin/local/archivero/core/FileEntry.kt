package local.archivero.core

import java.nio.file.Path

enum class EntryKind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, TEXT, APK, OTHER }

data class FileEntry(
    val path: Path,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedMillis: Long,
    val isHidden: Boolean,
    val kind: EntryKind,
)

enum class SortKey { NAME, DATE, SIZE, TYPE }

data class SortSpec(val key: SortKey = SortKey.NAME, val ascending: Boolean = true)

sealed interface ListResult {
    data class Ok(val entries: List<FileEntry>) : ListResult
    data class Error(val reason: ListError) : ListResult
}

enum class ListError { NOT_FOUND, NO_ACCESS, NOT_A_DIRECTORY, IO }
