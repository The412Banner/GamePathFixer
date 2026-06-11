# Game Path Fixer

Fix a game's startup path in GameHub-based builds when you've moved or renamed the
game folder and the app's **PC Game Settings** stops showing up.

The game library lives in the app's private SQLite database (`ux_db`,
table `t_game_library`), where the exe path is stored twice — in the
`package_name` column and in the `filePath` field of the row's JSON blob.
If either points at a folder that no longer exists, the game breaks.

## How it works — no root needed

Builds that ship with the **MT data files provider** expose their private data
folder through Android's documents picker (SAF). This app:

1. **Detects every installed build** that has the provider and lists them
   (label + package name), since there are multiple build variants.
2. You grant access to that app's data folder once (system folder picker).
3. **Lists every game** in its library with the current exe path and a
   found/missing badge.
4. Tap a game, **browse to the new .exe** (or type the path), hit **Save**.

Save does the full safe sequence automatically: re-reads the live DB (WAL
included), updates both path fields, checkpoints the WAL, writes the DB back
through the provider, and deletes the stale `-wal`/`-shm` files so SQLite
can't replay the old path over the fix.

> ⚠️ Force-stop the game app before saving (Settings → App info → Force stop) —
> there's a shortcut button in the app. If your device is rooted, Game Path
> Fixer force-stops it for you automatically.

Optional: grant *All files access* so the app can show the ✓ found / ✗ missing
badge and validate the new path before saving.

## Install

Grab the APK from [Releases](../../releases) (or the latest build artifact
under Actions) and install it.

## Build

Built on GitHub Actions: Gradle 8.10.2, AGP 8.7.3, Kotlin 2.1.0, Jetpack
Compose. Every push builds a signed APK artifact; tags `v*` create a release.
