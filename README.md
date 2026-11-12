# Archivero

Explorador de archivos propio para Android. Sin anuncios, sin cuentas, sin internet.

## Compilar (Git Bash)

    source ./env.sh
    ./gradlew :core:test
    ./gradlew :app:assembleDebug

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Versión final

    ./gradlew :core:test :app:testDebugUnitTest :app:assembleRelease

Sale en `app/build/outputs/apk/release/app-release.apk`, reducido y firmado.
La firma usa un almacén de claves que vive fuera del repo; `local.properties` (que no se sube) dice dónde está:

    archivero.keystore=D:/ruta/al/almacen.jks
    archivero.keystore.password=...
    archivero.key.alias=archivero

Si faltan esas líneas, se firma con la clave de depuración. Ojo: Android no deja actualizar una app
con un APK firmado con otra clave; hay que desinstalar la anterior.

## Instalar en el aparato

Con depuración USB: `adb install -r app/build/outputs/apk/release/app-release.apk`.
Sin ella: copiar el APK a `Download/` y abrirlo desde el aparato.

Lista de pruebas y controles: `docs/verificacion-etapa-1.md`.
Requiere JDK 17 y el SDK de Android (plataforma 35); las rutas van en `env.sh` y `local.properties`.

Diseño y planes: `docs/superpowers/`.
