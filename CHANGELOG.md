# Historial de cambios

Cada versión tiene su APK en [Releases](../../releases). Hasta la 0.5.2 la app se llamaba
**Archivero**.

## 0.6.1

- Con el control ya se puede navegar Acceso rápido al abrirlo con ☰ (antes solo funcionaba
  con Select: el foco se quedaba en la lista de atrás). También si se abre con el dedo y
  luego se usa el control.
- La app abre en la raíz del almacenamiento interno, donde está todo, en vez de Descargas.
  Si nunca elegiste otra carpeta de inicio, se corrige sola al actualizar.

## 0.6.0 — V Files

- La app ahora se llama **V Files**.
- Identificador nuevo (`io.github.v0uer.vfiles`): se instala como app aparte de Archivero.
  Desinstala la anterior; tus archivos no se tocan, solo hay que volver a anclar carpetas.
- Código publicado con README, historial y todas las versiones anteriores.

## 0.5.2

- Icono nuevo: una carpeta estilo Windows con pestaña roja y frente azul sobre fondo carbón.

## 0.5.1

- La división entre paneles siempre se pega a la mitad al soltarla cerca; se quitó el
  candado para prenderlo o apagarlo.

## 0.5.0 — Zips y Acceso rápido

- **Zips:** un `.zip` se abre como carpeta de solo lectura, con subcarpetas. Tocar un
  archivo de adentro lo abre directo.
- *Extraer* (junto al zip), *Extraer al otro panel* y *Comprimir* (crea un `.zip` con lo
  seleccionado).
- Nombres en UTF-8 y en el formato viejo de Windows; un zip dañado o con contraseña muestra
  un aviso en vez de cerrarse. Las entradas que intentan salirse del destino se ignoran.
- **Acceso rápido:** la barra lateral arranca con Escritorio (si existe), Descargas,
  Documentos, Imágenes, Videos y Música, con su icono y un pin. A quien ya tenía favoritos
  se le agregan una sola vez, sin duplicar.
- "Favorito" pasa a llamarse *Anclar a Acceso rápido*.
- Imán a la mitad al arrastrar la división, con un candado para activarlo.

## 0.4.0 — Iconos de Windows 11 y división ajustable

- Todos los iconos pasan a Fluent UI System Icons, los de Windows 11: los de los botones son
  monocromos y los de carpetas y tipos de archivo van a color.
- La división entre los dos paneles se arrastra (de 20 % a 80 %); doble toque la regresa a
  la mitad. Con el control, R2 agranda el panel activo y L2 lo achica. Se apaga en ⋮ →
  *Ajustar tamaño de paneles* y el reparto se recuerda.

## 0.3.1

- La barra de cada panel va en dos renglones: botones arriba y la ruta completa abajo, que
  así se lee aunque haya dos paneles en una pantalla chica.
- La barra de favoritos entra y sale sin cruzar por encima del otro panel.

## 0.3.0 — Cada panel con su barra

- Cada panel tiene su propia barra: ☰ ← ↑ ruta 🔍 ⋮, y ◫ para abrir el segundo panel o ✕
  para cerrarlo.
- Ya no hay columna fija de favoritos: los paneles ocupan todo el ancho y el ☰ abre los
  favoritos encima de su panel.
- La búsqueda es del panel donde se abre.
- Control: Select abre o cierra los favoritos del panel activo.

## 0.2.1 — Correcciones importantes

- En Android 11–13 no se podía vaciar la papelera, y mover carpetas a la SD dejaba el árbol
  vacío en el origen. Arreglado, con un candado de compilación para que no vuelva a pasar.
- Al mover, *Omitir* ya no borra carpetas vacías del origen.
- Borrar algo de la papelera pide confirmación.
- Una lectura que falla a la mitad (SD retirada) ya no cierra la app en búsqueda,
  propiedades o papelera.

## 0.2.0 — Celulares

- Diseño para celular: en vertical los paneles van uno arriba del otro, los favoritos son un
  cajón y las filas muestran tamaño y fecha bajo el nombre.
- La app se dibuja de borde a borde respetando las barras del sistema.
- En celular, *Favorito* va primero en la barra de acciones.

## 0.1.1

- Agregar a favoritos desde la lista (mantener presionada una carpeta).
- Panel de favoritos más ancho y plegable.

## 0.1.0 — Primera versión

- Explorador de uno y dos paneles, con favoritos y almacenamiento (interno y SD).
- Copiar, mover, renombrar, borrar a la papelera, borrar definitivo, propiedades, compartir
  y abrir. Copia segura: nada queda a medias.
- Conflictos de nombre con *Aplicar a todos*.
- Operaciones en segundo plano con notificación y cancelar.
- Búsqueda por nombre en subcarpetas.
- Manejo completo con el control físico.
