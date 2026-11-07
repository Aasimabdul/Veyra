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

## Current implementation status

This repository is an early preview, not a privacy-hardened daily-use browser. The following describes the current source code, not planned features.

### Implemented in the Android prototype
- A GeckoView-based single browsing session with URL/search input.
- Basic page navigation, reload, and a back action.
- Neumorphic dark navy/cyan home, browsing, and navigation surfaces.
- In-memory session history and bookmarks; these are lost when the process is recreated.

### Not implemented or not verified
- No lock screen, PIN, biometric gate, or protected-tabs mechanism.
- No real multi-tab management or isolated private-browsing sessions.
- No configurable tracker/ad blocking, cookie controls, or anti-fingerprinting protections.
- Downloads are a placeholder screen; a download manager is not implemented.
- Settings rows are presentation-only; the listed privacy and appearance controls do not change browser behavior.
- The quick-link “Add” action is a placeholder.
- No VPN or built-in video/audio downloader.
- No desktop web-rendering engine; the Windows/Linux target is only a C++ shell scaffold.
- No production-signed Android release has been published.

Do not rely on Veyra for sensitive browsing until the missing privacy controls are implemented and independently tested. A successful CI build alone does not prove privacy, crash-free behavior on physical devices, or production-release readiness.

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
