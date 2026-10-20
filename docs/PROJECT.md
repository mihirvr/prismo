# Prismo — Project Outline

> This document says **what Prismo is and does**. Libraries and implementation approach: `TECH_STACK.md`. Workflow and rules: `DEVELOPMENT.md`.

## Vision

A fast, offline-first desktop media browser for **Windows and Linux** that feels like a phone gallery, not a file explorer or DAM tool. Indexes existing folders — never imports, never duplicates.

**Not**: cloud service, file explorer replacement, editing suite, professional DAM tool.

**Uncapped Library Vision**: The application will never enforce artificial freemium library caps (e.g., 5,000 or 20,000 image limits). It is designed to scale infinitely with the user's hardware.

**One-line pitch**: *Browse your memories like a phone, manage your files like a desktop.*

---

## Project Goals

```
✓ Feel like a modern mobile gallery
✓ Never block the UI
✓ Never modify original media
✓ Handle very large libraries without freezing
✓ Be completely offline
✓ Package as a native desktop application
```

## Non-Goals (v1)

```
✗ Cloud storage
✗ Collaboration
✗ Professional photo editing
✗ DAM workflows
✗ Social features
✗ Mobile sync
```

---

## Core Principles

- **Offline-first**: no account, no login, no telemetry, no cloud, no subscriptions
- **Non-destructive**: Prismo never changes, moves, renames, re-encodes or deletes a file **on its own**. Indexing, thumbnails, favorites, hiding and trash all live in Prismo's own database and cache. Files on disk change only when the user explicitly runs a file operation (see File Operations).
- **Mobile-gallery browsing model**: Timeline first, folders second
- **Architecture over language**: performance comes primarily from cache-first design and async indexing, not the implementation language

---

## Scope Tiers

These match the GitHub milestones and issues #1–#10 (see `DEVELOPMENT.md`).

### v0.1 — Foundation
A clean project, a database, and an indexer that fills it:
```
Project Setup (#1) · Database Layer (#2) · Media Indexer (#3)
```

### v0.5 — Core Loop
Prove the core loop works and feels fast before anything else:
```
Thumbnail Cache (#4) · Timeline (#5) · Viewer (#6) · Search (#7) · Filesystem Watcher (#8)
```

### v1.0 — MVP
Everything above, plus the expected baseline features that make it actually feel like a phone gallery:
```
Collections: Folder Explorer + Albums (#9) · Packaging (#10)
Favorites · Hidden (OS-level + Ignored Paths) · Trash
Filters · Sort · Metadata Panel · Selection Mode · File Operations · Multiple Locations
Broken & Unavailable Media · HEIC support · RAW previews
Video Watch Progress (thumbnail bar + resume playback)
```
The v1.0 items without an issue number still need GitHub issues.

### Deferred to Post-MVP
Gallery Lock (PIN), duplicate finder, AI/content search, face recognition, OCR, basic editing, plugins, Workspaces, cloud sync. (Full list at the bottom.)

---

## Navigation

```
🏠 Timeline   📂 Collections   🔍 Search   ⚙ Settings
```

---

## Timeline (Home)

Default landing page. All media types (photos, videos, GIFs, RAW) mixed, chronological.

- **Grouping**: Smart (Today / Yesterday / Month — default) or explicit Day / Week / Month / Year
- **Sort**: shared sort component (see below)

---

## Collections

```
Folders
Albums
Favorites
Recently Added
Hidden
Trash
```

(Media Types is not a separate Collections entry — merged into Filters, available everywhere.)

---

## Folder Explorer

- Only media-containing folders appear
- Parent folders automatically aggregate media from every nested folder — no need to browse five levels deep
- **Recursive View**: ON (aggregated) / OFF (traditional explorer behavior)
- Sort via shared sort component, plus item count

---

## Albums

- Auto-generated per media-containing folder (Downloads, WhatsApp, Screenshots, Camera, etc.)
- Each shows cover image + media count
- No manual import/creation in v1 — custom/manual albums are post-MVP

---

## Filters (merged with Media Types)

Available on every page. Changes the current view only, never the underlying library.

```
All · Photos · Videos · GIF · RAW
JPEG · PNG · HEIC · WEBP
Landscape · Portrait · Screenshots
Favorites · Hidden
```

---

## Sort (single shared component)

`Date Taken / Date Modified / Date Added / Name / Size / Resolution` + one ascending/descending toggle.

Used identically across Timeline, Folders, Albums, and Search — no duplicated or conflicting sort definitions per page.

