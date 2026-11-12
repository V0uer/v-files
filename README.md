# Archivero

Explorador de archivos propio para Android. Sin anuncios, sin cuentas, sin internet.

## Compilar (Git Bash)

    source ./env.sh
    ./gradlew :core:test
    ./gradlew :app:assembleDebug

El APK queda en `app/build/outputs/apk/debug/app-debug.apk`.
Requiere JDK 17 y el SDK de Android (plataforma 35); las rutas van en `env.sh` y `local.properties`.

Diseño y planes: `docs/superpowers/`.
