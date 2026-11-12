# Archivero Etapa 1 (el explorador) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un APK instalable en el AYN Thor con explorador de uno y dos paneles, favoritos, operaciones de archivo confiables, búsqueda y manejo completo por control físico.

**Architecture:** Dos módulos Gradle. `:core` es Kotlin puro (JVM), sin Android: listado, orden, motor de operaciones, papelera, búsqueda; se prueba con carpetas temporales. `:app` es Android + Jetpack Compose, una sola actividad, que solo observa `StateFlow`s de `:core` y los pinta. Las operaciones corren en un servicio en primer plano.

**Tech Stack:** Kotlin 2.0.x, Gradle 8.9+ con wrapper, AGP 8.7.x, Jetpack Compose (BOM 2024.10+), Material 3, kotlinx-coroutines, DataStore Preferences, Coil (miniaturas), JUnit 5 + kotlinx-coroutines-test en `:core`. Si una versión no resuelve, usar la estable más reciente de la misma línea.

**Spec:** `docs/superpowers/specs/2026-11-12-archivero-design.md` (boceto: `docs/superpowers/specs/boceto-layout.html`). Las etapas 2 a 4 del spec quedan fuera de este plan.

## Global Constraints

- minSdk 30, compileSdk/targetSdk 35. Dispositivo objetivo: AYN Thor, Android 13.
- `applicationId` y paquete raíz: `local.archivero`. Nombre visible: "Archivero".
- El manifiesto NO declara `android.permission.INTERNET` en esta etapa. Sin Google Play Services, sin analítica, sin anuncios.
- Permiso de archivos: `MANAGE_EXTERNAL_STORAGE`; acceso con `java.io`/`java.nio`.
- Identidad de git ya configurada en el repo (`V0uer`, correo noreply). Ningún nombre real ni correo personal en código, commits o documentos. Textos de la interfaz en español.
- Diseño, iconos y nombre propios: no copiar recursos de ninguna app comercial.
- Rendimiento: arranque en frío a lista visible < 1 s; carpeta de 5,000 elementos, primeros elementos < 300 ms.
- Compilar con JDK 17. Comandos desde Git Bash en `D:\Archivero`.
- Cada commit termina con `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.

## Review Focus

1. **Se llena el almacenamiento a media copia:** se borra el temporal, el origen queda intacto y la operación termina en `Failed` con mensaje claro (Task 3).
2. **Copiar o mover una carpeta dentro de sí misma o de un descendiente:** se rechaza antes de tocar nada (Task 3).
3. **Almacenamiento que no distingue mayúsculas (interno emulado, SD exFAT):** renombrar `foto.jpg` a `Foto.JPG` funciona; el conflicto de nombres se detecta sin distinguir mayúsculas (Tasks 3 y 4).
4. **Carpeta ilegible o que desaparece (`Android/data`, SD retirada):** el listado devuelve error y la pantalla muestra un estado de error con "Reintentar", nunca se cierra la app (Tasks 2 y 9).
5. **Cancelar un "mover" entre volúmenes a la mitad:** ningún origen se borra si su copia no terminó y verificó (Task 4).

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
core/src/main/kotlin/local/archivero/core/
  FileEntry.kt        modelo + tipos
  Lister.kt           listar y ordenar
  Names.kt            conflictos de nombre, validación
  CopyEngine.kt       copia segura con progreso
  FileOps.kt          mover, renombrar, borrar, crear carpeta, propiedades
  Trash.kt            papelera
  OperationQueue.kt   cola, estados, conflictos
  Search.kt           búsqueda recursiva
core/src/test/kotlin/local/archivero/core/   un *Test.kt por archivo
app/src/main/kotlin/local/archivero/
  MainActivity.kt, ArchiveroApp.kt
  data/Prefs.kt, data/Volumes.kt
  ops/OpsService.kt
  ui/Theme.kt, ui/PermissionGate.kt, ui/BrowserScreen.kt, ui/PaneState.kt,
  ui/PaneView.kt, ui/TopBar.kt, ui/Sidebar.kt, ui/ActionBar.kt, ui/Dialogs.kt,
  ui/OpsPanel.kt, ui/TrashScreen.kt, ui/SearchView.kt, ui/Gamepad.kt
```

---

### Task 1: Herramientas y esqueleto del proyecto

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, wrapper de Gradle, `core/build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/local/archivero/MainActivity.kt`, `core/src/test/kotlin/local/archivero/core/SmokeTest.kt`, `README.md`

