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

**Planning / Early Development**

The project is currently in the design and architecture phase. No runnable application exists yet.

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
│   │   │   ├── platform/           # OS-specific code: OS detection, app data/cache/log folders
│   │   │   ├── db/                 # SQLite: connections, single writer thread, migrations, settings, library folders
│   │   │   └── index/              # Media indexer: folder walk, metadata reading, batched writes, progress
│   │   └── resources/
│   │       ├── logback.xml         # Logging setup (rotating log file)
│   │       └── db/migration/       # Numbered schema scripts (V1__..., V2__...)
│   └── test/
│       ├── java/...                # Unit tests, mirroring the main packages
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

New packages appear as features land (indexer, thumbnails, UI, …).

---

## Contributing

Prismo is in its earliest stages. Contributions are welcome once the project has a buildable skeleton.

In the meantime, feel free to open an issue to discuss ideas, suggest features, or report problems with the design.

---

## License

This project is licensed under the [MIT License](LICENSE).
