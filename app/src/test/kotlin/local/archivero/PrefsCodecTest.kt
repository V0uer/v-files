package local.archivero

import local.archivero.data.Favorite
import local.archivero.data.PrefsCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrefsCodecTest {
    @Test fun ida_y_vuelta_con_caracteres_raros() {
        val list = listOf(
            Favorite("/storage/emulated/0/Download", "Descargas"),
            Favorite("/sdcard/a|b/c", "Con | barra"),
            Favorite("/sdcard/x", "Dos\nlíneas 🎮"),
        )
        assertEquals(list, PrefsCodec.decodeFavorites(PrefsCodec.encodeFavorites(list)))
    }

    @Test fun lista_vacia_ida_y_vuelta() =
        assertEquals(emptyList<Favorite>(), PrefsCodec.decodeFavorites(PrefsCodec.encodeFavorites(emptyList())))

    @Test fun null_y_corrupto_devuelven_vacio() {
        assertEquals(emptyList<Favorite>(), PrefsCodec.decodeFavorites(null))
        assertEquals(emptyList<Favorite>(), PrefsCodec.decodeFavorites("esto no es una lista"))
        assertEquals(emptyList<Favorite>(), PrefsCodec.decodeFavorites("%ZZ|%ZZ"))
    }

    @Test fun linea_corrupta_no_tira_las_buenas() {
        val raw = PrefsCodec.encodeFavorites(listOf(Favorite("/a", "A"))) + "\nbasura\n"
        assertEquals(listOf(Favorite("/a", "A")), PrefsCodec.decodeFavorites(raw))
    }
}