**Interfaces:**
- Produces: `./gradlew :core:test` y `./gradlew :app:assembleDebug` funcionando; APK en `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 1:** Instalar JDK 17 (Temurin) y Android command-line tools en `D:\Android\sdk` (`platforms;android-35`, `build-tools;35.0.0`, `platform-tools`). Aceptar licencias con `sdkmanager --licenses`. Escribir `local.properties` con `sdk.dir` (está en `.gitignore`).
- [ ] **Step 2:** Crear los dos módulos. `:core` con plugin `org.jetbrains.kotlin.jvm`, JUnit 5. `:app` con `com.android.application`, Compose, dependencia `implementation(project(":core"))`. `MainActivity` muestra el texto "Archivero".
- [ ] **Step 3:** `SmokeTest` con `assertEquals(4, 2 + 2)`. Run: `./gradlew :core:test` → PASS.
- [ ] **Step 4:** Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL y existe el APK. Verificar con `aapt dump permissions` que no aparece `INTERNET`.
- [ ] **Step 5:** README con cómo compilar. Commit: `chore: esqueleto de proyecto y herramientas`.

### Task 2: Modelo, listado y orden

**Files:** Create `core/.../FileEntry.kt`, `Lister.kt`; Test `ListerTest.kt`

**Interfaces — Produces:**
```kotlin
enum class EntryKind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, TEXT, APK, OTHER }
data class FileEntry(val path: java.nio.file.Path, val name: String, val isDirectory: Boolean,
                     val size: Long, val modifiedMillis: Long, val isHidden: Boolean, val kind: EntryKind)
enum class SortKey { NAME, DATE, SIZE, TYPE }
data class SortSpec(val key: SortKey = SortKey.NAME, val ascending: Boolean = true)
sealed interface ListResult { data class Ok(val entries: List<FileEntry>) : ListResult
                              data class Error(val reason: ListError) : ListResult }
