# Veyra Browser

Veyra is an open-source browser project targeting **Android, Windows, and Linux**.

> **Development status:** starter scaffold only. This is not yet a production-ready browser, and no installable APK/release has been verified.

## Platforms

- **Android:** Kotlin + Mozilla GeckoView starter app.
- **Windows/Linux:** C++/CMake desktop shell scaffold. A working desktop rendering engine is still to be selected and integrated.
- **Design direction:** dark navy + neon cyan.
- **Planned packaging:** Android APK/AAB, Windows EXE/installer, Linux AppImage/DEB.

## Android build

Open the `android/` folder in Android Studio, use JDK 17, install Android SDK 35, then sync and run the `app` configuration. The first Gradle build requires internet access to download dependencies.

## Desktop engine decision

GeckoView is intended for Android embedding; it is not a drop-in desktop embedding library. The desktop shell currently does not render web pages. Before shipping desktop builds, validate an engine integration path, licensing, security update strategy, and packaging.

## Roadmap

1. Build and smoke-test the Android app.
2. Add back/forward/reload, tabs, bookmarks, history, downloads, and private sessions.
3. Validate desktop rendering on both Windows and Linux.
4. Profile RAM and startup time on low-memory devices.
5. Add reproducible release builds and signing.

## Security

- Do not bypass DRM, access controls, or browser security boundaries.
- Treat URLs and downloads as untrusted input.
- Never log passwords or private browsing data.
- Keep permissions and downloads explicit.

See [docs/ROADMAP.md](docs/ROADMAP.md) for more details.
