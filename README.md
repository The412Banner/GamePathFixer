# Game Path Fixer

A small, no-root utility for **GameHub-based launchers** that lets you edit a
game's library entry directly:

| Feature | What it does |
|---|---|
| 🔧 **Fix the exe path** | Repair a game whose folder you moved/renamed — restores PC Game Settings and launching |
| ✏️ **Edit the display name** | Rename any game in the library to whatever you want |
| 🖼️ **Change the game art** | Replace the library card image with any picture from your gallery |

All of it works **without root** on any build that ships the MT data files
provider — and if you *are* rooted, the app uses root to make saves even safer.

---

## Compatibility — GameHub **v5.x** builds only

This app works with **5.x-generation** GameHub-based builds — the ones that
keep their game library in `databases/ux_db` (table `t_game_library`). That
includes 5.1.x and 5.3.5-base builds under any package name.

> ✅ **Verified & tested on GameHub Lite 5.1.7** — exe path fix, display name,
> and game art changes all confirmed working end-to-end on a real device.

❌ **GameHub 6.0+ builds are NOT supported.** 6.0 moved the game library to a
completely different database (`db_game_library.db`) with a new multi-table
schema. A 6.0 build may still appear in the variant picker (it ships the same
data provider), but loading its library will fail. Don't use this app on 6.0+.

---

## Why this exists

GameHub-based launchers keep the game library in a private SQLite database
(`/data/data/<pkg>/databases/ux_db`, table `t_game_library`). Each entry
stores:

- the **exe path twice** — in the `package_name` column *and* in the
  `filePath` field of the row's `data` JSON blob,
- the **display name** in the JSON `name` field,
- the **card art** as a file path in the JSON `localGameIconPath` field.

If you move or rename a game folder, both stored paths go stale and the
launcher breaks for that game (no PC Game Settings, no launch). There's no
in-app way to fix the path, rename an entry, or change its art — and editing
the database by hand requires root *and* gets silently reverted if the
launcher is still running. This app does the whole dance correctly, for every
installed variant, from one screen.

## How it works — no root needed

These builds bundle the **MT data files provider**
(`<package>.MTDataFilesProvider`), which exposes the app's own private data
folder through Android's documents picker (Storage Access Framework). That
means another app — this one — can read and write `ux_db` with nothing but a
one-time folder grant from you.

1. **Variant detection** — the app scans every installed package for the
   provider and lists all compatible builds (label, package name, version).
   All package-name variants are detected automatically; whatever spoof
   package the build uses, if it has the provider, it shows up.
2. **One-time folder grant** — tapping a build opens the system folder
   picker; choose that app's data root (☰ menu → the app's name → *Use this
   folder*). The grant persists; there's a re-select button in the top bar if
   you ever pick the wrong folder.
3. **Game list** — every library entry with its current exe path and a
   ✓ found / ✗ missing badge (badge needs *All files access*, see below).
4. **Editor** — tap a game to change any combination of:
   - **Path** — browse to the new `.exe` or type the path; validated before
     saving when possible.
   - **Display name** — plain text field.
   - **Game art** — pick any image; you get a current → new preview before
     saving. The image is converted to PNG automatically (HEIC/AVIF/WebP all
     fine), saved to `Pictures/GamePathFixer/`, and the library entry is
     repointed at it.
5. **Save** — one button applies everything in a single safe write.

### The safe-save sequence

SQLite + a live app is a hostile environment for outside edits, so Save does
the full dance every time:

1. Re-pulls the live `ux_db` **including the WAL** (which can hold rows newer
   than the main file).
2. Applies your changes (both path fields, JSON `name`, JSON
   `localGameIconPath`).
3. Checkpoints the WAL so everything lands in the single main file.
4. Writes the DB back through the provider.
5. **Deletes the stale `-wal`/`-shm` files** — otherwise SQLite would replay
   the old values right over the fix on next launch.
6. Re-reads the written DB and verifies the change actually landed.

> ⚠️ **Force-stop the launcher before saving** (Settings → App info → Force
> stop — there's a shortcut button in the editor). A running launcher holds
> the old data in memory and can write it back over your edit.
> **Rooted devices skip this**: the app detects root and force-stops the
> launcher automatically before every save.

## Permissions

| Permission | Why | Required? |
|---|---|---|
| Query all packages | Find installed builds with the data provider | Yes |
| Folder grant (SAF) | Read/write the launcher's `ux_db` | Yes, once per build |
| All files access | ✓/✗ path badges, new-path validation | Optional but recommended |
| Root (if present) | Auto force-stop the launcher before saving | Optional |

No network permission — the app is fully offline. Nothing leaves your device.

## Install

Grab the APK from **[Releases](../../releases)** and install it. Updates
install over the top (same signing key).

## Troubleshooting

- **"Couldn't read ux_db"** — the folder grant points at the wrong place. Use
  the folder icon in the top bar to re-select: in the picker tap ☰, choose
  the launcher's name, then *Use this folder*.
- **Saved, but the launcher still shows the old value** — the launcher was
  running during the save and wrote its in-memory copy back. Force-stop it
  and save again.
- **"The picker returned an empty file"** when choosing art — the photo is
  cloud-only (e.g. Google Photos not on device). Download it first.
- **Game art didn't change but the save succeeded** — make sure the launcher
  was force-stopped; the card art re-reads from disk on next cold start.
- **No compatible apps found** — the installed build doesn't ship the MT data
  files provider; this app can't reach its database without it.

## Build

Plain Gradle Android project — Gradle 8.10.2, AGP 8.7.3, Kotlin 2.1.0,
Jetpack Compose, single activity, no third-party runtime dependencies.

Built on GitHub Actions: every push builds a signed APK artifact; pushing a
`v*` tag publishes a release (`-pre`/`-beta` tags become pre-releases).