enum class ListError { NOT_FOUND, NO_ACCESS, NOT_A_DIRECTORY, IO }
object Lister {
  fun list(dir: Path, showHidden: Boolean, sort: SortSpec): ListResult   // bloqueante; se llama en Dispatchers.IO
  fun kindOf(name: String, isDirectory: Boolean): EntryKind              // por extensión
}
```
Orden: carpetas siempre primero; nombre con orden natural sin distinguir mayúsculas (`a2` antes de `a10`). `isHidden` = nombre que empieza con `.`. Tamaño de carpeta = 0.

- [ ] **Step 1: Tests que fallan** en `ListerTest` con `@TempDir`:
  - `carpetas_primero_y_orden_natural`: crea `b.txt`, `a10.txt`, `a2.txt`, carpeta `zeta` → nombres `[zeta, a2.txt, a10.txt, b.txt]`.
  - `ocultos_se_filtran`: `.x` no aparece con `showHidden=false`, sí con `true`.
  - `orden_por_tamano_descendente` y `orden_por_fecha`.
  - `carpeta_inexistente_devuelve_NOT_FOUND`; `archivo_devuelve_NOT_A_DIRECTORY`.
  - `sin_permiso_devuelve_NO_ACCESS` (carpeta con permisos retirados; `assumeTrue` si el SO no lo permite).
  - `kindOf` para `.jpg`→IMAGE, `.zip`/`.7z`/`.rar`→ARCHIVE, `.nsp`→OTHER, `.apk`→APK.
  - `cinco_mil_archivos_en_menos_de_300ms`: 5,000 archivos vacíos, `list` < 300 ms (medido tras un calentamiento).
- [ ] **Step 2:** Run `./gradlew :core:test --tests '*ListerTest*'` → FAIL (no compila).
- [ ] **Step 3:** Implementar con `Files.newDirectoryStream` + `Files.readAttributes` (una lectura de atributos por entrada). Capturar `NoSuchFileException`, `AccessDeniedException`, `NotDirectoryException`, `IOException`.
- [ ] **Step 4:** Run → PASS. **Step 5:** Commit `feat(core): listado y orden`.

### Task 3: Nombres y copia segura

**Files:** Create `Names.kt`, `CopyEngine.kt`; Test `NamesTest.kt`, `CopyEngineTest.kt`

**Interfaces — Produces:**
```kotlin
object Names {
  fun isValid(name: String): Boolean                     // no vacío, sin '/', sin NUL, no "." ni ".."
  fun existsIgnoreCase(dir: Path, name: String): Path?   // devuelve el existente o null
  fun keepBoth(dir: Path, name: String): String          // "foto.jpg" -> "foto (2).jpg", "(3)"...; carpetas sin extensión
  fun isSameOrDescendant(parent: Path, candidate: Path): Boolean
}
enum class ConflictChoice { SKIP, REPLACE, KEEP_BOTH }
data class Progress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long, val currentName: String)
class OpFailure(val kind: Kind, val path: Path, cause: Throwable? = null) : Exception() {
  enum class Kind { NO_SPACE, NO_ACCESS, SOURCE_GONE, INTO_ITSELF, FILE_TOO_LARGE, IO }
}
class CopyEngine(private val bufferSize: Int = 1 shl 20) {
  /** Copia archivos o carpetas (recursivo) a destDir. Lanza OpFailure; respeta la cancelación de la corrutina. */
  suspend fun copy(sources: List<Path>, destDir: Path,
                   onConflict: suspend (existing: Path) -> ConflictChoice,
                   onProgress: (Progress) -> Unit): List<Path>   // rutas creadas, sin las omitidas
}
```
Algoritmo por archivo: escribir a `<destino>/.<nombre>.archivero-tmp`, comprobar `size(tmp) == size(origen)`, luego `Files.move(tmp, final, ATOMIC_MOVE)` (con REPLACE solo si la elección fue REPLACE). Ante cualquier fallo o cancelación, borrar el temporal. `bytesTotal` se calcula antes de empezar recorriendo los orígenes. Conservar la fecha de modificación. `IOException` con mensaje ENOSPC → `NO_SPACE`; EFBIG → `FILE_TOO_LARGE`.

- [ ] **Step 1: Tests que fallan:**
  - `NamesTest`: `keepBoth` da `foto (2).jpg` y luego `foto (3).jpg`; `existsIgnoreCase(dir,"FOTO.JPG")` encuentra `foto.jpg`; `isSameOrDescendant(/a, /a/b/c)` true, `(/a, /ab)` false; `isValid("")`, `"a/b"`, `".."` false.
  - `copia_archivo_conserva_contenido_y_fecha`.
  - `copia_carpeta_recursiva` (3 niveles, incluye carpeta vacía).
  - `conflicto_SKIP_no_toca_destino`, `conflicto_REPLACE_sobrescribe`, `conflicto_KEEP_BOTH_crea_nombre_2`.
  - `conflicto_se_detecta_sin_distinguir_mayusculas` (destino tiene `Foto.JPG`, origen `foto.jpg` → se llama `onConflict`).
  - `copiar_carpeta_dentro_de_si_misma_falla_INTO_ITSELF_sin_crear_nada`.
  - `cancelar_a_media_copia_no_deja_temporal_ni_archivo_final` (archivo de 64 MB, cancelar en el primer `onProgress`; el destino queda sin entradas nuevas).
  - `fallo_de_escritura_borra_temporal_y_origen_intacto`: inyectar el fallo con un parámetro interno de prueba `openOutput: (Path) -> OutputStream` que lanza `IOException("ENOSPC")` tras N bytes → `OpFailure.Kind.NO_SPACE`, sin `.archivero-tmp` en destino, origen con su tamaño original.
  - `progreso_termina_en_totales` (último `Progress` tiene `filesDone==filesTotal` y `bytesDone==bytesTotal`).
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implementar. **Step 4:** Run → PASS.
- [ ] **Step 5:** Prueba de mutación: comentar el borrado del temporal y confirmar que fallan los dos tests de limpieza; restaurar. Commit `feat(core): copia segura`.

### Task 4: Mover, renombrar, borrar, crear carpeta, propiedades

**Files:** Create `FileOps.kt`; Test `FileOpsTest.kt`

**Interfaces:**
- Consumes: `CopyEngine.copy`, `Names`, `ConflictChoice`, `Progress`, `OpFailure`.
- Produces:
```kotlin
class FileOps(private val copier: CopyEngine, private val sameVolume: (Path, Path) -> Boolean = { a, b -> Files.getFileStore(a) == Files.getFileStore(b) }) {
  suspend fun move(sources: List<Path>, destDir: Path, onConflict: suspend (Path) -> ConflictChoice, onProgress: (Progress) -> Unit): List<Path>
  fun rename(target: Path, newName: String): Path            // lanza IllegalArgumentException si !Names.isValid; OpFailure si ya existe otro
  fun createFolder(parent: Path, name: String): Path
  suspend fun deletePermanently(targets: List<Path>, onProgress: (Progress) -> Unit)
  suspend fun properties(target: Path): Properties           // recursivo, cancelable
}
data class Properties(val path: Path, val totalBytes: Long, val fileCount: Int, val folderCount: Int, val modifiedMillis: Long)
```
`move`: si `sameVolume`, `Files.move` (renombrado). Si no, por cada origen de primer nivel: copiar, y solo si su copia terminó, borrar ese origen. `rename` que solo cambia mayúsculas: pasar por un nombre intermedio.

- [ ] **Step 1: Tests que fallan:**
  - `mover_mismo_volumen_es_renombrado` (el origen ya no existe, el destino sí).
  - `mover_entre_volumenes_copia_y_borra` (con `sameVolume = { _, _ -> false }`).
  - `cancelar_mover_entre_volumenes_no_borra_origenes_sin_copiar`: 3 archivos, cancelar durante el segundo → el primero está en destino y no en origen; el segundo y tercero siguen completos en origen; no hay temporales.
  - `mover_carpeta_dentro_de_si_misma_falla_INTO_ITSELF`.
  - `renombrar_solo_mayusculas_funciona` (`foto.jpg` → `Foto.JPG`, queda un solo archivo con el nombre nuevo).
  - `renombrar_a_nombre_existente_falla_y_no_pisa`.
  - `renombrar_nombre_invalido_lanza_IllegalArgumentException`.
  - `borrar_definitivo_carpeta_con_contenido`.
  - `propiedades_suma_recursiva` (bytes, archivos y carpetas exactos).
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS. **Step 5:** Commit `feat(core): mover, renombrar, borrar, propiedades`.

### Task 5: Papelera

**Files:** Create `Trash.kt`; Test `TrashTest.kt`

**Interfaces:**
- Consumes: `FileOps`.
- Produces:
```kotlin
data class TrashItem(val id: String, val originalPath: Path, val deletedAtMillis: Long, val sizeBytes: Long, val isDirectory: Boolean)
class Trash(private val volumeRootOf: (Path) -> Path, private val now: () -> Long = System::currentTimeMillis) {
  companion object { const val DIR_NAME = ".archivero-papelera"; const val RETENTION_DAYS = 30L }
  fun moveToTrash(targets: List<Path>): List<TrashItem>     // renombrado dentro del mismo volumen
  fun list(volumeRoot: Path): List<TrashItem>               // más reciente primero
  fun restore(item: TrashItem, volumeRoot: Path): Path      // si la ruta original está ocupada, usa Names.keepBoth
  fun purge(item: TrashItem, volumeRoot: Path)
  fun purgeExpired(volumeRoot: Path): Int                   // borra los de más de 30 días
}
```
Estructura: `<raízDelVolumen>/.archivero-papelera/<id>/payload` más `<id>/meta` (texto: ruta original, fecha, tamaño, esCarpeta). `id` = UUID. Crear un `.nomedia` en la carpeta de papelera.

- [ ] **Step 1: Tests que fallan:** `a_papelera_y_restaurar_devuelve_contenido_identico`; `restaurar_con_ruta_ocupada_crea_nombre_2`; `restaurar_recrea_carpeta_padre_si_ya_no_existe`; `purgeExpired_borra_31_dias_y_conserva_29` (con `now` inyectado); `list_ignora_entradas_con_meta_corrupta_sin_lanzar`; `la_papelera_no_aparece_con_showHidden_false` (vía `Lister`).
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS. **Step 5:** Commit `feat(core): papelera`.

### Task 6: Cola de operaciones

**Files:** Create `OperationQueue.kt`; Test `OperationQueueTest.kt`

**Interfaces:**
- Consumes: `CopyEngine`, `FileOps`, `Trash`, `Progress`, `ConflictChoice`, `OpFailure`.
- Produces:
```kotlin
sealed interface OpRequest {
  data class Copy(val sources: List<Path>, val destDir: Path) : OpRequest
  data class Move(val sources: List<Path>, val destDir: Path) : OpRequest
  data class ToTrash(val targets: List<Path>) : OpRequest
  data class Delete(val targets: List<Path>) : OpRequest
}
enum class OpStatus { QUEUED, RUNNING, WAITING_CONFLICT, DONE, CANCELLED, FAILED }
data class OpState(val id: Long, val request: OpRequest, val status: OpStatus, val progress: Progress?,
                   val conflict: Path? = null, val failure: OpFailure.Kind? = null, val failurePath: Path? = null)
