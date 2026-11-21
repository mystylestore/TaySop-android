# TayShop — Proyecto APK Android WebView

Proyecto Android preconfigurado para cargar la aplicación web:
**https://script.google.com/macros/s/AKfycbxWuafFw3IW-BVfdSarB1eA_hMkuW02KrH4iSblIVTM1nsixzh_gOMSwfLKtgrduWQ_/exec**

- **ID de paquete:** `com.tayshop.webapp`
- **Versión:** `1.0.0` (código `1`)
- **SDK mínimo:** Android 7.0 (API 24) · **SDK destino:** Android 15 (API 35)

---

## Opción 1: Compilar el archivo .APK gratis en la nube (sin instalar Android Studio)

Este paquete incluye un flujo automático de GitHub Actions en `.github/workflows/build-apk.yml`.

1. Crea un repositorio nuevo en [github.com/new](https://github.com/new) (público o privado).
2. Sube todos los archivos descomprimidos de este ZIP al repositorio.
3. En GitHub, abre la pestaña **Actions** > **Compilar APK Android** > **Run workflow**.
4. Cuando finalice (~2 minutos), descarga el artefacto **TayShop-apk**, descomprímelo e instala `app-debug.apk` en tu teléfono Android.

## Opción 2: Compilar localmente en Android Studio

1. Abre **Android Studio** > **Open** y selecciona esta carpeta.
2. Espera a que Gradle sincronice las dependencias.
3. Ve al menú **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. El archivo instalable quedará en `app/build/outputs/apk/debug/app-debug.apk`.

## Opción 3: Compilar por terminal (si tienes Java 17 y Android SDK)

```bash
gradle assembleDebug
```
