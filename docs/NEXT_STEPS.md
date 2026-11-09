# Handoff notes (for any developer or AI continuing Veyra)

State of `main` after the "private folder / free VPN / dock" work. Everything below compiles in CI but has **not** been run on a real phone unless stated.

## Needs a real-device check first
1. Free VPN: Settings > VPN > "Turn on free VPN". If Cloudflare's unofficial registration (`WarpProvisioner.register`) fails, read the toast. Fall back to importing a `.conf`.
2. Private folder: Downloads > Private folder asks for the screen lock; try "Private" on a detected video and on a pasted mp4 link.
3. Scroll pages no longer paint under the dock / nav bar (`contentArea.clipChildren` is true only while a `scrollPage()` is shown).
4. Home dock restyle (`addMainNav`) and wallpaper thumbnails in Settings > Appearance.

## Known gaps / ideas
- VPN country choice needs real servers (not provided). Kill switch and split tunnelling are not implemented.
- Private folder is not encrypted at rest (sandbox only). A streaming AES-CTR `DataSource` for Media3 would allow real encryption.
- Downloads only run while the app process lives (no foreground service).
- Dock colours should all come from `Palette`; `addBrowserBar` and `addMainNav` share the same recipe and could be one helper.
- No benchmark numbers exist for speed. Current tweaks: console output and remote debugging off, background tabs deactivated.

## How to build / verify
CI (`.github/workflows/android.yml`) runs unit tests, lint, debug build and emulator screenshots (`ci-screenshots` branch). `first-release.yml` publishes signed pre-releases. No local Gradle: Maven is not reachable from the agent sandbox.