class OperationQueue(scope: CoroutineScope, copier: CopyEngine, ops: FileOps, trash: Trash) {
  val states: StateFlow<List<OpState>>          // todas, en orden de alta
  val finished: SharedFlow<OpState>             // emite al pasar a DONE/CANCELLED/FAILED
  fun enqueue(request: OpRequest): Long
  fun cancel(id: Long)
  fun resolveConflict(id: Long, choice: ConflictChoice, applyToAll: Boolean)
  fun clearFinished()
}
```
Una operación a la vez, en orden. `applyToAll=true` fija la elección para el resto de esa operación. El progreso se publica como máximo cada 100 ms.

- [ ] **Step 1: Tests que fallan** (con `runTest`): `copia_pasa_por_QUEUED_RUNNING_DONE`; `dos_operaciones_corren_en_orden`; `conflicto_pone_WAITING_CONFLICT_y_resolver_continua`; `applyToAll_no_vuelve_a_preguntar` (3 conflictos, una sola espera); `cancelar_en_espera_de_conflicto_termina_CANCELLED`; `fallo_deja_FAILED_con_kind_y_la_siguiente_operacion_corre`; `finished_emite_una_vez_por_operacion`.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** PASS. **Step 5:** Commit `feat(core): cola de operaciones`.

### Task 7: Búsqueda

**Files:** Create `Search.kt`; Test `SearchTest.kt`

**Interfaces — Produces:**
```kotlin
object Search {
  /** Emite coincidencias conforme las encuentra. Sin distinguir mayúsculas ni acentos; coincide por subcadena del nombre. */
  fun byName(root: Path, query: String, includeHidden: Boolean): Flow<FileEntry>
}
```
Recorre en anchura, ignora carpetas ilegibles sin fallar, no sigue enlaces simbólicos, nunca entra a `.archivero-papelera`.

- [ ] **Step 1: Tests que fallan:** `encuentra_en_subcarpetas`; `ignora_mayusculas_y_acentos` (`"cancion"` encuentra `Canción.mp3`); `consulta_vacia_no_emite`; `carpeta_ilegible_no_interrumpe`; `no_entra_a_la_papelera`; `cancelar_la_recoleccion_detiene_el_recorrido`.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar con `flow { }` + `flowOn(Dispatchers.IO)`. **Step 4:** PASS. **Step 5:** Commit `feat(core): búsqueda`.

### Task 8: Permiso, volúmenes y preferencias

**Files:** Create `app/.../data/Prefs.kt`, `data/Volumes.kt`, `ui/PermissionGate.kt`, `ui/Theme.kt`, `ArchiveroApp.kt`; Modify `AndroidManifest.xml`, `MainActivity.kt`; Test `app/src/test/.../PrefsCodecTest.kt`

**Interfaces — Produces:**
```kotlin
data class Volume(val root: Path, val label: String, val isRemovable: Boolean, val freeBytes: Long, val totalBytes: Long)
class Volumes(context: Context) { val volumes: StateFlow<List<Volume>>; fun refresh(); fun rootOf(path: Path): Path }
data class Favorite(val path: String, val label: String)
data class Settings(val startPath: String, val favorites: List<Favorite>, val showHidden: Boolean,
                    val sort: SortSpec, val gridView: Boolean, val dualPane: Boolean, val secondPanePath: String?)
