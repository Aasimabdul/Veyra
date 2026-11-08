# Veyra roadmap

## Phase 0 — foundation
- [x] Multi-platform repository layout
- [x] Android GeckoView starter activity
- [x] Desktop C++ shell scaffold
- [x] Initial CI workflows
- [x] Verify Android dependency resolution, three ABI debug APKs, emulator install/launch, unit tests, and lint on CI
- [ ] Verify installability and stability on physical Android devices

## Phase 1 — usable Android browser
Legend: `[x]` complete, `[~]` partial, `[ ]` not implemented.

- [~] Basic back, forward, reload, and home navigation controls are wired; automated UI interaction coverage and enabled/disabled state feedback are not implemented
- [~] Tabs screen is a placeholder for one browsing session; no multi-tab manager
- [ ] Downloads and permission UX
- [ ] Private sessions
- [~] Bookmarks persist locally; history is in-memory only. Clear-history calls GeckoView's `GeckoSession.purgeHistory()` to remove the current session's back/forward entries; source passed Android CI run [#39](https://github.com/Aasimabdul/Veyra/actions/runs/42406555534), but History-screen behavior was not interactively tested. This does not clear cookies, cache, site storage, or downloaded files.
- [~] GeckoView built-in tracking-protection toggle, default on; implementation passed CI run [#37](https://github.com/Aasimabdul/Veyra/actions/runs/42393585551); live tracker-blocking and physical-device validation pending; not a full ad blocker
- [ ] Lock/PIN/biometric gate
- [ ] Accessibility and crash testing

## Phase 2 — desktop proof of concept
- [x] Compare current official engine/platform documentation
- [ ] Recover and identify the engine SDK available in the local development environment; the public repository currently contains no engine SDK or engine integration
- [ ] Integrate a supported engine into the existing desktop scaffold
- [ ] Add a real local HTML `--smoke-test` that verifies the rendered page title and exits 0/1
- [ ] Run rendering smoke tests on Windows and Linux under Xvfb
- [ ] Verify Windows PE architecture for every published executable
- [ ] Verify engine/runtime license notices, pinned security updates, package layout, and Linux AppImage launch
- [ ] Add desktop tabs, bookmarks and settings after the engine proof passes

## Phase 3 — performance and releases
- [ ] Profile memory on low-RAM hardware
- [ ] Automated Android UI smoke tests
- [ ] Windows portable ZIPs (unsigned; SmartScreen warning expected)
- [ ] Linux x86_64 AppImage
- [x] Android Release Candidate workflow verified signed APK/AAB, ZIP alignment, matching signing certificates, unit-test task, lint, and x86_64 emulator launch in [run #6](https://github.com/Aasimabdul/Veyra/actions/runs/42442229527); this is CI verification, not physical-device validation
- [x] Android-only first-release workflow exists and checks signed ARM APKs, APK size below 150 MB, package/ABI/signature/alignment, unit tests, lint, and emulator smoke test before publishing
- [ ] Run the Android first-release workflow and verify published APK downloads and SHA256SUMS
- [ ] Publish desktop packages only after real page rendering and package smoke tests pass

## Desktop engine decision and blockers

The public `desktop/src/main.cpp` currently prints a scaffold message. The current Desktop Shell CI proves only that this native shell compiles and launches; it is **not a browser** and must not be distributed as one.

Qt WebEngine is not selected for the requested matrix: current Qt supported-platform documentation lists Windows x86_64 (and ARM64), not 32-bit Windows, and current Qt WebEngine depends on Chromium's narrower platform support. Official references:
- [Qt 6 supported platforms](https://doc.qt.io/qt-6.12/supported-platforms.html)
- [Qt WebEngine deployment](https://doc.qt.io/QT-6/qtwebengine-deploying.html)

CEF remains the leading candidate for Windows x64 and Linux x86_64. Its official project documentation describes Win32 and x64 CMake configurations, and CEF documentation explains that its release branches track Chromium. However, the existence of Win32 build instructions alone is not sufficient proof that a current security-supported binary distribution is available for Windows x86. Before claiming that target, pin a current CEF branch, confirm the official downloadable Win32 distribution or reproduce a supported build, and run the same real-page smoke test. Do not ship a stale Chromium runtime merely to provide a 32-bit executable.
- [CEF project build instructions](https://github.com/chromiumembedded/cef-project)
- [CEF branches and build requirements](https://github.com/chromiumembedded/cef/blob/master/docs/branches_and_building.md)
- [CEF general usage and licensing](https://chromiumembedded.github.io/cef/general_usage)

The user has indicated that an engine SDK is installed in their development environment, but those local files are not present in this public repository. CI cannot consume that local SDK until its acquisition path/version is made reproducible. This is the current integration blocker; no fake browser binary or desktop release is produced while it remains unresolved.

## Release policy
- Android public release assets must be only the signed `arm64-v8a` and `armeabi-v7a` APKs; each must be strictly smaller than 150 MB and use the same certificate.
- Windows packages must be explicitly labelled unsigned until a real code-signing certificate is configured.
- Desktop artifacts are release-eligible only after actual page rendering, architecture verification, package launch, and SHA-256 checks pass.
- If current supported engine distributions do not support Windows x86, document that limitation in release notes and omit the Windows x86 package rather than ship a non-browser executable.
