# Veyra Browser

Veyra is an open-source browser project targeting **Android, Windows, and Linux**.

> **Development status:** early preview, not a production-ready browser. CI has successfully built ABI-specific debug APKs and installed/launched the x86_64 APK in an API 35 emulator. No production-signed release has been published.

## Platforms

- **Android:** Kotlin + Mozilla GeckoView starter app.
- **Windows/Linux:** C++/CMake desktop shell scaffold. A working desktop rendering engine is still to be selected and integrated.
- **Design direction:** dark navy + neon cyan.
- **Planned packaging:** Android APK/AAB, Windows EXE/installer, Linux AppImage/DEB.

## Android build

Open the `android/` folder in Android Studio with JDK 17. Install Android SDK platform `37.2` and Build Tools `36.0.0`, then sync and run the `app` configuration. CI uses Gradle `9.6.0`. The first Gradle build requires internet access to download dependencies.

CI currently verifies separate `arm64-v8a`, `armeabi-v7a`, and `x86_64` debug APKs, checks package metadata, ZIP alignment and APK signatures, installs/launches the x86_64 build in an API 35 emulator, then runs unit tests and Android lint. These are debug artifacts, retained for 14 days; they are not production-signed releases. Download available artifacts from the [GitHub Actions runs](https://github.com/Aasimabdul/Veyra/actions/workflows/android.yml).

## Current implementation status

This repository is an early preview, not a privacy-hardened daily-use browser. The following describes the current source code, not planned features.

### Implemented in the Android prototype
- A GeckoView-based single browsing session with URL/search input.
- Basic page navigation, reload, and a back action.
- Neumorphic dark navy/cyan home, browsing, and navigation surfaces.
- Session history is kept in memory and is lost when the process is recreated.
- Bookmarks are saved in app-private preferences on this device, with no sync. The code passed the general Android CI build and emulator smoke test, but bookmark persistence has no dedicated automated behavior test or physical-device verification yet.
- Address-bar input resolution routes valid HTTP(S) URLs and domain names to navigation, and treats ordinary text or unsupported URI schemes as search text. The focused unit tests, including the `localhost:8080` regression, passed in [Android CI run #35](https://github.com/Aasimabdul/Veyra/actions/runs/42376990810). This confirms the resolver's unit tests, not broader browser security or physical-device behavior.
- A tracking-protection toggle is now wired to GeckoView's built-in anti-tracking categories, defaults to enabled, and persists the user's choice in app-private preferences. The code change is awaiting Android CI verification; no live tracker-blocking test has been run. This is not a comprehensive ad blocker.

### Not implemented or not verified
- No lock screen, PIN, biometric gate, or protected-tabs mechanism.
- No real multi-tab management or isolated private-browsing sessions.
- No comprehensive ad blocker, configurable tracker categories, cookie controls, or anti-fingerprinting protections. The basic GeckoView tracking-protection toggle is awaiting CI and live-behavior verification.
- Downloads are a placeholder screen; a download manager is not implemented.
- Remaining settings rows are presentation-only; only the tracking-protection toggle is wired to browser behavior in the current source.
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