class Prefs(context: Context) { val settings: Flow<Settings>; suspend fun update(transform: (Settings) -> Settings) }
object PrefsCodec { fun encodeFavorites(list: List<Favorite>): String; fun decodeFavorites(raw: String?): List<Favorite> }
@Composable fun PermissionGate(content: @Composable () -> Unit)
```
Valores por defecto: `startPath` = `Environment.DIRECTORY_DOWNLOADS` del almacenamiento interno; favoritos = `[Descargas]`. `Volumes` usa `StorageManager.storageVolumes` y se refresca al montar/desmontar. `PermissionGate` muestra una pantalla con un botón que abre `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` y vuelve a comprobar en `onResume`. Tema oscuro con los colores del boceto (fondo `#14171c`, superficie `#1b1f26`, acento `#3b82f6`).

- [ ] **Step 1: Tests que fallan** (JVM): `PrefsCodec` ida y vuelta con etiquetas que contienen `|`, saltos de línea y emojis; `decodeFavorites(null)` y texto corrupto devuelven lista vacía.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar todo lo de la tarea. **Step 4:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat(app): permiso, volúmenes y preferencias`.

### Task 9: Un panel: barra superior, migas y lista

**Files:** Create `ui/PaneState.kt`, `ui/PaneView.kt`, `ui/TopBar.kt`, `ui/BrowserScreen.kt`; Test `app/src/test/.../PaneStateTest.kt`

**Interfaces:**
- Consumes: `Lister`, `ListResult`, `SortSpec`, `Prefs`, `Volumes`.
- Produces:
```kotlin
sealed interface PaneContent { data object Loading : PaneContent
  data class Ready(val entries: List<FileEntry>) : PaneContent
  data class Failed(val reason: ListError) : PaneContent }
