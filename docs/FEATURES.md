# Veyra Android feature status

Status words follow the release rules: only features that were built **and** checked are marked tested. "CI" = GitHub Actions Android CI (unit tests, lint, build, emulator launch and screenshots on an x86_64 API 35 emulator). Engine: Mozilla GeckoView 159.

| Feature | Status | Evidence / limits |
|---|---|---|
| Neumorphic UI, all screens (lock, home, search, browser, tabs, bookmarks, history, downloads, settings, appearance, privacy, Private Space) | IMPLEMENTED AND TESTED | Emulator screenshots in CI (`ci-screenshots` branch). Phone-size layouts only; tablet gutters not visually verified. |
| 12 themes + System | IMPLEMENTED AND TESTED | `ThemesTest` checks contrast (text 7:1, muted 4.5:1, accent 3:1) and shadow direction; 3 themes screenshotted. |
| Multiple tabs (max 8) | IMPLEMENTED BUT NOT FULLY TESTED | One GeckoSession per tab; tab switching not exercised by automated tests. |
| Private tabs | IMPLEMENTED BUT NOT FULLY TESTED | Gecko private-mode sessions, never added to history, `FLAG_SECURE` in release builds. Cookie/storage isolation not independently verified. |
| Tracker protection (Standard / Strict), cookie modes, JavaScript switch | IMPLEMENTED BUT NOT FULLY TESTED | Uses Gecko settings; blocked counter counts Gecko block events only. No controlled tracker test page yet. |
| Ad blocking with filter lists, per-site exceptions | NOT IMPLEMENTED | Only Gecko's built-in tracking protection. |
| Bookmarks (persistent), history (persistent, normal tabs only) | IMPLEMENTED BUT NOT FULLY TESTED | SharedPreferences, history capped at 300. |
| Find in page, desktop-site mode, share, reload, back/forward | IMPLEMENTED BUT NOT FULLY TESTED | |
| Download manager | IMPLEMENTED AND TESTED (basic) | Emulator downloaded a 13 KB PDF into `Download/Veyra`; naming/format logic unit-tested. Pause/resume needs a public URL and Range support; downloads run only while the app process is alive (no foreground service); no concurrent-limit stress test. |
| Direct media/file URL download | IMPLEMENTED BUT NOT FULLY TESTED | Rejects HTML pages; no DRM/segmented-stream handling. |
| Video/audio player (Media3) | IMPLEMENTED BUT NOT FULLY TESTED | Local files, direct URLs and HLS streams, speed, subtitles button, PiP, resume position. No background playback. Not yet exercised with sample media in CI. |
| App lock (device credential) | IMPLEMENTED BUT NOT FULLY TESTED | System credential screen; emulator has no screen lock, so the unlock flow was not run. |
| Private Space (AES-256-GCM, Keystore key, encrypted index) | PARTIALLY TESTED | Round trip, tamper detection, wrong-key and size-limit unit-tested with a software key. Keystore + device-credential path not run on a real device. No backup or recovery. |
| VPN | IMPLEMENTED BUT NOT TESTED ON A DEVICE | Real device-wide tunnel via the WireGuard engine. The user imports their own `.conf` (file or paste); stored encrypted with a Keystore key. Veyra ships no VPN servers, no kill switch and no split tunnelling. Needs a real-device test with a working config. |
| Page video/audio detection | IMPLEMENTED BUT NOT TESTED ON A DEVICE | Built-in scanner extension finds `<video>`, `<audio>`, og:video and media links; menu shows "Media on this page" with Play and Save. Direct files (mp4, webm, mp3...) can be saved; HLS (.m3u8) is play-only. blob/MSE, DASH and DRM streams (e.g. YouTube, Netflix) are detected but cannot be saved or played outside the page. Saving uses a plain connection without the page's cookies, so login-protected files may fail. |
| Background downloads service, notifications with progress | NOT IMPLEMENTED | Completion notification only (Android 13+ permission requested). |
| Instrumented UI tests, low-RAM and performance measurements | NOT IMPLEMENTED | No benchmark numbers have been recorded. |
