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

- [~] Basic back, reload, and home navigation; forward navigation is not implemented
- [~] Tabs screen is a placeholder for one browsing session; no multi-tab manager
- [ ] Downloads and permission UX
- [ ] Private sessions
- [~] Bookmarks persist locally; history is in-memory only. Clear-history now also calls GeckoView's `GeckoSession.purgeHistory()` to remove the current session's back/forward entries; CI verification is pending in [run #39](https://github.com/Aasimabdul/Veyra/actions/runs/42406555534). This does not clear cookies, cache, site storage, or downloads.
- [~] GeckoView built-in tracking-protection toggle, default on; implementation passed CI run #37 (build, APK checks, emulator launch, unit tests, lint); live tracker-blocking and physical-device validation pending; not a full ad blocker
- [ ] Accessibility and crash testing

## Phase 2 — desktop proof of concept
- [ ] Evaluate supported engine embedding options for Windows and Linux
- [ ] Build a page-rendering proof of concept on both OSes
- [ ] Verify licensing, security updates, and packaging
- [ ] Add desktop tabs and settings after engine proof passes

## Phase 3 — performance and releases
- [ ] Profile memory on low-RAM hardware
- [ ] Automated UI smoke tests
- [ ] Signed Windows packaging
- [ ] Linux AppImage and DEB packaging
- [ ] Android APK/AAB release workflow
