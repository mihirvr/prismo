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
| Video playback | JavaCV `FFmpegFrameGrabber` + FFmpeg (LGPL v3 build) + `javax.sound` | javacv 1.5.14, ffmpeg 8.1.2-1.5.14 | **no** (automatic) | Our own small player, see "Video". Replaced `javafx.media` (pre-packaging work, 2026-10-20) |
| Video thumbnails | The `ffmpeg` program from the same FFmpeg bundle, run as a separate process | — | — | See "Video" |
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

**Native-access warnings.** Since JDK 24, the JVM prints warnings when code uses JNI or FFM unless native access is allowed at launch. sqlite-jdbc uses JNI, and JavaFX uses native code too. The run options (`build.gradle.kts`) use `--enable-native-access=javafx.graphics,ALL-UNNAMED`: `javafx.graphics` checked in #1, `ALL-UNNAMED` (sqlite-jdbc) in #2 and FFmpeg through JavaCPP (also a classpath jar) since the video player replaced `javafx.media` (which #6 had added to the flag); Prismo's own FFM call for the dark title bar (`platform/WindowsTitleBar`) is classpath code too, so `ALL-UNNAMED` covers it. The installer launcher (#10) needs the same flag.

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
4. To find which JDK modules we need, run `jdeps --print-module-deps --ignore-missing-deps` on the app jars, then add JavaFX's modules (`javafx.controls`; `javafx.media` is no longer used; `javafx.swing` if we use `SwingFXUtils`).
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
| Viewer: playing a video | Native (FFmpeg through JavaCPP) | Two platform threads per video while it plays: `prismo-video-picture` and `prismo-video-sound`, each with its own FFmpeg reader. Closed when the viewer moves on |
| Collections pages (#9) | Native (JNI) | The same `prismo-timeline` thread as the Timeline, newest load wins |
| "Delete permanently" (#9) | I/O | A short-lived virtual thread per file (Recycle Bin call or delete), then the row on the writer |
| Subtitles from inside a video | Separate process | A virtual thread waits for the bundled `ffmpeg` program (`ExternalProgram`), which converts the track to SRT; an `.srt` next to the video is read on the same kind of thread |
| Info panel: reading the row and the file's EXIF | I/O | A virtual thread per item shown; a result for an item no longer on screen is dropped |
| Setting / clearing the Windows Hidden mark | I/O | A virtual thread, then the row on the writer |
| Show in folder | Separate process | `explorer.exe /select,<path>` started and left to run |
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

**Migrations:** hand-written and numbered (`V1__init.sql`, `V2__...`; now up to `V4__os_hidden.sql`, which adds the `media.os_hidden` column for the Windows Hidden mark) in `src/main/resources/db/migration/`. The current schema version is kept in `PRAGMA user_version`. On startup, run every script whose number is above `user_version`, each in its own transaction. No Flyway: the job is small and doing it by hand teaches how it works.

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
- **Playback: our own small player on FFmpeg** (`video/VideoPlayer`), built before #10 because `javafx.media` couldn't play phone videos on the development machine (a normal Windows 11 build 26300, not an Insider build): AAC sound failed every time, in JavaFX 25.0.4, 27 and 28-ea+12 alike (72 of 72 opens: 3 files × 8 opens × 3 versions, 2026-10-20), and H.264 started unreliably. It plays every video type Prismo indexes (MP4, M4V, MOV, MKV, WebM, AVI) with the codecs FFmpeg decodes (H.264, H.265, VP9, AV1, MPEG-4, ProRes; AAC, MP3, Opus, Vorbis…).
  - **Libraries:** JavaCV (`org.bytedeco:javacv`, only its FFmpeg classes: added with `isTransitive = false`, because its pom also pulls in OpenCV, Tesseract and camera drivers), JavaCPP (the loader) and `org.bytedeco:ffmpeg` with the native files for one OS (classifier `windows-x86_64` or `linux-x86_64`). Size: the Windows FFmpeg jar is 30.4 MB (76 MB unpacked), the Linux one 26.9 MB.
  - **How it plays:** two platform threads, each with its own `FFmpegFrameGrabber` on the same file: one decodes only pictures (BGRA, scaled down to the screen's real pixel size), one only sound (16-bit stereo, converted by FFmpeg). Sound goes to a `javax.sound` `SourceDataLine` (200 ms buffer). The **sound card is the master clock** (`PlaybackClock`: samples played ÷ sample rate); pictures wait for it. Files without sound, or a computer without a sound device, run on the wall clock. Three pixel arrays go round between the picture thread and the UI, which draws the newest one into a `WritableImage` on each pulse.
  - **Phone videos** store an upright video as a sideways picture plus a "display rotation" tag; the player reads it and turns the picture.
  - When a file can't be played, the viewer shows why in plain words, with the thumbnail behind it, and offers "Open with default app" (JavaFX `HostServices.showDocument`).
  - Measured (synthetic test videos, this machine, 8 threads): 1080p and 4K H.264 / H.265 at 30 fps delivered 30 of 30 pictures a second, at most 7 ms late; 4K used 14–17% of all cores; first picture after 110–217 ms (512 ms in one run for the first video after the app started).
- **Speed (0.5×–2×):** the clock runs `speed` times as fast (samples played ÷ sample rate × speed), and the sound thread passes the sound through FFmpeg's **`atempo`** filter (JavaCV `FFmpegFrameFilter`), which makes it shorter or longer **without changing its pitch**. A speed change is handled like a seek to the current moment, so no sound made at the old speed stays queued in the sound card.
- **Sound tracks:** the sound thread's own `FFmpegFrameGrabber` reads one chosen sound stream; picking another starts a new sound thread on that stream and seeks to the current moment. Track names come from the file's `language` and `title` tags (`video/Track`).
- **Subtitles** (`video/SubtitleSources`, `video/Subtitles`): `.srt` files named like the video in its folder (`Holiday.srt`, `Holiday.en.srt`) are read directly; text subtitle tracks inside the video (SubRip, ASS, MP4 text, WebVTT) are converted to SRT by the bundled **`ffmpeg` program in a separate process** (`-map 0:<stream> -f srt`), for the same reason as thumbnails: a damaged file can only crash that process. Prismo then parses the SRT itself (times, text; HTML-like tags and ASS override codes are dropped, so styling shows as plain text) and shows the line for the current time, moved by the delay. Picture subtitles (DVD / Blu-ray) are listed but not offered: they would need drawing bitmaps. A file next to the video is turned on by itself. "Choose subtitle file…" opens JavaFX's `FileChooser` (the system's own file dialog) on the video's folder and adds the picked `.srt` to the choices (`SubtitleSources.withChosenFile`, once per file).
- **Info for the Info panel:** when a video opens the player hands over what FFmpeg found: length, resolution, frame rate, rotation, and the codec names (`avcodec_get_name` on each stream's parameters), plus the sound and subtitle tracks.
- **Watch progress:** the viewer saves `playback_position_seconds` from the player's position every 5 s while playing, and on pause, at the end, on moving to another item, on closing the viewer and on app close, through the DB writer queue (`db/PlaybackPositions`). The thumbnail's progress bar is drawn by the tile from that column (not baked into the cached JPEG). Rules in `ui/WatchProgress`: watched at 95%, no resume and no bar under 5 s.
- **Thumbnails:** one frame at 1 s (the first frame for shorter videos), made by the `ffmpeg` **program** from the same bundle, run as a separate process (`thumbs/VideoThumbnailer`), single-threaded, with a 60 s limit. Why a process: a decoder crash on a damaged file ends only that `ffmpeg` run, not Prismo. `ffmpeg` turns phone videos upright by itself.
- **Native files:** JavaCPP unpacks FFmpeg's DLLs / `.so` files on first use into Prismo's cache (`cache/native`, set in `Launcher`; tests use `build/javacpp-cache`). The first unpack took 2.2–3.1 s here (5 runs); afterwards they load from there. #10 decides whether the installer ships them already unpacked.
- **License:** the default `org.bytedeco:ffmpeg` build is **LGPL v3 or later** (its `ffmpeg -L`; GPL parts like x264/x265 are only in the separate `-gpl` artifact, which Prismo doesn't use). JavaCV and JavaCPP are Apache 2.0 (or GPLv2 with Classpath exception, our choice: Apache). Prismo links to FFmpeg's shared libraries and doesn't change them, which LGPL allows from an MIT app; the license texts must ship with the installer (see "Third-party licenses to ship").

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
- **Library folders turned off or removed (#9):** that folder is unwatched (`FileWatcher.unwatch`), and watcher batches still waiting for it are dropped. A folder turned off keeps its rows; every query leaves them out with one shared condition (`LibraryRootStore.NOT_IN_DISABLED_FOLDER`, free when nothing is off: measured on synthetic 100k rows, no difference within noise; with 2 folders off the Timeline went from ~360 to ~430 ms). Library folders never nest: a folder inside one is refused, a parent replaces the ones inside it.
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
- **Collections (#9):** a second tab (Timeline | Collections). Every page (Folders, Albums, Favorites, Recently Added, Hidden, Trash) is the same grid over the one `media` table with a different `WHERE` (`MediaScope`); Folders adds a tree with "Include subfolders", Albums a grid of folder cards. Folder ranges and `GROUP BY` use `+folder` so SQLite reads the table instead of walking `idx_media_folder` (measured, synthetic 100k: tree 456 → 101 ms, albums 1,041 → 255 ms). Favorite / hide / Trash / restore / delete permanently are one shared set of actions (viewer buttons, keys F / H / Delete, right-click menu).
- **Viewer additions (pre-packaging work):** previous / next edge buttons and Page Up / Page Down (Left / Right skip 5 s in a video, move on for photos: `MediaViewer.Move`); full screen hides the top bar, edge buttons, video controls and the pointer (`Cursor.NONE`) after 2.5 s without mouse movement, with a fade; the **Info panel** (`viewer/InfoPanel`, `viewer/InfoSections`) sits on the right of the picture in an `HBox`, 340 px wide, in the app's look (not the dark viewer's), and reads EXIF with metadata-extractor through `platform/FileReads` on a virtual thread (`InfoSections` turns the metadata into lines and is unit-tested without the toolkit).
- **Right-click menus** are built the same way everywhere (`ui/Menus`): icon on the left, the shortcut on the right (JavaFX's `MenuItem.setAccelerator` draws it; the arrow keys and Enter show as the plain Unicode arrows ← → ↑ ↓ ↵, not emoji). The file items are shared (`ui/FileItems`): **Show in folder** (`platform/FileManager`: `explorer.exe /select,<path>`), **Open with default app** (`HostServices.showDocument`), **Copy** (`ClipboardContent.putFiles`: Windows gets a file-drop list, the format File Explorer's Paste reads).
- **Icons** (`ui/Icon`) are our own line drawings as SVG paths on a 24 × 24 grid, coloured by the stylesheet (`.icon-path`), so they follow the look. No icon font and no emoji.
- Styling with JavaFX CSS (`ui/prismo.css`). Every colour is a named "looked-up colour" on `.root` (`-prismo-accent`, `-prismo-bg`, `-prismo-text` …), and Modena's own names (`-fx-background`, `-fx-accent`, `-fx-selection-bar` …) point at them, so standard controls follow. A theme (Dark / AMOLED) only redefines those names in a small extra stylesheet. The accent is the blue of the logo (`#3366ff`).

## Looks, title bar and window

- **Looks** (`ui/Theme`, `ui/Appearance`): Light = `prismo.css` alone; Dark = plus `theme-dark.css`; AMOLED black = plus `theme-dark.css` and `theme-amoled.css` (later stylesheets win). Switching a look only changes the scene's stylesheet list, so it applies at once. Menus, drop-downs and tooltips are small windows of their own but JavaFX styles them from their owner's scene, so they follow by themselves; dialogs are separate scenes and copy their owner's list (`Appearance.matchOwner`). The logo image is set from CSS (`-fx-image`), so each look picks the right one.
- **Follow system** reads JavaFX's platform preferences (`Platform.getPreferences().colorSchemeProperty()`, JavaFX 22+) and listens to it, so the app changes when Windows switches between light and dark while it runs.
- **Dark title bar (Windows):** Windows draws the title bar, not the app. JavaFX 25 has a per-scene colour-scheme preference (`Scene.getPreferences().setColorScheme`), but for a normal decorated window it never tells Windows. So `platform/WindowsTitleBar` does it through the **FFM API** (JEP 454: calling C functions from Java without JNI): it finds the window's handle (HWND) by its title among this process's own windows (`FindWindowExW` + `GetWindowThreadProcessId`, because JavaFX's public API doesn't give out the HWND) and calls `DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE = 20, TRUE)` (19 on Windows 10 before 20H1). It must run **one pulse after `WINDOW_SHOWN`**: at that event Windows is still setting the window up and ignores the call. Anything that fails is logged and the title bar stays as Windows drew it.
- **App icon:** the logo in 9 sizes (16–256 px, `ui/icon/`) on every window (`Stage.getIcons()`); Windows picks the size it needs for the title bar and the taskbar. The installer's `.ico` is #10.
- **Window place** (`ui/WindowPlacement`): the normal (not maximised) position and size plus "maximised" are saved when Prismo closes and used at start. Before use the place is fitted to today's screens (the screen it overlaps most, shrunk and moved fully onto it); a place on no screen at all is dropped and the window opens centred. Minimum size 760 × 480.
- **Dialogs fit the screen** (`ui/DialogPlacement`): at most the usable area of the owner's screen (without the taskbar) less a margin, centred on the owner, content in a `ScrollPane` when taller. Built after the Settings window once opened taller than the screen (its cards held wrapping text in toggle buttons, which measured one word per line).
- **Settings** are rows in the `settings` table (key → text): `appearance.theme`, `hidden.mode`, `hidden.show_os_hidden` (saved when changed); `timeline.thumbnail_size`, `window.placement`, `video.volume`, `video.muted`, `video.speed` (saved when the app closes).

## The Windows Hidden mark

- **Reading it costs nothing extra:** on Windows the attributes a folder walk hands over are DOS attributes (`DosFileAttributes`), which include Hidden. The indexer stores it in `media.os_hidden` (V4). A file whose only change is the mark counts as changed (the row is updated without reading the file again), so a mark set in File Explorer is picked up by the watcher.
- **Setting it** (right-click "Hide in File Explorer", or Prismo's Hide in "Both ways" mode) uses `DosFileAttributeView.setHidden`, which changes only that attribute: the file's contents and modified time stay the same.
- **Which pages show it:** one shared SQL condition per page (`ui/MediaScope.sqlCondition(osHiddenShown)`): with "Show files hidden in File Explorer" off, every page except Hidden and Trash adds `os_hidden = 0`, and Hidden is `hidden = 1 OR os_hidden = 1`.
- **Not on Linux:** "hidden" there is a name starting with a dot; Prismo would have to rename the file, so the mark is treated as not supported (`OsHiddenMark.SUPPORTED` is false) and the menu items don't appear.

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

Bundled native code brings license duties: FFmpeg (**LGPL v3 or later**, the default `org.bytedeco:ffmpeg` build, checked 2026-10-20 from its own `ffmpeg -L` and the preset's build script), `libheif` and `libde265` (LGPL) when HEIC lands, and `libraw` (LGPL/CDDL) if added later. LGPL means: ship its license text and notices, link it as separate shared libraries the user could swap (the FFmpeg DLLs are separate files), and don't change it. The installer must include a `THIRD_PARTY_NOTICES` file. Java libraries: TwelveMonkeys (BSD), metadata-extractor (Apache 2.0), sqlite-jdbc (Apache 2.0), SLF4J (MIT), Logback (EPL/LGPL), JavaCV and JavaCPP (Apache 2.0, or GPLv2 with Classpath exception; we use them under Apache 2.0). **(re-check each license when the dependency is added)**

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
- JavaFX media formats (the old player, replaced): [javafx.scene.media](https://openjfx.io/javadoc/25/javafx.media/javafx/scene/media/package-summary.html)
- JavaFX `Image`: [javafx.scene.image.Image](https://openjfx.io/javadoc/25/javafx.graphics/javafx/scene/image/Image.html)
- Windows `fileKey()`: [nio-dev thread, 2023](https://mail.openjdk.org/pipermail/nio-dev/2023-April/013561.html)
- beryx plugin: [badass-jlink-plugin](https://github.com/beryx/badass-jlink-plugin)
