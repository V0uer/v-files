# Archivero: explorador de archivos propio para el AYN Thor

Fecha: 2026-11-12. Estado: diseño, pendiente de aprobación.
"Archivero" es nombre de trabajo (decisión mía, se puede cambiar).

## Intención y porqué

El autor usa un explorador de archivos comercial en su AYN Thor (Android 13, consola portátil con controles físicos y dos pantallas). Le estorba: salen anuncios a media operación (al mover o cambiar un archivo), depende de licencias que a veces se pierden, y se siente torpe. Quiere su propia app: mismo nivel de utilidad práctica, pero limpia, rápida, local y suya, que se pueda ir modificando y ampliando sobre la marcha.

El modelo mental que pidió es el explorador de Windows.

## Lo que el autor decidió

- Abre por defecto en Descargas.
- Favoritos fijos en un panel a la izquierda.
- Un botón activa el modo de dos paneles, para comparar carpetas o pasar archivos de una a otra.
- Debe tener las funciones prácticas de un explorador completo, mejoradas.
- Se maneja con el dedo y también con la palanca y los botones del Thor. Los dos completos.
- Transferencia de archivos con la PC por wifi: sí.
- Acceso a carpetas protegidas (`Android/data`): sí, si resulta viable.
- El aspecto del boceto aprobado (tema oscuro, barra superior con ruta, lista con nombre/tamaño/fecha) está bien como base; la personalización se irá viendo en la marcha.
- Orden de etapas aprobado (ver abajo).

## Metas

1. Ninguna interrupción: cero anuncios, cero avisos de licencia, cero cuentas.
2. Rapidez perceptible: abrir la app y listar carpetas grandes sin espera.
3. Operaciones confiables: nunca perder ni corromper archivos; todo progreso visible y cancelable.
4. Uso cómodo con control físico, sin tocar la pantalla.
5. Código fácil de ampliar, porque se le van a ir agregando cosas.

## No-metas (por ahora)

- Nubes (Drive, Dropbox, etc.).
- Publicar en Play Store. Es una app instalada por APK para uso propio.
- Reproductor multimedia propio, editor de texto avanzado, FTP, root.
- Uso especial de la segunda pantalla del Thor (decisión mía: la app corre en la pantalla donde se abra).

## Anti-metas (sería un fracaso aunque funcione)

- Cualquier cosa que se atraviese a media operación.
- Que la app se conecte a internet. Solo red local, y solo en la etapa 3.
- Que una copia o un movimiento deje archivos a medias sin avisar.
- Que parezca una copia de la app comercial: el diseño, nombre e iconos son propios.

## Restricciones

- Dispositivo: AYN Thor, Android 13. minSdk 30 (decisión mía).
- Se compila en la PC del autor (Windows 11). Hoy solo hay Java 8: hay que instalar JDK 17 y el SDK de Android por línea de comandos (unos 5 GB; hay ~23 GB libres en C: y ~21 GB en D:).
- No hay adb configurado. El APK se pasa por cable a `Download/` y se instala desde el Thor, salvo que el autor active la depuración USB (queda a criterio del constructor proponerlo).
- El repositorio va anonimizado: identidad local de git `V0uer <284541292+V0uer@users.noreply.github.com>`. Ningún nombre real ni correo personal en código, commits ni documentos.

## Cómo se hace (decisiones mías)

- Kotlin + Jetpack Compose, una sola actividad.
- Acceso directo al sistema de archivos (`java.io`/`java.nio`) con el permiso "Acceso a todos los archivos" (`MANAGE_EXTERNAL_STORAGE`), que es lo más rápido y es válido para una app instalada por APK.
- Motor de operaciones separado de la interfaz: un módulo Kotlin puro, probado en JVM con carpetas temporales. La interfaz solo observa su estado.
- Sin Google Play Services, sin analítica, sin librerías de anuncios.
- Preferencias y favoritos en DataStore.

## Etapas

Cada etapa termina en un APK usable.

### Etapa 1: el explorador

**Pantalla principal**
- Barra superior: atrás, subir, ruta en migas (cada tramo se puede tocar), buscar, botón "2 paneles", menú.
- Panel izquierdo: Favoritos (Descargas por defecto, más los que el usuario agregue; se reordenan y se quitan), Almacenamiento (interno y tarjeta SD, con espacio libre).
- Lista de archivos: icono, nombre, tamaño, fecha. Ordenar por nombre, fecha, tamaño o tipo. Vista de lista o cuadrícula. Mostrar/ocultar archivos ocultos. Miniaturas de imágenes cargadas de forma perezosa.
- Carpeta inicial configurable; por defecto, Descargas.