---

## Search

Instant search across: filename, folder name, extension, camera, date, resolution.

- GPS/location search → post-MVP (needs map view UI)
- GPS still *displays* in the metadata panel if present in EXIF

---

## Viewer

- Zoom, pan, rotate (view only — the file is not changed), next/previous, fullscreen, slideshow, video playback
- Keyboard shortcuts, mouse gestures
- **Video formats**: every video type Prismo indexes plays in the app (MP4, M4V, MOV, MKV, WebM, AVI), through a small FFmpeg-based player (changed before #10 from JavaFX Media, which couldn't play AAC sound on the development machine). Phone videos are shown upright. A file that can't be played shows why, with its thumbnail, and an "Open with default player" button.

**Metadata panel** (optional side panel): resolution, file size, camera, lens, ISO, aperture, GPS (if present), date taken, date modified, file path.

---

## Video Watch Progress

Two linked features, both video-only:

1. **Thumbnail progress bar** — thin colored strip along the bottom edge of a video's thumbnail, width proportional to `playback_position_seconds / duration_seconds`. Rendered as a live UI overlay at display time, sourced from DB values — **not** baked into the cached thumbnail bitmap, since progress changes constantly and the thumbnail cache is meant to be generate-once. Once position crosses a "fully watched" threshold (e.g. ≥90-95% of duration, to account for trailing credits/black frames), the bar renders full-width in a distinct color.
2. **Resume playback** — on opening a video, seek automatically to the saved position (silent resume, VLC-style). No confirmation prompt by default; flag if a "Resume from 12:34?" prompt is preferred instead.

**Data** (added to `Media`, video rows only): `duration_seconds`, `playback_position_seconds`.

- `duration_seconds` read once during indexing from the file's MP4/QuickTime metadata (`metadata-extractor`) — not from JavaFX `MediaPlayer`, which would mean creating a player per file and only works for formats JavaFX can play
- `playback_position_seconds` written periodically during playback (~every 5-10s) plus on pause/stop/app-close

**Optional extension (not in v1 by default)**: a Watched / Unwatched / In Progress filter and sort option, same category as existing Favorites/Hidden filters — cheap to add later since the underlying data already exists.

---

## Favorites / Recently Added

- Favorites: one-click toggle, appears in Collections. Stored as a boolean column on `Media` — not a separate table (see Database section).
- Recently Added: sorted by `date_added` (when Prismo first indexed the file), not Date Taken

---

## Hidden Content System

1. **OS Hidden** — toggles: Hide Hidden Files / Hide Hidden Folders / Include Hidden Media
2. **Ignored Paths** — user-defined, can ignore folders, entire drives, extensions, or individual paths (e.g. `node_modules`, `.git`, `$RECYCLE.BIN`, `System Volume Information`, `Android/data`)
3. **Gallery Lock** *(post-MVP)* — PIN-protected, app-level hiding from Timeline / Search / Collections / Folder Explorer until unlocked. Windows Hello / Linux auth support → further out. **Note**: this hides items from the index view — it does not encrypt files on disk.

---

## Trash

App-level soft delete (flag + hidden from all views) — consistent across Windows/Linux/network drives, unlike relying on the OS recycle bin (which behaves inconsistently across platforms and doesn't exist for network drives).

- **Delete** = move to Prismo's Trash. The file stays on disk, untouched; only the database flag changes.
- **Restore** = clear the flag.
- **Delete permanently** (from Trash only, after a confirmation dialog) = the file is removed from its folder: into the operating system's Recycle Bin where Java can use it (Windows), otherwise deleted for good. The dialog says which one will happen. This is the only way Prismo removes a file. (Decided in #9.)
- Auto-purge after N days would delete real files without the user present, so it is **off by default** and its policy is an open question.

---

## Broken & Unavailable Media

Handle these explicitly rather than silently failing or losing index state:

- **Missing files** — indexed but no longer found on disk (deleted outside the app, or on a disconnected drive)
- **Corrupted images** — file exists but fails to decode
- **Unsupported formats** — recognized but not renderable

**Disconnected drives**: don't wipe the drive's entries from the database when it's unavailable. Instead show it as offline:
```
📀 External SSD — Unavailable
```
Reconnect later and the index resumes without a full rescan. (This is how Lightroom handles offline volumes.)

---

## Duplicate Indexing (not duplicate *finding*)

Copies like `photo.jpg` and `photo (1).jpg` are indexed as separate, independent entries in v1 — no merge/skip logic. A dedicated duplicate finder (exact + visually similar) that *suggests* removal is a post-MVP feature layered on top of this, not a v1 requirement.

---

## Selection Mode

Desktop equivalent of long-press. Supports: delete, copy, move, favorite, hide, rename, open containing folder.

---

## File Operations

Consolidated here rather than scattered across Selection Mode and the right-click menu. These are the **only** actions that change files on disk, and each one runs only when the user asks for it:

**Supported (v1)**:
```
Copy · Move · Rename · Delete · Restore
Open · Open With · Reveal in Folder
```

**Post-MVP**:
```
Batch Rename · Convert · Compress
```

---

## Thumbnail Cache

- Generated once, stored locally, background-generated, lazy-loaded
- Regenerates only on file change or manual rebuild (Settings > Cache)
- Cache-first display is the single biggest UX lever in the app (see Architecture)

---

## Indexing Strategy

**First launch:**
```
Scan selected folders
        ↓
Extract metadata
        ↓
Generate tiny (placeholder) thumbnails
        ↓
Store SQLite entry
        ↓
Generate full-size thumbnails lazily, in background
        ↓
Done
```

**Ongoing (post-launch) changes:**
```
Filesystem change detected
        ↓
Queue
        ↓
Worker thread (virtual thread pool)
        ↓
Update SQLite
        ↓
Refresh UI incrementally
```

- **Local / USB drives**: real-time filesystem monitoring — detects new files, deleted files, renamed files/folders, drive connect/disconnect
- **Network / NAS drives**: manual or scheduled rescan only — real-time watch is not reliable over SMB/NFS
- **Rename/move detection**: a plain OS rename event often looks identical to delete+create and cannot be assumed to "just work". Built in #8: a gone file and a new file with the same size, modified time and media type are the same file (same name preferred), so favorite/hide/progress state survives a move. Windows gives Java no file ID, so this works the same on every OS. Details in `TECH_STACK.md`.

---

## Multiple Locations

One unified library spanning several enabled paths (e.g. `C:\Pictures`, external SSD, USB, NAS). Locations can be toggled on/off individually.

This is deliberately *not* separate Workspaces/library profiles — that's a bigger, isolated-library feature reserved for post-MVP.

---

## Settings

- **Appearance**: Light / Dark / AMOLED / Follow System
- **Timeline**: grouping, sort, thumbnail size
- **Folder Explorer**: recursive view, folder covers, preview size
- **Hidden**: respect OS hidden, ignored paths, Gallery Lock
- **Cache**: cache size, clear cache, rebuild cache
- **Performance**: background indexing speed, CPU limit, memory limit

---

## UX Features

- Infinite scrolling, no pagination
- Smooth zoom (Ctrl + scroll / trackpad pinch)
- Thumbnail size slider (tiny → huge)
- Drag & drop (move, copy, create albums)
- Right-click menu: open, open with, open folder, rename, move, copy, delete, favorite, hide, properties
- Keyboard shortcuts: arrows, space, delete, Ctrl+A/C/V, F11, Esc

---

## Database

One `Media` table remains the core of the philosophy — every page is a filtered/sorted *view* of it, nothing duplicated, nothing imported, everything points to the original file on disk. Frequently-toggled per-item state (favorite, hidden) stays as columns on `Media` rather than separate tables, to preserve that "one table, many views" model:

```
Media        → id, path, type, date_taken, date_modified, date_added,
               resolution, favorite (bool), hidden (bool), broken (bool),
               trashed_at (null = not in Trash; used for auto-purge),
               duration_seconds (video only), playback_position_seconds (video only), ...
Folders      → structural entries for the Folder Explorer / Albums
Drives       → tracks connect/disconnect state for Multiple Locations
Tags         → post-MVP, user-defined
IgnoredPaths → user-configured exclusions
Settings     → app config
```

`Cache` is intentionally not a table — thumbnails live as files on disk, with at most a lightweight manifest for cache invalidation. `HiddenItems` and `Favorites` are not separate tables — see above.

```
Timeline   → everything, sorted by date
Folders    → everything, grouped by filesystem path
Albums     → everything, grouped by folder
Favorites  → favorite = true
Videos     → type = video
Hidden     → hidden = true
Trash      → trashed_at is not null
```
(Every view except Trash also filters out `trashed_at is not null`.)

---

## Architecture

```
Filesystem
     ↓
Scanner (first run / rescan)  +  File Watcher (live changes)
     ↓
Queue
     ↓
Metadata Extractor
     ↓
DB Writer (single thread) ──→ SQLite Index (Media table + FTS5)
     ↓                                   ↑
Thumbnail Generator (background)         │ queries / search
     ↓                                   │
Thumbnail Cache (files on disk) ──→ Gallery UI
```

The UI never waits on the scanner or the thumbnail generator. It reads what is already in SQLite and the cache, and updates when new rows or thumbnails arrive. Thread layout and the database write model are in `TECH_STACK.md`.

Fully local. No servers, no uploads, no internet dependency.

**The key architectural decision — matters more than the language choice:**

- **Bad**: open folder → generate all thumbnails → display (can take many seconds for a large folder)
- **Good**: open folder → show cached thumbnails immediately → generate missing thumbnails in background → update UI incrementally

This cache-first, generate-in-background pattern is the single largest lever on perceived speed — larger than any gain from switching implementation language. Language choice affects the performance *ceiling*; architecture affects what the user actually *feels*.

---

## Design Goals (qualitative — no numbers promised until measured)

- Large library support, without a defined ceiling assumed in advance
- No UI blocking during indexing or thumbnail generation
- Incremental indexing, not full rescans
- Background thumbnail generation
- Cache-first rendering
- Multi-threaded indexing and thumbnail generation

Internal note: only state real numbers (search latency, cold start time, etc.) once actually benchmarked post-implementation — e.g. "100,000 photos, average search 18ms, cold startup 420ms" is the kind of claim to earn, not assume.

---

### Storage & OS Paths

Application data is decoupled from the installation directory and follows platform conventions:
- **Windows**: `%LOCALAPPDATA%\Prismo\` (database), `\cache\` (thumbnails), `\logs\`
- **Linux**: `$XDG_DATA_HOME/prismo` (database), `$XDG_CACHE_HOME/prismo` (thumbnails), `$XDG_STATE_HOME/prismo/logs` (logs) — defaulting to `~/.local/share`, `~/.cache` and `~/.local/state` when the variables are unset

Full table in `TECH_STACK.md`. macOS is not a target.

---
## Post-MVP Roadmap

- AI/content search ("dog", "sunset", "receipt")
- Face recognition (local-only, optional)
- OCR for text inside images
- Duplicate detection (exact + visually similar)
- Basic editing — rotate, flip, crop only, and later than everything else on this list. **Open conflict to resolve before building**: crop is inherently destructive, but the app's core promise is never modifying the original file. Needs either a Lightroom-style non-destructive edit stack (stored edits, re-rendered at view time) or an explicit decision to break the non-destructive promise for this one feature.
- Map view (GPS-based browsing)
- Memories ("On this day", yearly highlights)
- Tags & smart collections (user-defined, saved filters)
- Cloud sync (optional)
- Plugin system (community extensions for formats/metadata/tools) — do not touch until core app is solid
- Mobile companion app / remote library access
- Workspaces (isolated library profiles)
- Windows Hello / Linux auth for Gallery Lock
- Live Photos support
- Batch Rename / Convert (including HEIC to JPEG) / Compress (file operations)

---

## Tech Stack

Moved to **`TECH_STACK.md`**: libraries, versions, Java/JavaFX version, threading model, database write model, packaging approach, and why Java.

**Distribution**: `.msi`/`.exe` (Windows), `.deb`/`.rpm` (Linux), each bundling its own Java runtime, so the user never installs Java. AppImage is optional and needs an extra tool. Same model as IntelliJ IDEA, Android Studio, DBeaver, DataGrip.

---

## Open Questions Before Build

- Confirm target library size to design/test against
- Confirm trash retention/auto-purge policy (auto-purge deletes real files)
- ~~Video thumbnails: confirm bundling an LGPL `ffmpeg` build (decide in #4)~~ Decided before #10: FFmpeg (LGPL v3 build, through JavaCV) is bundled; it plays videos and makes their thumbnails. See TECH_STACK.md, "Video"
- HEIC: bundle `libheif` native libraries per OS, or rely on the Linux distro package?
- Confirm the non-destructive-editing conflict resolution (before crop/rotate/flip get built, even as post-MVP)

---

## Resume Pitch (design-intent framing — keep in this tense until measured)

> Designing a cross-platform offline desktop gallery application with recursive media indexing, real-time filesystem monitoring, SQLite/FTS5-backed search, multi-threaded thumbnail generation, and a UI architected to handle large local media libraries.

Switch to past tense, and add real benchmark numbers, only once actually measured.
