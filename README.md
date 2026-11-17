# 151 Editor

An offline Android studio for photos, video, audio and print design. No AI, no account, no network.

Part of The 151 Universe family of apps.

## What it does
- **Photo** – crop (free and fixed ratios), rotate, straighten, light and colour sliders, 11 filters, text with 25 open fonts. Always saves a new copy; the original is never touched.
- **Video** – a three-lane timeline (text, clips, music): trim, split, speed 0.25–2×, volume, fades, text overlays. Exports MP4, or audio only as M4A.
- **Audio** – up to six tracks of sounds and recorded voice: trim, split, fades, markers, crossfade, remove silences, clean background noise. Saves MP3, M4A, WAV or FLAC.
- **Design** – flyers, posts, stories, cards and multi-page documents: text styles, master pages, margins and columns, threaded text, tables, shapes and photos. Saves PNG or print-ready PDF (bleed and crop marks).
- **Pen** – one freehand drawing tool (pen, marker, eraser) shared by every editor.

## What it does not do
No cloud, no sign-in, no ads, no tracking. The only permissions are storage (Android 9 and older) and the microphone, asked only when you record your voice.

## Build
Requirements: JDK 21, Android SDK (compileSdk 36) and NDK 27.2.12479018; `tools/test.sh` reads them from `JAVA_HOME` and `ANDROID_HOME`.

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
bash tools/test.sh             # unit tests + debug and release builds + permission check; non-zero on failure
```

Signing is optional. To sign debug and release with your own key, put `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and the keystore in a `.signing/` folder (ignored by git). Without it, debug uses the standard Android debug key and release is unsigned.

## How it is built
Native Kotlin, minSdk 26, Media3 1.11.1 (ExoPlayer for preview, Transformer for export). The editing logic of each editor lives in plain Kotlin files (`Progetto.kt`, `Suono.kt`, `Foto.kt`, `Grafica.kt`, `Mano.kt`) tested on the JVM; one drawing recipe per editor is shared by the screen and the exported file, so what you see is what you get. Code comments are in Italian.

## License
The app code is released under the MIT License (see `LICENSE`).

Third-party parts keep their own licenses:
- **LAME 3.100** (`app/src/main/cpp/lame/`) – LGPL 2, see `app/src/main/cpp/lame/COPYING`. It is compiled from source with the app; you can rebuild it with your own changes.
- **Fonts** (`app/src/main/assets/font/`) – SIL Open Font License and Apache 2.0, one license file per font in `app/src/main/assets/font/licenze/`.
- **AndroidX and Media3** – Apache 2.0, fetched by Gradle.

A personal project, shared as is: no support, but issues are welcome.
