package local.archivero.data

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import local.archivero.core.SortKey
import local.archivero.core.SortSpec
import java.net.URLDecoder
import java.net.URLEncoder

data class Favorite(val path: String, val label: String)

data class Settings(
    val startPath: String,
    val favorites: List<Favorite>,
    val showHidden: Boolean,
    val sort: SortSpec,
    val gridView: Boolean,
    val dualPane: Boolean,
    val secondPanePath: String?,
    /** Si la división entre los dos paneles se puede arrastrar. */
    val splitAdjustable: Boolean = true,
    /** Fracción que ocupa el primer panel (0.5 = mitad y mitad). */
    val split: Float = 0.5f,
)

object PrefsCodec {
    fun encodeFavorites(list: List<Favorite>): String =
        list.joinToString("\n") { enc(it.path) + "|" + enc(it.label) }

    /** Tolerante: una línea que no se entiende se salta; nunca lanza. */
    fun decodeFavorites(raw: String?): List<Favorite> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 2) return@mapNotNull null
            try {
                val path = dec(parts[0]); val label = dec(parts[1])
                if (path.isEmpty()) null else Favorite(path, label)
            } catch (e: IllegalArgumentException) { null }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
}

/** Mueve un elemento de [from] a [to]; con índices fuera de rango devuelve la lista igual. */
fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> {
    if (from !in list.indices || to !in list.indices || from == to) return list
    val m = list.toMutableList()
    m.add(to, m.removeAt(from))
    return m
}

private val Context.store by preferencesDataStore("ajustes")

class Prefs(private val context: Context) {
    private object K {
        val start = stringPreferencesKey("start_path")
        val favorites = stringPreferencesKey("favorites")
        val hidden = booleanPreferencesKey("show_hidden")
        val sortKey = stringPreferencesKey("sort_key")
        val sortAsc = booleanPreferencesKey("sort_asc")
        val grid = booleanPreferencesKey("grid")
        val dual = booleanPreferencesKey("dual")
        val second = stringPreferencesKey("second_path")
        val splitAdjustable = booleanPreferencesKey("split_adjustable")
        val split = floatPreferencesKey("split")
    }

    val defaultStart: String =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

    val settings: Flow<Settings> = context.store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    private fun read(p: Preferences) = Settings(
        startPath = p[K.start] ?: defaultStart,
        favorites = if (p.contains(K.favorites)) PrefsCodec.decodeFavorites(p[K.favorites])
        else listOf(Favorite(defaultStart, "Descargas")),
        showHidden = p[K.hidden] ?: false,
        sort = SortSpec(
            runCatching { SortKey.valueOf(p[K.sortKey] ?: "NAME") }.getOrDefault(SortKey.NAME),
            p[K.sortAsc] ?: true,
        ),
        gridView = p[K.grid] ?: false,
        dualPane = p[K.dual] ?: false,
        secondPanePath = p[K.second],
        splitAdjustable = p[K.splitAdjustable] ?: true,
        split = p[K.split] ?: 0.5f,
    )

    suspend fun update(transform: (Settings) -> Settings) {
        context.store.edit { p ->
            val s = transform(read(p))
            p[K.start] = s.startPath
            p[K.favorites] = PrefsCodec.encodeFavorites(s.favorites)
            p[K.hidden] = s.showHidden
            p[K.sortKey] = s.sort.key.name
            p[K.sortAsc] = s.sort.ascending
            p[K.grid] = s.gridView
            p[K.dual] = s.dualPane
            if (s.secondPanePath != null) p[K.second] = s.secondPanePath else p.remove(K.second)
            p[K.splitAdjustable] = s.splitAdjustable
            p[K.split] = s.split
        }
    }
}