**Dos paneles**
- El botón parte el área en dos listas independientes, cada una con su ruta. Un panel es el activo (marcado visualmente).
- En dos paneles, el panel de favoritos se encoge a iconos.
- Con selección activa aparece una barra de acciones: "Copiar al otro panel", "Mover al otro panel", renombrar, borrar, más.

**Operaciones**
- Selección múltiple (pulsación larga o botón del control), seleccionar todo.
- Copiar, mover, renombrar, borrar, crear carpeta, propiedades (tamaño recursivo, fecha, ruta).
- Cola de operaciones en segundo plano con progreso (archivos, bytes, velocidad), cancelable, que sobrevive a salir de la app (servicio en primer plano con notificación).
- Conflictos de nombre: preguntar una vez, con "aplicar a todos": omitir, reemplazar, conservar ambos.
- Copia segura: se escribe a un nombre temporal y se renombra al terminar; se verifica el tamaño. Mover dentro del mismo volumen es un renombrado; entre volúmenes es copiar, verificar y luego borrar el origen.
- Borrar manda a una papelera propia (carpeta oculta en el mismo volumen) recuperable durante 30 días, con opción de borrar definitivo. Decisión mía.
- Abrir archivo con la app que corresponda ("Abrir con"). Compartir.

**Búsqueda**
- Por nombre, recursiva desde la carpeta actual, con resultados que van apareciendo mientras busca. Sin índice previo.

**Control físico** (asignación propuesta, decisión mía, configurable más adelante)
- Cruceta/palanca: mover el foco. A: abrir. B: atrás. X: seleccionar. Y: menú de acciones.
- L1/R1: cambiar de panel. Start: activar/desactivar dos paneles. Select: favoritos.
- Todo elemento accionable debe poder alcanzarse con el foco; el foco siempre visible.

**Objetivos de rendimiento** (míos)
- Arranque en frío a lista visible: menos de 1 s.
- Carpeta con 5,000 elementos: primeros elementos en pantalla en menos de 300 ms; desplazamiento fluido.

### Etapa 2: zips y visores
- Comprimir a zip; descomprimir zip (y 7z/rar de solo lectura si hay librería libre adecuada; queda al constructor).
- Entrar a un zip como si fuera carpeta.
- Visor de imágenes y de texto integrados, simples.

### Etapa 3: PC por wifi
- La app levanta en el Thor una página web local, protegida con un PIN que se muestra en pantalla. Desde el navegador de la PC se suben y bajan archivos, sin instalar nada en la PC. Decisión mía.
- Solo acepta conexiones de la red local y solo mientras el usuario la tiene encendida.
- Requiere el permiso de red de Android; la app sigue sin hacer ninguna conexión hacia internet.
- Entrar desde el Thor a carpetas compartidas de Windows (SMB) queda como posible ampliación posterior.

### Etapa 4: carpetas protegidas
- Acceso a `Android/data` y `Android/obb` mediante Shizuku (app ayudante gratuita que el usuario activa).
- **Riesgo conocido, hay que probarlo antes de comprometerse:** los archivos que se copiaron por cable a la carpeta del emulador Eden quedaron con un dueño que el emulador no podía leer ni escribir. Hay que hacer una prueba corta: copiar con Shizuku un archivo a `Android/data/dev.eden.eden_emulator` y comprobar que Eden lo lee y lo puede modificar. Si no pasa, esta etapa se limita a ver y sacar archivos de esas carpetas, y se documenta que para meterlos se usan los importadores del emulador.

## Lo que queda al constructor

- Estructura de módulos, librerías concretas (carga de imágenes, servidor web embebido, compresión).
- Detalle visual: espaciados, iconos, animaciones, dentro del estilo del boceto.
- Estrategia de pruebas de interfaz. El motor de operaciones sí debe llevar pruebas, incluidas las de fallo a media copia (falta de espacio, cancelación).
- Forma de instalar y probar en el Thor.

## Referencia

Boceto aprobado de la pantalla principal (un panel y dos paneles): `docs/superpowers/specs/boceto-layout.html`.
