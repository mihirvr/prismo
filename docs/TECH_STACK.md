# Prismo Tech Stack & Working Approach

> This document says **which libraries Prismo uses and how the parts fit together**.
>
> What Prismo does (features, scope, roadmap) lives in `PROJECT.md`. How we work (workflow, rules) lives in `DEVELOPMENT.md`.

Library versions below were checked on Maven Central on **2026-10-15**. They are a starting point, not a pin. The real versions live in `gradle/libs.versions.toml` once the project exists, and each one is re-checked when it is first added.

Anything marked **(verify in #N)** is our current reading and has not been tested yet. Issue #N must check it before the code depends on it.

---

## Summary

| Layer | Choice | Version seen | JPMS module? | Notes |
|---|---|---|---|---|
| Language / runtime | Java 25 LTS | — | — | See "Java and JavaFX version" |
| UI | JavaFX 25 (OpenJFX) | 25.0.4 | yes | Needs JDK 23 or later |
| Build | Gradle (Kotlin DSL) + wrapper | 9.8.1 current, 9.1+ needed | — | Gradle 9.1.0 is the first version that supports Java 25 |
| Database driver | `org.xerial:sqlite-jdbc` | 3.53.4.0 | yes | Bundles SQLite with FTS5 compiled in |
| Metadata | `com.drew:metadata-extractor` | 2.21.0 | **no** (automatic) | EXIF, HEIF, MP4/QuickTime (incl. duration), RAW tags |
| Image decode (JPEG/PNG/BMP/GIF) | `javax.imageio` (JDK) | — | yes | Built into the JDK |
| Image decode (WEBP) | TwelveMonkeys `imageio-webp` | 3.15.3 | **no** (automatic) | Read-only WEBP plugin for ImageIO |
| Image decode (JPEG, robust) | TwelveMonkeys `imageio-jpeg` | 3.15.3 | **no** (automatic) | Replaces the JDK JPEG reader; also reads CMYK/YCCK and slightly broken JPEGs |
| Image decode (HEIC) | `libheif` (C library) via Java FFM | — | — | Native library, comes later in v1.0 |
| RAW | Embedded JPEG preview (v1) | — | — | Full RAW decoding (`libraw`) is post-MVP |
| Video metadata | `metadata-extractor` | — | — | `TAG_DURATION_SECONDS` on MP4/QuickTime directories |
| Video playback | `javafx.media` | — | yes | Limited formats, see "Video" |
| Video thumbnails | Bundled `ffmpeg` (LGPL build), run as a separate process | — | — | Proposal, decide in #4 |
| File watching | `java.nio.file.WatchService` behind our own interface | — | yes | See "File watching" |
| Logging | SLF4J 2.0.x + Logback | slf4j 2.0.20 (stable), logback 1.6.5 | yes | Don't use the SLF4J 2.1 alpha |
| Tests | JUnit Jupiter (JUnit 6) | 6.1.3 | yes | TestFX for UI tests, maybe later |
| Packaging | `jlink` + `jpackage` (JDK tools) | — | — | See "Modules and packaging" |

**Removed from the earlier plan:**
- **HikariCP.** A connection pool does not fix SQLite's "database is locked" errors. See "Database".
- **JNI/JNA for native libraries.** Java's built-in FFM API replaces both.
- **A `module-info.java` for the app.** See "Modules and packaging".

---

## Java and JavaFX version

**Choice: JDK 25 LTS with JavaFX 25.**

Why not stay on Java 21:

1. **Free JavaFX 21 updates have ended.** Oracle's JavaFX download page says free (NFTC) updates for JavaFX 21 run until September 2026 and for JavaFX 25 until September 2028.
2. **Virtual threads block less.** On Java 21, a virtual thread that blocks inside a `synchronized` block stays stuck to its OS thread ("pinning"). JEP 491 (JDK 24) removed nearly all of those cases. Pinning still happens during native calls (JNI, FFM), so that rule still shapes our thread design below.
3. **FFM is final.** The Foreign Function & Memory API (JEP 454, final in JDK 22) lets Java call C libraries like `libheif` directly. With it we don't need JNI glue code or the JNA library.
4. **JavaFX 25 needs JDK 23 or later**, so a newer JavaFX means a newer JDK anyway.

Setup: install a JDK 25 build (e.g. Temurin 25) and let a Gradle toolchain pin it. JDK 21 can stay installed alongside it.

**Native-access warnings.** Since JDK 24, the JVM prints warnings when code uses JNI or FFM unless native access is allowed at launch. sqlite-jdbc uses JNI, and JavaFX uses native code too. The run options (`build.gradle.kts`) use `--enable-native-access=javafx.graphics,javafx.media,ALL-UNNAMED`: `javafx.graphics` checked in #1, `ALL-UNNAMED` (sqlite-jdbc) in #2, `javafx.media` in #6 (without it, JDK 25 warns when the media module's native methods are bound). The installer launcher (#10) needs the same flag.

---

## Modules and packaging

### The problem

`jlink` builds a small custom Java runtime, but it only accepts **real modules**: jars that contain a `module-info.class`. A jar that has only a module name in its manifest (an "automatic module") is rejected. The OpenJDK ticket for adding link-time support for automatic modules (JDK-8130047) is marked "Won't Fix".

We checked the jars directly (`jar --describe-module`) on 2026-10-15:

| Jar | Real module? |
|---|---|
| sqlite-jdbc 3.53.4.0 | yes |
| slf4j-api, logback-classic | yes |
| junit-jupiter-api | yes |
| metadata-extractor 2.21.0 | **no** |
| xmpcore 6.1.11 (pulled in by metadata-extractor) | **no** |
| TwelveMonkeys imageio-core / imageio-webp / common-lang 3.15.3 | **no** |

If Prismo were a module (`module-info.java`) and we ran `jlink` on the whole app, the build would fail on these jars. Workarounds exist (patching a generated `module-info` into each third-party jar, or plugins that merge them), but they're fragile and every new dependency would bring the problem back.

### The approach

1. **The app is a normal classpath app.** It has no `module-info.java`.
2. **`jlink` builds only the runtime:** the JDK modules we need plus the JavaFX modules (from the JavaFX jmods, which are platform-specific). JavaFX then loads as proper named modules from the runtime, which is what JavaFX expects.
3. **`jpackage` takes that runtime image (`--runtime-image`) plus our jars (`--input`)** and builds the installer.
4. To find which JDK modules we need, run `jdeps --print-module-deps --ignore-missing-deps` on the app jars, then add JavaFX's modules (`javafx.controls`, `javafx.media`, `javafx.swing` if we use `SwingFXUtils`).
5. During development, the `org.openjfx.javafxplugin` Gradle plugin puts JavaFX on the module path for `./gradlew run`.
6. We write the `jlink` and `jpackage` steps as our own Gradle tasks in #10, so every flag is visible and understood. A third-party plugin (e.g. beryx `badass-runtime`) is a fallback if that gets painful. The beryx project's own page says it is looking for co-maintainers.

### Installers

- **Types `jpackage` can build:** `app-image`, `exe`, `msi`, `deb`, `rpm` (plus `dmg`/`pkg` on macOS, which isn't a target).
- **AppImage is not one of them.** `app-image` is just a folder with the app and its runtime. Making a real `.AppImage` needs the separate `appimagetool`, which wraps that folder. Treat AppImage as optional.
- **Each OS builds its own installer.** `jpackage` can't cross-build, so Windows and Linux installers need their own CI runners (GitHub Actions).
- **Windows needs the WiX Toolset** to build `msi`/`exe`. Newer JDKs support WiX 3, 4 and 5. **(verify which WiX the JDK 25 `jpackage` wants, in #10)**
- **Linux:** JavaFX needs GTK 3.20 or later. Declare it as a package dependency (`--linux-package-deps`) instead of checking for it with a custom launcher script.
- **Dropped:** the earlier idea of a `.bat`/shell wrapper that checks for DirectX/GTK before starting the JVM. `jpackage` already makes a native launcher, a `.bat` wrapper on Windows would flash a console window, and the Linux package manager already handles GTK.

---

## Threads and concurrency

The main rule: **never block the JavaFX Application Thread.** All disk, database and decoding work runs somewhere else.

| Work | Kind | Runs on |
|---|---|---|
| Walking folders, `stat`-ing files | I/O-bound, pure Java | The indexing thread (`prismo-indexer`), one run or watcher batch at a time |
| Watching folders | Blocking on OS events | One `prismo-watcher` platform thread; batches timed on `prismo-watch-batch`; the 30-minute rescan on `prismo-rescan-timer` |
| Reading metadata (metadata-extractor) | I/O + parsing, pure Java | Virtual threads |
| Writing to SQLite | Native (JNI) | **One dedicated platform thread** (the "writer") |
| Reading from SQLite (UI queries, search) | Native (JNI) | Platform threads, a fresh read-only connection each time: the Timeline on one `prismo-timeline` thread, searches on one `prismo-search` thread. On both, only the newest request runs (built in #7) |
| Decoding images / making thumbnails | CPU-bound | Fixed pool of platform threads, sized to the CPU limit setting (default: cores − 1), at below-normal thread priority. Its queue puts thumbnails on screen first and skips requests for rows that scrolled away. |
| HEIC decode via FFM, ffmpeg | Native / separate process | The same thumbnail pool |
| Viewer: decoding full-size stills | CPU-bound | 2 platform threads at normal priority (someone is waiting for the result) |
| Viewer: opening a video | Native | A short-lived platform thread per video |
| Updating the UI | — | `Platform.runLater`, batched (e.g. a few times per second, not once per file) |

Why native work isn't on virtual threads: a native call pins the virtual thread to its carrier, so we gain nothing and can starve other virtual threads. Platform threads make the cost visible and bounded.

Work moves between stages through **bounded queues**. When the writer falls behind, the scanner waits instead of filling memory (this is called back-pressure).

---

## Database

**Driver:** Xerial `sqlite-jdbc`. Its README says the bundled native library includes FTS5. The jar ships native libraries for Windows x86_64 and Linux x86_64 (checked) and unpacks them into the OS temp folder when it loads.

**Why not HikariCP.** SQLite allows **one writer at a time** for the whole database file. Adding more connections doesn't change that, it only adds more writers fighting over the lock. What works:

1. **WAL mode** (`PRAGMA journal_mode=WAL`). Readers keep reading while the writer writes.
2. **`PRAGMA busy_timeout`** on every connection, so a short lock wait becomes a wait instead of an instant error.
3. **One writer connection, owned by one thread.** Every insert/update/delete is sent to it through a queue.
4. **Batched transactions.** The indexer inserts many rows per transaction (start with a few hundred, then tune by measuring). One transaction per file would be very slow.
5. **A few read-only connections** for the UI and search.

**Migrations:** hand-written and numbered (`V1__init.sql`, `V2__...`) in `src/main/resources/db/`. The current schema version is kept in `PRAGMA user_version`. On startup, run every script whose number is above `user_version`, each in its own transaction. No Flyway: the job is small and doing it by hand teaches how it works.

**Search (FTS5, built in #7):**
- An FTS5 table `media_fts` (migration V3) that mirrors the `media` table (an external-content table), kept in sync by triggers. Checked: the indexer's upsert (`ON CONFLICT DO UPDATE`) fires the update trigger, and FTS5's `integrity-check` passes after insert, upsert, update and delete.
- **`trigram` tokenizer** with `remove_diacritics 1` over file name, folder path, camera make and camera model. It matches any part of a text, ignoring case and accents (`0101` finds `IMG_20240101.jpg`, `cafe` finds `Café`). A search word shorter than 3 characters matches nothing through the trigram index, so those words use a plain `LIKE '%…%'` (with `%`, `_`, `\` escaped).
- **Camera is in the FTS index** (changed in #7, agreed): people type part of a camera name ("nikon", "iphone 13"). Matching it with `LIKE` instead would turn every search into a full table scan.
- Extension needs no column of its own: it's part of the file name. Date and resolution stay normal columns: a search word that looks like a year, month, date or `4000x3000` also matches those columns (see `SearchQuery`).
- Every typed word is passed as a quoted FTS5 string, so search text is never read as FTS5 query syntax.
- **Never walk the date index for a view (measured):** with `ORDER BY sort_date`, SQLite walks `idx_media_sort_date` and fetches every row one at a time in date order, instead of reading the table straight through and sorting. Synthetic, 100,000 rows: a 2-letter search 639 ms vs 35 ms; a Videos filter 520 vs 48 ms; even the whole Timeline 718–739 vs 250–262 ms. So every view and search sorts by `+sort_date` (the unary `+` stops the index being used for the order). This replaced #5's "index walk, no sort" choice.
- **Measured (synthetic, this machine; 100,000 rows written straight to the table, each query through a fresh read connection like the app):** 532 hits 9 ms, 12,576 hits ("goa") 60 ms, 90,079 hits ("img") 305 ms, "2024" (index + date range) 187 ms, a 1-character `LIKE` matching 87,616 rows 310 ms (medians of 5). Index size: 27 MB for 100,000 rows (the `media` table itself: 19 MB). Indexing cost: the real indexer on 10,000 tiny JPEGs took 3.9–4.5 s with the triggers against 1.9–2.3 s without (2 runs each), about 0.2 ms more per file; real photos spend more time on reading, so the share will be smaller (not measured).

---

## Metadata

`metadata-extractor` (by Drew Noakes) is pure Java. Its README lists JPEG, TIFF, WebP, PSD, PNG, BMP, GIF, HEIF (HEIC & AVIF), ICO, PCX, WAV, AVI, QuickTime, MP4, and the RAW formats NEF, CR2, ORF, ARW, RW2, RWL and SRW. DNG isn't named, but DNG is TIFF-based. **(verify DNG in #3)**

Video duration comes from here too: `Mp4Directory` and `QuickTimeDirectory` both have `TAG_DURATION_SECONDS` (checked in the 2.21.0 jar). **This replaces the earlier plan of reading `totalDuration` from JavaFX's `MediaPlayer` during indexing.** That would mean creating a media player per file on the UI toolkit, which is slow and only works for formats JavaFX can play.

---

## Images and thumbnails

**Decoding by format:**

| Format | How |
|---|---|
| JPEG, PNG, BMP, GIF | `ImageIO` (JDK). JavaFX's `Image` class also reads these four directly. |
| WEBP | TwelveMonkeys `imageio-webp`, which plugs into `ImageIO` automatically once it's on the classpath |
| HEIC | `libheif` through FFM. Needs native libraries per OS (libheif + an HEVC decoder such as libde265). Comes in v1.0, after the core loop works. |
| RAW | v1: pull out the JPEG preview that cameras embed in the RAW file. Full RAW decoding with `libraw` is post-MVP. **(verify how preview offsets are read per format, in the RAW issue)** |
| Animated GIF | Thumbnail = first frame. The viewer uses JavaFX `Image`, which handles GIF. |

**Making thumbnails fast:**
1. **Tiny placeholder:** use the small EXIF thumbnail many JPEGs already contain, so no full decode is needed.
2. **Grid thumbnail:** decode with `ImageIO` **subsampling** (`ImageReadParam.setSourceSubsampling`), which reads only every Nth pixel instead of decoding the full image and then shrinking it.
3. Apply the EXIF orientation when making the thumbnail, so the cached file is already the right way up.
4. **Save as JPEG files** on disk (ImageIO can write JPEG, but TwelveMonkeys WEBP is read-only).

**Opening the user's files:** always through `java.nio` (`platform/FileReads`), never `java.io`. On Windows a file open through `FileInputStream` / `RandomAccessFile` can't be deleted or renamed by anyone until it's closed (checked in #8, 6 of 6), and `ImageIO.createImageInputStream(File)` and metadata-extractor's `readMetadata(File)` both open files that way. ImageIO gets a small buffered `ImageInputStream` over a file channel (not ImageIO's stream version, which copies to a temp file by default); measured 2–3 % faster than the old way (synthetic 12 and 24 MP JPEGs, 8 of 8). Still locking: JavaFX's own loading of animated GIFs in the viewer (brief), and a video while it plays.

**Cache layout:** `<cache dir>/thumbs/<size>/<two hex chars from the id>/<media id>.jpg`. The two-character subfolders keep any one folder from holding hundreds of thousands of files. To know when a thumbnail is stale, the thumbnail file's own modified time is set to the source file's modified time when it is written; it is fresh while the two match. No database writes and no cache table. Files that fail to decode get an empty `<media id>.fail` marker stamped the same way, so they aren't retried until they change. Thumbnails are written to a temp file and atomically renamed into place.

**Showing thumbnails in JavaFX:**
- Load cached thumbnails with `new Image(url, width, height, true, true, true)`. The last `true` loads in the background, and the requested size shrinks the image as it loads.
- Keep an in-memory LRU (least-recently-used) cache of `Image` objects with a size cap.
- Converting a decoded `BufferedImage` to JavaFX: a `WritableImage` filled through a `PixelWriter` (chosen in #6, so `javafx.swing` and `SwingFXUtils` aren't needed).

---

## Video

- **Duration:** from `metadata-extractor` during indexing (see "Metadata").
- **Playback:** `javafx.media`. Per the JavaFX 25 docs, it plays **MP4 (`.mp4`, `.m4v`) with H.264 or H.265 video and AAC audio**, HLS streams, and MP3/WAV/AIFF audio. **MOV, MKV, WebM and AVI are not on the supported list.** Many phone videos are MOV, so this is a real gap.
  - v1 (built in #6): the viewer plays MP4 / M4V. For anything else it shows the thumbnail (once video thumbnails exist) and an "Open with default app" button, which uses JavaFX's `HostServices.showDocument` (no `java.desktop` needed).
  - **Measured in #6 on the development machine (Windows 11 build 26300, an Insider build):** H.265 (with the Microsoft HEVC extension installed) played every time. **AAC audio never played** (`ERROR_MEDIA_INVALID`), whichever encoder made it, in JavaFX 21, 24 and 25 alike, so most phone videos (H.264 + AAC) fail there. H.264 video without audio played about 6 times in 10; the other times it jumped to the end, never started, or failed. Not yet tried on another Windows build or on Linux. When playback fails, the viewer shows why and offers "Open with default app".
  - The player is created off the UI thread, and must be kept referenced while it plays: an unreferenced `MediaPlayer` can be garbage-collected mid-play (seen in a test program).
- **Watch progress:** the viewer saves `playback_position_seconds` from `MediaPlayer.currentTime` while playing (and on pause, stop and app close), through the DB writer queue.
- **Thumbnails (proposal; not in #4, which reports videos as "unsupported"; needs its own issue):** JavaFX can't grab a frame from a video without playing it on the UI toolkit. The plan is to bundle an **LGPL-only build of `ffmpeg`** and run it as a separate process to grab one frame. As a separate program it doesn't affect Prismo's MIT license, but its license notice must ship with the app.

---

## File watching

Built in #8 (`watch/` package). `java.nio.file.WatchService` sits behind our own `FileWatcher` interface, so the backend can be swapped later.

- **Windows: one watch per library folder covering the whole tree** (`TreeWatcher`), with `com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE` (module `jdk.unsupported`). Checked in #8 on Windows 11 / JDK 25: it works and reports paths below the root. Naming that class in code makes javac warn "internal proprietary API", which our `-Werror` build turns into an error, so it is looked up by name at runtime. **Packaging (#10): the `jlink` runtime must include `jdk.unsupported`.** `jdeps` can't see a lookup by name, so add it by hand.
- **Linux (and anywhere `FILE_TREE` is missing): one watch per folder** (`DirectoryWatcher`), with folders created later registered as they appear and then reported whole (files may land before the registration). When inotify's per-user limit (`fs.inotify.max_user_watches`) is hit, that library folder stops being watched live and the UI says so; the periodic rescan keeps it current. **Not run on Linux yet** (no Linux machine; with CI in #10). The same class runs on Windows in the tests.
- **What Windows reports** (checked in #8): a rename or move is "old path deleted" + "new path created"; a renamed or moved-in folder is reported only as the folder, not the files inside, so a created folder is walked; about 1,500 files created or deleted at once gave `OVERFLOW` in a scratch program that read events late (the app's own reading thread may keep up; a test checks "all reported, or an overflow").
- **`OVERFLOW`:** events were lost, so that library folder is rescanned (cheap: unchanged files aren't opened).
- **Debounce:** events are collected until nothing new has arrived for 1 s (5 s at most), then each path is handled once; a path inside another path of the batch is dropped.
- **Safety net:** a full rescan every 30 minutes catches anything missed (and re-watches folders that stopped being watched).
- **Deleted files:** if the library folder can be reached, a file that is gone has its row removed. If the library folder itself can't be reached (an unplugged drive), nothing under it is touched. Never under a folder that couldn't be read, and only after a complete walk.
- **Network drives (SMB/NFS):** not detected yet. A network folder is watched like any other; change notifications over SMB are not reliable, and the 30-minute rescan covers it.

### Rename / move detection

A rename shows up as "delete old path" + "create new path". To keep favorite/hidden/progress state, the old row must follow the file:

1. **No file ID on Windows:** `BasicFileAttributes.fileKey()` returned `null` on JDK 25 (checked in #8, 2 of 2). Linux gives device + inode, but one rule for both OSes is simpler.
2. **Matching by what a move keeps (built in #8, agreed 2026-10-19):** size + modified time + media type. Within a group, the same file name wins (a renamed folder); otherwise exactly one gone and one new file make a rename; anything else is ambiguous and treated as delete + add (`MoveMatcher`).
3. A matched pair keeps the **old row** (same id, so the thumbnail cache and user state stay) with the new path.

The fingerprint first planned here (SHA-256 of the first and last 64 KiB, stored per file) was not built: it needs a new column and about 128 KiB of extra reading per file at index time. It would only help when several files share size, time and type. This answers the open question in `PROJECT.md` (content hash vs file ID).

---

## UI structure

- **The grid must be virtualized** (only visible thumbnails exist as nodes). JavaFX has no built-in virtualized grid with section headers, so the Timeline is built on a `ListView`: each list item is a **row**, either a date header or a row of N thumbnails. When the window width or the thumbnail size changes, N is recomputed and the rows are rebuilt, keeping the same photo at the top. `ListView` already reuses cells as you scroll.
- **Thumbnails on screen:** the UI checks the cache off the UI thread (the freshness check is a file `stat`), keeps the last 300 decoded images in an in-memory LRU, and cancels a tile's request when its row scrolls away.
- **Viewer (#6):** an overlay inside the main window, not a second window. Cache-first: the 320 px thumbnail shows at once, in the place and size the full image will take (the indexed width/height), then the full image replaces it. The previous and next photos are decoded ahead; at most three full images are kept. Full images over 24 MP are decoded subsampled: JavaFX drew images up to 20,000 px wide in tests, so the limit is memory (4 bytes per pixel), not the GPU.
- **Search, Filters and Sort (#7):** the search box sits in the top bar; results show in a second copy of the Timeline grid, so the Timeline keeps its place. One shared Filters + Sort bar (second row) applies to both. Date sorts keep date headers (grouped by that date, in local time for modified/added); other sorts show a plain grid.
- Styling with JavaFX CSS; themes (Light / Dark / AMOLED) are separate stylesheets.

---

## Logging

SLF4J 2.0.x API with Logback as the backend. Logs are rotated (Logback `RollingFileAppender`) and written to the OS-specific log folder below. `System.out.println` is banned (see `DEVELOPMENT.md`).

---

## Where Prismo stores its data

All paths come from one OS-aware path class. Nothing is hardcoded.

| | Windows | Linux |
|---|---|---|
| Database, settings | `%LOCALAPPDATA%\Prismo\` | `$XDG_DATA_HOME/prismo/` (default `~/.local/share/prismo/`) |
| Thumbnail cache | `%LOCALAPPDATA%\Prismo\cache\` | `$XDG_CACHE_HOME/prismo/` (default `~/.cache/prismo/`) |
| Logs | `%LOCALAPPDATA%\Prismo\logs\` | `$XDG_STATE_HOME/prismo/logs/` (default `~/.local/state/prismo/logs/`) |

macOS isn't a target. If it ever becomes one, it would use `~/Library/Application Support/Prismo` and `~/Library/Caches/Prismo`.

---

## Third-party licenses to ship

Bundled native code brings license duties: `libheif` and `libde265` (LGPL), `ffmpeg` (LGPL build only), and `libraw` (LGPL/CDDL) if added later. The installer must include a `THIRD_PARTY_NOTICES` file. Java libraries: TwelveMonkeys (BSD), metadata-extractor (Apache 2.0), sqlite-jdbc (Apache 2.0), SLF4J (MIT), Logback (EPL/LGPL). **(re-check each license when the dependency is added)**

---

## Why Java (and not Rust or Python)

- **Not Python:** the GIL and packaging (PyInstaller cold start, bundle size) work against a UI-heavy, CPU-adjacent desktop app.
- **Not Rust:** most of the app's time goes to I/O (disk, SQLite, decoders, GPU), not CPU. An optimized Java version should land close to Rust for this kind of work. Rust's real win is one-time bulk CPU work (generating 100k thumbnails), which happens once and then becomes cache reads. Rust would also be learned mostly through AI, which makes it harder to defend the hard internals in an interview. In Java those parts (concurrency, native bridges, the image pipeline) can be owned and explained directly.
- **GC:** modern collectors (G1, ZGC) have pauses of a few milliseconds. GC isn't a problem at this scale unless allocation is sloppy. To keep it tidy: reuse buffers, pool thumbnail arrays, and avoid creating objects in hot loops.

---

## Sources

- JavaFX 25 needs JDK 23+: [JavaFX 25 Highlights](https://openjfx.io/highlights/25/), [JDK-8359387 on openjfx-dev](https://mail.openjdk.org/pipermail/openjfx-dev/2025-July/055263.html)
- JavaFX free-update dates: [Oracle JavaFX downloads](https://www.oracle.com/java/technologies/downloads/javafx/)
- Virtual threads and `synchronized`: [JEP 491](https://openjdk.org/jeps/491)
- FFM API: [JEP 454](https://openjdk.org/jeps/454)
- jlink and automatic modules: [JDK-8130047](https://bugs.openjdk.org/browse/JDK-8130047)
- jpackage types: [jpackage man page](https://man.archlinux.org/man/jpackage-openjdk27.1.en.txt); WiX 4/5: [JDK-8319457 review](https://mail.openjdk.org/pipermail/build-dev/2024-June/045181.html)
- Gradle and Java 25: [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html)
- sqlite-jdbc and FTS5: [xerial/sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)
- FTS5 trigram tokenizer: [SQLite FTS5](https://www.sqlite.org/fts5.html)
- metadata-extractor formats: [drewnoakes/metadata-extractor](https://github.com/drewnoakes/metadata-extractor)
- JavaFX media formats: [javafx.scene.media](https://openjfx.io/javadoc/25/javafx.media/javafx/scene/media/package-summary.html)
- JavaFX `Image`: [javafx.scene.image.Image](https://openjfx.io/javadoc/25/javafx.graphics/javafx/scene/image/Image.html)
- Windows `fileKey()`: [nio-dev thread, 2023](https://mail.openjdk.org/pipermail/nio-dev/2023-April/013561.html)
- beryx plugin: [badass-jlink-plugin](https://github.com/beryx/badass-jlink-plugin)
