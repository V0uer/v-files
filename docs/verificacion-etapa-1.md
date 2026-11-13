# Verificación de la etapa 1 en el Thor

APK: `app/build/outputs/apk/release/app-release.apk` (versión 0.1.0).
Estado: **pendiente**. Ningún punto se ha probado todavía en el aparato; la interfaz solo se ha compilado.

Para cada punto, anotar `OK` o lo que falló.

| # | Qué probar | Resultado |
|---|------------|-----------|
| 1 | Abre en Descargas en menos de 1 s. | |
| 2 | Carpeta de ROMs grande: desplazamiento fluido. | |
| 3 | Copiar un archivo de más de 3 GB del interno a la SD con dos paneles; cancelar a la mitad no deja restos; repetir completo y comprobar tamaño. | |
| 4 | Mover una carpeta con subcarpetas entre paneles. | |
| 5 | Conflicto de nombre con "Aplicar a todos". | |
| 6 | Borrar y restaurar desde la papelera. | |
| 7 | Renombrar cambiando solo mayúsculas. | |
| 8 | Buscar un archivo tres niveles abajo. | |
| 9 | Agregar, reordenar y quitar un favorito; cerrar y abrir la app conserva todo. | |
| 10 | Todo lo anterior solo con el control, sin tocar la pantalla. | |
| 11 | Entrar a `Android/data`: muestra el estado de error, no se cierra. | |
| 12 | Salir de la app durante una copia larga: la copia termina y la notificación lo refleja. | |

## Dónde mirar con más cuidado

Estas partes se escribieron sin poder verlas correr, así que son las más probables de necesitar ajuste:

- **Control físico.** Que el foco (borde azul) aparezca al tocar el control, que A abra, B regrese, X seleccione,
  Y abra el menú, L1/R1 cambien de panel, Start alterne los dos paneles y Select abra los favoritos sobre el panel activo (B los cierra).
  Dentro de diálogos y menús, que A acepte y B cierre.
- **Palanca.** Un toque corto debe avanzar un solo renglón; sostenida, repetir.
- **Tarjeta SD.** Que aparezca en "Almacenamiento" y que abrir y compartir archivos de la SD funcione.
- **Abrir un `.apk`.** Debe saltar el instalador (la primera vez Android pide permitir la instalación desde Archivero).
- **Notificación de avance.** Android pide permiso de notificaciones la primera vez que se copia algo.

## Controles

| Botón | Hace |
|-------|------|
| Cruceta / palanca izquierda | Mover el foco |
| A | Abrir / aceptar |
| B | Atrás (quita selección, cierra búsqueda, regresa de carpeta) |
| X | Seleccionar lo enfocado (en un favorito: su menú) |
| Y | Menú de acciones |
| L1 / R1 | Panel izquierdo / derecho |
| Start | Uno o dos paneles |
| Select | Abrir o cerrar favoritos sobre el panel activo |
| L2 / R2 | Achicar / agrandar el panel activo (si "Ajustar tamaño de paneles" está prendido) |
