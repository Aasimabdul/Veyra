# Veyra Desktop (preview development)

Veyra Desktop is moving from the original console scaffold to a Qt 6 WebEngine browser prototype. The prototype provides a native window, address bar, back/forward/reload/home controls, and a real embedded Chromium-based page renderer.

## Current target

- Windows x64 and Linux x86_64 CI builds
- Qt 6.8.3 with Qt WebEngine (pinned in CI)
- Dark navy / cyan visual direction
- A deterministic `--smoke-test` mode that loads local HTML and verifies its title

This is an early prototype, not yet a stable or security-hardened browser. It does not yet provide full tab management, bookmarks, history, downloads, private sessions, installer packaging, or an AppImage. Windows x86 is not targeted by this Qt WebEngine build.

## Local build

Install Qt 6.8.3 with the **Qt WebEngine** and **Qt WebEngine Widgets** components, plus CMake 3.21+ and a C++17 compiler.

```sh
cmake -S desktop -B desktop/build -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build --config Release
./desktop/build/veyra --smoke-test
```

On Linux, the smoke test requires a graphical display; on headless machines use `xvfb-run -a desktop/build/veyra --smoke-test`. For normal use, launch `veyra` without `--smoke-test`.

## Security and release policy

The embedded renderer uses Qt WebEngine's Chromium runtime. Pin and regularly update the Qt patch release, review Qt/Chromium security advisories, and include required Qt/Chromium license notices before public packaging. Do not publish CI preview artifacts as stable releases until runtime deployment, actual page rendering, package launch, and SHA-256 checks are verified. No signing keys or tokens belong in the repository.
