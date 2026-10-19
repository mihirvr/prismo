# Prismo

**A fast, offline-first desktop media library for Windows and Linux.**

> Browse your memories like a phone. Manage your files like a desktop.

Prismo is a local media browser inspired by modern mobile galleries — Samsung Gallery, Google Photos, Apple Photos — built for the desktop. It indexes your existing folders and gives you a beautiful, searchable, timeline-based view of your media without ever importing, duplicating, or uploading a single file.

---

## Features (Vision)

- **Timeline view** — all media, chronological, grouped by day/month/year
- **Folder explorer** — browse your actual filesystem, with recursive aggregation
- **Albums** — auto-generated from media-containing folders
- **Instant search** — filename, folder, extension, camera, date, resolution (SQLite FTS5)
- **Favorites, hidden content, soft-delete trash** — gallery essentials, built in
- **Filters** — Photos · Videos · GIFs · RAW · by format · by orientation
- **Background indexing** — non-blocking, incremental, cache-first thumbnails
- **Multiple locations** — unified library across drives, USB, NAS
- **Disconnected drive support** — external drives show as offline, not deleted
- **Viewport virtualization** — only visible thumbnails are decoded
- **Your files stay yours** — Prismo never changes, moves, renames, re-encodes or deletes a file on its own; files change only when you run a file operation yourself

---

## Philosophy

1. **Offline-first** — no account, no login, no telemetry, no cloud, no subscriptions
2. **Non-destructive** — files stay exactly where they are, nothing is ever duplicated
3. **Privacy-first** — everything runs locally, nothing leaves your machine
4. **Architecture over language** — performance comes from cache-first design and async indexing, not the implementation language

---

## Tech Stack

| Layer | Choice |
|---|---|
| UI | JavaFX 25 |
| Core | Java 25 LTS (virtual threads, FFM API) |
| Build | Gradle (Kotlin DSL) |
| Database | SQLite + FTS5 (xerial sqlite-jdbc) |
| Metadata | metadata-extractor (EXIF, HEIF, MP4/QuickTime) |
| Image decoding | ImageIO (JPEG/PNG/BMP/GIF), TwelveMonkeys (WEBP), libheif via FFM (HEIC), embedded previews (RAW) |
| File monitoring | java.nio.file.WatchService (behind a pluggable abstraction) |
| Logging | SLF4J + Logback |
| Packaging | jlink + jpackage (bundled runtime, no Java installation required) |

Details, versions and reasons: [docs/TECH_STACK.md](docs/TECH_STACK.md).

---

## Status

**Early development: v1.0 MVP in progress** (v0.1 Foundation and v0.5 Core Loop done)

The app runs (`./gradlew run`). You can add folders ("Library folders…"); Prismo indexes it, makes thumbnails in the background, and shows everything on a date-grouped Timeline. Click a photo (or move with the arrow keys and press Enter) to open it in the viewer: zoom, pan, rotate, slideshow, and MP4 playback. Type in the search box (Ctrl+F) to find files by name, folder, camera, date or resolution. Filter (Photos, Videos, JPEG, Portrait, Screenshots…) and sort (date taken / modified / added, name, size, resolution) the Timeline and search results. Files you add, rename, move or delete in your folders show up in Prismo by themselves within a second or two. The Collections tab has Folders (a folder tree, with or without subfolders), Albums (one per folder), Favorites, Recently Added, Hidden and Trash; press F / H / Delete (or right-click) to favorite, hide or move something to Trash. "Library folders…" adds, turns off or removes the folders Prismo shows. Done so far: Project Setup, Database Layer, Media Indexer, Thumbnail Cache, Timeline, Viewer, Search, Filesystem Watcher, Collections. Next: Packaging. No installer yet.

- [Project Outline](docs/PROJECT.md) — full design document
- [Tech Stack](docs/TECH_STACK.md) — libraries and how the parts fit together
- [Development Guide](docs/DEVELOPMENT.md) — development workflow, rules, and conventions