class PaneState(scope: CoroutineScope, startPath: Path, private val list: suspend (Path) -> ListResult) {
  val path: StateFlow<Path>; val content: StateFlow<PaneContent>; val selection: StateFlow<Set<Path>>
  fun navigate(to: Path); fun up(): Boolean; fun back(): Boolean      // back usa historial; false si está vacío
  fun refresh(); fun toggleSelect(p: Path); fun selectAll(); fun clearSelection()
  fun breadcrumbs(volumeRoot: Path, volumeLabel: String): List<Pair<String, Path>>
}
@Composable fun PaneView(state: PaneState, active: Boolean, grid: Boolean, onOpenFile: (FileEntry) -> Unit, modifier: Modifier)
@Composable fun TopBar(...)   // atrás, subir, migas tocables, buscar, "2 paneles", menú (orden, vista, ocultos, papelera)
```
Lista con `LazyColumn`/`LazyVerticalGrid` y `key = path`. Fila: icono por `EntryKind`, nombre, tamaño legible, fecha corta. Miniaturas solo para IMAGE, con Coil, tamaño fijo. Estado `Failed` muestra mensaje según `ListError` y botón "Reintentar". Carpeta vacía muestra "Carpeta vacía". Tocar carpeta navega; tocar archivo llama `onOpenFile`; pulsación larga selecciona. Navegar limpia la selección. No se puede subir por encima de la raíz del volumen.

- [ ] **Step 1: Tests que fallan** (`PaneState` con `list` falso): `navegar_carga_y_publica_Ready`; `back_regresa_a_la_carpeta_anterior`; `up_en_la_raiz_del_volumen_devuelve_false`; `error_de_listado_publica_Failed_y_refresh_reintenta`; `navegar_limpia_la_seleccion`; `respuesta_lenta_de_una_carpeta_anterior_no_pisa_la_actual` (dos `navigate` seguidos, la primera respuesta llega tarde); `breadcrumbs_de_Interno_Download_Smash`.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar estado y composables; `BrowserScreen` arranca en `settings.startPath`. **Step 4:** tests PASS y `assembleDebug` OK.
- [ ] **Step 5:** Commit `feat(app): panel único con navegación`.

### Task 10: Panel de favoritos y almacenamiento

**Files:** Create `ui/Sidebar.kt`; Modify `ui/BrowserScreen.kt`

**Interfaces:**
- Consumes: `Prefs`, `Volumes`, `PaneState.navigate`.
- Produces: `@Composable fun Sidebar(compact: Boolean, favorites: List<Favorite>, volumes: List<Volume>, currentPath: Path, onNavigate: (Path) -> Unit, onAddCurrent: () -> Unit, onRemove: (Favorite) -> Unit, onMove: (from: Int, to: Int) -> Unit)`

Secciones "Favoritos" y "Almacenamiento" (etiqueta + "N GB libres"). "＋ Agregar" guarda la carpeta actual del panel activo. Pulsación larga en un favorito: quitar, subir, bajar. `compact=true` muestra solo iconos (ancho 48 dp); completo, 180 dp. Un favorito cuya carpeta ya no existe se muestra atenuado y al tocarlo ofrece quitarlo. Sin sección "Red" en esta etapa.

- [ ] **Step 1:** Implementar. **Step 2:** `assembleDebug` OK.
- [ ] **Step 3:** Añadir a `PrefsCodecTest`: `mover_favorito_reordena` sobre una función pura `moveItem(list, from, to)` en `Prefs.kt`; índices fuera de rango devuelven la lista igual. Run → PASS.
- [ ] **Step 4:** Commit `feat(app): favoritos y almacenamiento`.

### Task 11: Dos paneles, barra de acciones y diálogos

**Files:** Create `ui/ActionBar.kt`, `ui/Dialogs.kt`; Modify `ui/BrowserScreen.kt`, `AndroidManifest.xml` (FileProvider), `app/src/main/res/xml/file_paths.xml`; Test `app/src/test/.../BrowserLogicTest.kt`

**Interfaces:**
- Consumes: `PaneState`, `OperationQueue.enqueue`, `OpRequest`, `FileOps.rename/createFolder/properties`, `Names.isValid`.
- Produces:
```kotlin
class BrowserState(val left: PaneState, val right: PaneState) {
  val dual: StateFlow<Boolean>; val activeIsLeft: StateFlow<Boolean>
  val active: PaneState; val other: PaneState
  fun toggleDual(); fun setActive(left: Boolean)
  fun actionsFor(selectionCount: Int, dual: Boolean): List<Action>
}
enum class Action { COPY_TO_OTHER, MOVE_TO_OTHER, COPY, CUT, PASTE, RENAME, TRASH, DELETE, SHARE, OPEN_WITH, PROPERTIES, NEW_FOLDER, SELECT_ALL }
```
Dos paneles: reparto 50/50, el activo con borde superior de acento; tocar un panel lo activa; la barra superior refleja el panel activo; la `Sidebar` pasa a `compact`. El estado de dos paneles y la ruta del segundo se guardan en `Prefs`. Con un solo panel, copiar/mover usa portapapeles interno (COPY/CUT y luego PASTE en otra carpeta). RENAME solo con 1 seleccionado. Diálogos: renombrar (nombre preseleccionado sin la extensión; botón deshabilitado si `!Names.isValid`), nueva carpeta, confirmar borrado definitivo, propiedades (muestra "calculando…" mientras suma), conflicto (Omitir / Reemplazar / Conservar ambos + casilla "Aplicar a todos"). "Abrir con" y "Compartir" mediante `FileProvider` con `Intent.ACTION_VIEW`/`ACTION_SEND` y tipo MIME por extensión; si ninguna app lo abre, mensaje "No hay app para abrir este archivo".

- [ ] **Step 1: Tests que fallan:** `actionsFor(0, dual=false)` = `[NEW_FOLDER, PASTE, SELECT_ALL]`; `actionsFor(1, dual=true)` contiene `COPY_TO_OTHER`, `MOVE_TO_OTHER`, `RENAME`; `actionsFor(3, dual=true)` no contiene `RENAME` ni `OPEN_WITH`; `actionsFor(2, dual=false)` no contiene `COPY_TO_OTHER`; `toggleDual` conserva la ruta del panel izquierdo.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** tests PASS, `assembleDebug` OK.
- [ ] **Step 5:** Commit `feat(app): dos paneles y acciones`.

### Task 12: Servicio de operaciones, progreso y papelera

**Files:** Create `ops/OpsService.kt`, `ui/OpsPanel.kt`, `ui/TrashScreen.kt`; Modify `ArchiveroApp.kt`, `AndroidManifest.xml`, `ui/BrowserScreen.kt`

**Interfaces:**
- Consumes: `OperationQueue` (única instancia, creada en `ArchiveroApp`), `Trash`, `Volumes.rootOf`.
- Produces: `OpsService` (foreground, tipo `dataSync`) que arranca al encolar y se detiene cuando no quedan operaciones activas; `@Composable fun OpsPanel(states: List<OpState>, onCancel: (Long) -> Unit, onResolve: (Long, ConflictChoice, Boolean) -> Unit)`; `@Composable fun TrashScreen(...)`.

Permisos: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (si se niega, la operación corre igual). Notificación con nombre, porcentaje y acción "Cancelar". `OpsPanel`: tira inferior plegable con barra de progreso, "N de M · X MB/s" y cancelar; el diálogo de conflicto aparece cuando hay un `WAITING_CONFLICT`. Mensajes de fallo en español por `OpFailure.Kind` (NO_SPACE: "No hay espacio suficiente"; FILE_TOO_LARGE: "El archivo es demasiado grande para este almacenamiento"; NO_ACCESS: "Sin permiso para escribir aquí"; SOURCE_GONE; INTO_ITSELF: "No se puede copiar una carpeta dentro de sí misma"; IO). Al emitir `finished`, refrescar los paneles cuya ruta fue afectada. `TrashScreen`: lista por volumen con restaurar, borrar definitivo y vaciar; al abrir la app se llama `purgeExpired` en cada volumen. "Borrar" en la barra de acciones encola `ToTrash`; "Borrar definitivo" está en el menú y pide confirmación.

- [ ] **Step 1:** Implementar. **Step 2:** `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` → PASS.
- [ ] **Step 3:** Commit `feat(app): servicio de operaciones y papelera`.

### Task 13: Búsqueda en la interfaz

**Files:** Create `ui/SearchView.kt`; Modify `ui/TopBar.kt`, `ui/BrowserScreen.kt`

**Interfaces:** Consumes `Search.byName`, `PaneState.path`, `Prefs.settings.showHidden`.

El botón de buscar convierte la barra en un campo de texto. Busca desde la carpeta del panel activo, con 300 ms de espera tras la última tecla; cada consulta nueva cancela la anterior. Los resultados van apareciendo; cada uno muestra nombre y ruta relativa. Tocar una carpeta navega a ella; tocar un archivo navega a su carpeta y lo deja seleccionado. Indicador "Buscando…" y "Sin resultados" al terminar vacío. Atrás cierra la búsqueda.

- [ ] **Step 1:** Implementar. **Step 2:** `assembleDebug` OK. **Step 3:** Commit `feat(app): búsqueda`.

### Task 14: Control físico

**Files:** Create `ui/Gamepad.kt`; Modify `ui/BrowserScreen.kt`, `ui/PaneView.kt`, `ui/Sidebar.kt`, `ui/Dialogs.kt`; Test `app/src/test/.../GamepadMapTest.kt`

**Interfaces — Produces:**
```kotlin
enum class PadCommand { OPEN, BACK, SELECT, MENU, PREV_PANE, NEXT_PANE, TOGGLE_DUAL, FAVORITES }
object GamepadMap { fun commandFor(keyCode: Int): PadCommand? }
```
Mapa: `KEYCODE_BUTTON_A`→OPEN, `BUTTON_B`→BACK, `BUTTON_X`→SELECT, `BUTTON_Y`→MENU, `BUTTON_L1`→PREV_PANE, `BUTTON_R1`→NEXT_PANE, `BUTTON_START`→TOGGLE_DUAL, `BUTTON_SELECT`→FAVORITES. La cruceta y la palanca izquierda mueven el foco (la palanca se traduce a eventos de dirección con zona muerta 0.5 y repetición cada 120 ms). Se intercepta en `onPreviewKeyEvent` de la raíz. BACK: cierra diálogo, si no limpia selección, si no sale de búsqueda, si no `back()` del panel, si no deja salir. El foco se dibuja con un borde de acento de 2 dp; al cambiar de carpeta el foco va al primer elemento, y al volver atrás, al elemento del que se vino. Los diálogos reciben el foco en su acción principal. MENU abre las mismas acciones de `actionsFor` como menú enfocable.

- [ ] **Step 1: Tests que fallan:** cada código del mapa devuelve su comando; `KEYCODE_VOLUME_UP` devuelve null.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** tests PASS, `assembleDebug` OK.
- [ ] **Step 5:** Commit `feat(app): control físico`.

### Task 15: APK final, instalación y verificación en el Thor

**Files:** Modify `app/build.gradle.kts` (build `release` con minify y firma con un keystore local fuera del repo), `README.md`; Create `docs/verificacion-etapa-1.md`

- [ ] **Step 1:** `./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease` → PASS. Anotar el tamaño del APK.
- [ ] **Step 2:** Copiar el APK a `Download/` del Thor (por cable) y pedir al autor que lo instale; o, si activó la depuración USB, `adb install`.
- [ ] **Step 3:** Recorrer con el autor la lista de `docs/verificacion-etapa-1.md` y anotar el resultado de cada punto:
  1. Abre en Descargas en menos de 1 s.
  2. Carpeta de ROMs grande: desplazamiento fluido.
  3. Copiar un archivo de más de 3 GB del interno a la SD con dos paneles; cancelar a la mitad no deja restos; repetir completo y comprobar tamaño.
  4. Mover una carpeta con subcarpetas entre paneles.
  5. Conflicto de nombre con "Aplicar a todos".
  6. Borrar, restaurar desde la papelera.
  7. Renombrar cambiando solo mayúsculas.
  8. Buscar un archivo tres niveles abajo.
  9. Agregar, reordenar y quitar un favorito; cerrar y abrir la app conserva todo.
  10. Todo lo anterior solo con el control, sin tocar la pantalla.
  11. Entrar a `Android/data`: muestra el estado de error, no se cierra.
  12. Salir de la app durante una copia larga: la copia termina y la notificación lo refleja.
- [ ] **Step 4:** Corregir lo que falle (cada corrección con su test cuando sea lógica de `:core`). Commit `chore: etapa 1 verificada` y etiqueta `v0.1.0`.
