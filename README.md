# My Video Library (مكتبة الفيديو)

A **private, offline-first** personal video library for Android. Built for a single
device — no cloud, no accounts, no analytics, no tracking. All data lives locally in
an **encrypted** database inside the app's private storage.

> This is a personal application, not intended for Google Play. Downloading from
> third‑party platforms is provided for personal use only and is your responsibility;
> extraction from platforms such as YouTube/TikTok is inherently fragile and may break
> when those platforms change.

## Status — foundation (phases 0–4)

Implemented and wired end‑to‑end:

- **Project scaffold** — Gradle 8.9, AGP 8.6, Kotlin 1.9, KSP, Hilt, Material 3.
- **Encrypted local database** — Room + **SQLCipher**, key held in the Android
  Keystore via `EncryptedSharedPreferences` (never stored in plaintext).
- **Library dashboard** — grid/list views, Paging 3, search, sort (name/date/
  duration/size), folder filters, favorites, multi‑select (delete/move/favorite),
  storage usage, duplicate detection query.
- **Import** — scan device videos through MediaStore (scoped storage), read
  metadata, generate thumbnails, add to the library.
- **Player** — Media3 **ExoPlayer**: resume position, playback speed, rotation,
  picture‑in‑picture, brightness/volume gestures, lock controls, background toggle,
  subtitle button.

### Planned next (later phases)

- Download manager (WorkManager + foreground service, queue, pause/resume).
- Provider system with **TikTok** and **YouTube** modules (isolated, independently
  updatable).
- Security screen (PIN, biometric, hide‑from‑recents, screenshot blocking).
- Settings + encrypted backup/restore.

## Architecture

MVVM + Clean Architecture + Repository pattern, with Hilt DI.

```
data/
  local/        Room entities, DAOs, AppDatabase, SQLCipher key manager
  repository/   Video / Folder / Settings repositories (interfaces + impls)
  model/        Enums (source, sort, view mode, theme, download status)
di/             Hilt modules (database, repositories)
ui/
  main/         Library dashboard (MainActivity, LibraryViewModel, adapter)
  importer/     Device import (ImportActivity, ImportViewModel, adapter)
  player/       Media3 player (PlayerActivity, PlayerViewModel)
util/           MediaStore scanner, thumbnail/metadata, storage, formatters
```

## Tech stack

Kotlin · XML layouts · Room + SQLCipher · Hilt · Coroutines/Flow · Paging 3 ·
Retrofit/OkHttp/Gson · Glide · Media3 ExoPlayer · WorkManager · Material 3.

- **minSdk** 24 · **targetSdk / compileSdk** 35

## Build

Requires the Android SDK. Create `local.properties` with your SDK path:

```
sdk.dir=/path/to/Android/sdk
```

Then:

```bash
./gradlew assembleDebug      # build a debug APK
# output: app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and Run.

## Privacy notes

- Cloud backup and device‑to‑device transfer are disabled
  (`data_extraction_rules.xml`, `allowBackup=false`).
- The database is encrypted at rest; the key is generated once and kept in the
  Keystore.
- Imported device videos are referenced by their MediaStore URI (no duplicate copy);
  downloaded videos (later phase) will live in app‑private storage.
