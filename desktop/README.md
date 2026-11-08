# Veyra Desktop (development status)

Veyra Desktop is currently a **buildable native shell scaffold**, not yet a full browser. The desktop web-rendering engine has not been integrated into the public `main` branch, so this target must not be advertised or released as a functional browser.

## Current CI

The Desktop Shell CI workflow builds on Windows and Linux, runs the scaffold executable, and uploads short-lived artifacts for inspection:

- `veyra-desktop-windows-shell` — Windows executable
- `veyra-desktop-linux-shell` — Linux executable

These artifacts prove only that the shell compiles and launches. They do **not** prove that web pages can be rendered. The current workflow does not yet provide the requested real-page `--smoke-test`; that test depends on integrating an actual rendering engine first.

## Design and assets

The desktop design target is minimalist neumorphism: navy background `#0F1A2B`, raised shadows `#070E19` / `#182A42`, primary text `#E8F0FB`, muted text `#8FA3BF`, and cyan accent `#39D8FF`. The planned app icon is in `desktop/assets/veyra-icon.svg`. This is a design specification, not a claim that the current console scaffold already implements the final browser UI.

## Local build

Requirements: CMake 3.20+ and a C++17 compiler.

```sh
cmake -S desktop -B desktop/build -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build --config Release
```

Linux scaffold launch:

```sh
./desktop/build/veyra
```

Windows scaffold launch (PowerShell):

```powershell
.\desktop\build\Release\veyra.exe
```

## Before calling this a browser

1. Recover and identify the engine SDK available in the development environment.
2. Commit/document the SDK version and acquisition method; do not commit proprietary or bulky engine binaries unless their license permits it.
3. Integrate the engine into CMake and implement a real browser window, navigation, address bar, loading/error states, and safe shutdown.
4. Add CI setup for the engine on both Windows and Linux, then verify actual page rendering with a deterministic local-HTML title assertion.
5. Verify Windows PE architecture for each Windows target and package the real application (portable ZIPs and Linux AppImage) only after the tests pass.
6. Include engine license notices and document the pinned engine/Chromium security-update process.

The public repository currently does not contain the locally installed engine SDK the user referred to, so CI cannot use it until a reproducible acquisition or build path is committed. No signing keys, tokens, or other secrets belong in the repository.
