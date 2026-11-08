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
- [~] Bookmarks persist locally; history is in-memory only. Clear-history now also calls GeckoView's `GeckoSession.purgeHistory()` to remove the current session's back/forward entries; the source change passed build, APK checks, emulator launch, unit-test task, and lint in [run #39](https://github.com/Aasimabdul/Veyra/actions/runs/42406555534), but the History screen and purge behavior were not interactively tested. This does not clear cookies, cache, site storage, or downloads.
- [~] GeckoView built-in tracking-protection toggle, default on; implementation passed CI run #37 (build, APK checks, emulator launch, unit tests, lint); live tracker-blocking and physical-device validation pending; not a full ad blocker
- [ ] Accessibility and crash testing

## Phase 2 — desktop proof of concept
- [x] Research official engine support: Qt WebEngine's current desktop target list does not list Windows x86; CEF documents Windows Win32/x64 builds and Linux builds. See README's engine decision and the official CEF project docs.
- [ ] Integrate CEF into the existing desktop scaffold (no desktop browser packages until real page rendering works)
- [ ] Add local HTML --smoke-test, title assertion, and Windows PE architecture checks
- [ ] Verify CEF/Chromium license notices, pinned security updates, package layout, and Linux AppImage launch
- [ ] Add desktop tabs and settings after engine proof passes

## Phase 3 — performance and releases
- [ ] Profile memory on low-RAM hardware
- [ ] Automated UI smoke tests
- [ ] Signed Windows packaging
- [ ] Linux AppImage and DEB packaging
- [x] Android Release Candidate workflow passed signed APK/AAB verification and emulator smoke test in [run #6](https://github.com/Aasimabdul/Veyra/actions/runs/42442229527); this is CI verification, not physical-device validation
- [x] Manual Android first-release workflow added: verifies signed ARM APKs, size <150 MB, ABI/package/certificate/zip alignment, unit tests, lint, and emulator smoke test before publishing a prerelease
- [ ] Run the first-release workflow and verify the published APK download URLs and SHA256SUMS
- [ ] Publish Windows x64/x86 and Linux AppImage only after a real desktop engine is integrated and each target's smoke test passes

## Desktop release blocker

No desktop executable is currently a browser: `desktop/src/main.cpp` only prints a scaffold message. Do not package or describe that scaffold as a working browser. Qt WebEngine's current official desktop platform list is x86_64 for Windows/Linux and does not list Windows x86. CEF documents Win32 and x64 Windows builds and Linux support, so CEF is the preferred candidate pending integration and release testing. References: [Qt supported platforms](https://doc.qt.io/qt-6.10/supported-platforms.html), [Qt WebEngine deployment](https://doc.qt.io/QT-6/qtwebengine-deploying.html), [CEF project](https://github.com/chromiumembedded/cef-project), [CEF security updates](https://github.com/chromiumembedded/cef).
