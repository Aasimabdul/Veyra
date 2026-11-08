# Veyra roadmap

## Phase 0 — foundation
- [x] Multi-platform repository layout
- [x] Android GeckoView starter activity
- [x] Desktop native shell scaffold (superseded by Qt WebEngine prototype)
- [x] Initial CI workflows
- [x] Android signed ARM APK release workflow and first prerelease

## Phase 1 — usable Android browser
Legend: `[x]` complete, `[~]` partial, `[ ]` not implemented.

- [~] Basic back, forward, reload, and home navigation controls are wired; automated UI interaction coverage and enabled/disabled state feedback are not implemented
- [~] Tabs screen is a placeholder for one browsing session; no multi-tab manager
- [ ] Downloads and permission UX
- [ ] Private sessions
- [~] Bookmarks persist locally; history is in-memory only. Clear-history removes current session back/forward entries, not cookies, cache, site storage, or downloaded files.
- [~] GeckoView built-in tracking-protection toggle, default on; live tracker-blocking and physical-device validation pending; not a full ad blocker
- [ ] Lock/PIN/biometric gate
- [ ] Accessibility and crash testing

## Phase 2 — desktop browser prototype
- [x] Replace console-only scaffold with a Qt WebEngine browser window
- [x] Add address bar and back/forward/reload/home navigation
- [x] Add deterministic local HTML `--smoke-test` with rendered-title assertion
- [ ] Verify Windows and Linux CI builds and smoke tests pass
- [ ] Test normal external-page navigation and safe shutdown
- [ ] Add tabs, bookmarks, history, downloads and settings
- [ ] Verify Qt runtime deployment and license notices
- [ ] Package Linux AppImage and Windows portable ZIP after CI is green
- [ ] Evaluate Windows x86 separately; current Qt WebEngine target is x64

## Phase 3 — performance and releases
- [ ] Profile memory on low-RAM hardware
- [ ] Automated Android UI smoke tests
- [ ] Physical-device Android validation
- [x] Android-only first prerelease published with signed ARM64 and ARMv7 APKs
- [ ] Publish desktop packages only after real page rendering and package smoke tests pass

## Desktop engine decision

The desktop prototype uses Qt 6 WebEngine, an embedded Chromium-based renderer. CI pins Qt 6.8.3 and installs the WebEngine module. This is a starting point; before a stable release, update to a currently supported Qt patch release, audit security advisories, and ship the required licenses/notices.

Official references:
- [Qt 6 supported platforms](https://doc.qt.io/qt-6.8/supported-platforms.html)
- [Qt WebEngine deployment](https://doc.qt.io/qt-6/qtwebengine-deploying.html)
- [Qt WebEngine licensing](https://doc.qt.io/qt-6/qtwebengine-licensing.html)

## Release policy
- Android public release assets must be signed ARM64 and ARMv7 APKs under 150 MB and use the same certificate.
- Windows packages must be labelled unsigned until a real code-signing certificate is configured.
- Desktop artifacts are release-eligible only after actual page rendering, architecture verification, package launch, and SHA-256 checks pass.
- Do not publish a shell-only or unverified artifact as a functioning browser.
