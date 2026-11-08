# Veyra Desktop (preview development)

Veyra Desktop embeds **Chromium through the Chromium Embedded Framework (CEF)**. CEF is used instead of Qt WebEngine because it ships an official **Windows x86 (32-bit)** build in addition to Windows x64 and Linux x86_64, which are the three desktop targets for the first release.

## Status

- Builds and renders the Veyra start page (`assets/ui/home.html`) in a native Chromium window.
- CI builds Windows x64, Windows x86 and Linux x64 and runs `veyra --smoke-test`, which loads the shipped start page, executes its JavaScript inside Chromium and checks the resulting page title.
- Not implemented yet: custom tab strip / toolbar UI, bookmarks, history, downloads manager, private windows, installers and the Linux AppImage. The window currently uses CEF's default Chromium-style browser frame.
- Preview artifacts are not stable releases and are not code-signed.

## Engine

- CEF binary distribution pinned in `desktop/CMakeLists.txt` (`VEYRA_CEF_VERSION`), downloaded at configure time into `desktop/third_party/cef` (git-ignored). Override with `-DVEYRA_CEF_VERSION=...` after checking <https://cef-builds.spotifycdn.com/index.html>.
- The CEF/Chromium version must be bumped regularly for security fixes.
- `desktop/src/shared` and most of `desktop/src/app` are derived from the official [cef-project](https://github.com/chromiumembedded/cef-project) template (BSD-style licence, see `third_party_licenses/`).

## Build

Requirements: CMake 3.21+, and
- **Windows:** Visual Studio 2022 (C++ desktop workload), Windows 10+. x64: `-A x64`, x86: `-A Win32`.
- **Linux:** GCC/Clang, `libgtk-3-dev`, `libnss3`, `libxss1`, `libasound2` (Ubuntu 22.04 tested in CI).

```sh
# Windows (PowerShell), pick -A x64 or -A Win32
cmake -S desktop -B desktop/build -G "Visual Studio 17 2022" -A x64
cmake --build desktop/build --config Release
desktop\build\Release\veyra.exe

# Linux
cmake -S desktop -B desktop/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build
desktop/build/Release/veyra
```

Smoke test: `veyra --smoke-test --smoke-test-output=result.txt` (use `xvfb-run -a` on headless Linux). The file must contain `VEYRA_SMOKE_OK|` followed by the Chromium user agent.

## Linux sandbox

Chromium's sandbox needs `chrome-sandbox` to be owned by root with mode 4755. CI containers and AppImages cannot do that, so CI runs with `--no-sandbox`. Do not distribute a build that disables the sandbox for normal browsing without telling users.
