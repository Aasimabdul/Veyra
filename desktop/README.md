# Veyra Desktop (development status)

Veyra Desktop is currently a **buildable native shell scaffold**, not yet a full browser. The desktop web-rendering engine has not been integrated into the public `main` branch, so this target must not be advertised or released as a functional browser yet.

## Current CI

The Desktop Shell CI workflow builds on Windows and Linux, runs the scaffold executable as a smoke test, and uploads short-lived artifacts for inspection:

- `veyra-desktop-windows-shell` — Windows executable
- `veyra-desktop-linux-shell` — Linux executable

These artifacts prove only that the shell compiles and launches. They do **not** prove that web pages can be rendered.

## Local build

Requirements: CMake 3.20+ and a C++17 compiler.

```sh
cmake -S desktop -B desktop/build -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/build --config Release
```

Linux smoke test:

```sh
./desktop/build/veyra
```

Windows (PowerShell):

```powershell
.\desktop\build\Release\veyra.exe
```

## Before calling this a browser

1. Recover and identify the desktop engine SDK that is available in the development environment.
2. Commit/document the SDK version and acquisition method; do not commit proprietary or bulky engine binaries unless their license permits it.
3. Integrate the engine into CMake and implement a real browser window, navigation, address bar, loading/error states, and safe shutdown.
4. Add CI setup for the engine on both Windows and Linux, then verify actual page rendering with a deterministic smoke test.
5. Package and test the real application (Windows installer or portable package, and Linux AppImage) only after those checks pass.

No signing keys, tokens, or other secrets belong in the repository.
