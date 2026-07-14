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
- **No modifications** — your files are never touched, moved, renamed, or re-encoded

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
| UI | JavaFX |
| Core | Java 21 LTS (virtual threads) |
| Database | SQLite + FTS5 |
| Image decoding | ImageIO, TwelveMonkeys (WEBP/HEIC), libraw (RAW) |
| File monitoring | java.nio.file.WatchService (behind a pluggable abstraction) |
| Packaging | jlink + jpackage (bundled runtime, no Java installation required) |

---

## Status

**Planning / Early Development**

The project is currently in the design and architecture phase. No runnable application exists yet.

- [Project Outline](docs/PROJECT.md) — full design document

---

## Roadmap

### v0.1 — Engineering Skeleton

Prove the core loop works and feels fast:

```
Timeline · Folder Explorer · Collections (Folders/Albums)
Viewer · Search · Background Indexing · Thumbnail Cache · Packaging
```

### v1.0 — MVP

Everything in v0.1, plus the baseline features that make it feel like a real gallery:

```
Favorites · Hidden (OS-level + Ignored Paths) · Trash
Filters · Sort · Metadata Panel · Selection Mode · Multiple Locations
```

---

## Project Structure

```
prismo/
├── docs/
│   └── PROJECT.md          # Full project outline and design document
└── README.md
```

Additional directories (`src/`, `assets/`, `scripts/`, etc.) will appear as development begins.

---

## Contributing

Prismo is in its earliest stages. Contributions are welcome once the project has a buildable skeleton.

In the meantime, feel free to open an issue to discuss ideas, suggest features, or report problems with the design.

---

## License

*License to be determined.*