---

## Roadmap

### v0.1 — Foundation

```
Project Setup · Database Layer · Media Indexer
```

### v0.5 — Core Loop

Prove the core loop works and feels fast:

```
Thumbnail Cache · Timeline · Viewer · Search · Filesystem Watcher
```

### v1.0 — MVP

Everything above, plus the baseline features that make it feel like a real gallery:

```
Collections (Folder Explorer + Albums) · Packaging
Favorites · Hidden (OS-level + Ignored Paths) · Trash
Filters · Sort · Metadata Panel · Selection Mode · File Operations · Multiple Locations
HEIC support · RAW previews · Video Watch Progress
```

---

## Project Structure

```
prismo/
├── docs/
│   ├── PROJECT.md              # What Prismo is: features, scope, roadmap
│   ├── TECH_STACK.md           # Libraries, versions, and implementation approach
│   └── DEVELOPMENT.md          # Development workflow, rules, and conventions
├── gradle/
│   ├── libs.versions.toml      # Every dependency version, in one place
│   └── wrapper/                # Gradle wrapper (pinned Gradle version + checksum)
├── src/
│   ├── main/
│   │   ├── java/io/github/mihirvr/prismo/
│   │   │   ├── Launcher.java       # Entry point (main)
│   │   │   ├── PrismoApp.java      # JavaFX application: startup, main window, shutdown
│   │   │   ├── platform/           # OS-specific code: OS detection, app data/cache/log folders, file reads that never lock a file
│   │   │   ├── db/                 # SQLite: connections, single writer thread, migrations, settings, library folders
│   │   │   ├── index/              # Media indexer: folder walk, metadata reading, batched writes, progress; removes gone files, follows moves
│   │   │   ├── thumbs/             # Thumbnail cache: subsampled decode, EXIF rotation, JPEG files on disk, on-screen-first queue
│   │   │   ├── ui/                 # Timeline: virtualized grid of date-grouped rows, thumbnail tiles, loader, keyboard focus; shared Filters + Sort; page scopes, item actions, album grid
│   │   │   ├── search/             # Search: query words → FTS5/LIKE SQL, background search thread, search box
│   │   │   ├── collections/        # Collections tab: folder tree, albums, Favorites / Recently Added / Hidden / Trash, favorite-hide-trash-delete actions, Library folders dialog
│   │   │   ├── watch/              # Filesystem watcher: whole-tree (Windows) or per-folder watching, debounced batches
│   │   │   └── viewer/             # Media viewer: full-size stills, zoom/pan/rotate, slideshow, MP4 playback
│   │   └── resources/
│   │       ├── logback.xml         # Logging setup (rotating log file)
│   │       ├── db/migration/       # Numbered schema scripts (V1__..., V2__..., V3__search = FTS5 index)
│   │       └── ui/prismo.css       # The app's stylesheet
│   └── test/
│       ├── java/...                # Unit tests, mirroring the main packages (+ testing/ shared helpers)
│       └── resources/              # Test-only logging config and test migration scripts
├── build.gradle.kts            # Build: Java 25 toolchain, JavaFX, dependencies, run/test settings
├── settings.gradle.kts         # Project name + JDK auto-download plugin
├── gradlew / gradlew.bat       # Run Gradle without installing it (./gradlew build)
├── .gitattributes              # Line endings (keeps gradlew working on Linux)
├── .gitignore
├── LICENSE
└── README.md
```

Build and run: `./gradlew run` · tests: `./gradlew test`.

New packages appear as features land (packaging, settings, …).

---

## Contributing

Prismo is in its earliest stages. Contributions are welcome once the project has a buildable skeleton.

In the meantime, feel free to open an issue to discuss ideas, suggest features, or report problems with the design.

---

## License

This project is licensed under the [MIT License](LICENSE).
