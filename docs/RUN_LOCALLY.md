# Running Prismo locally

## What you need

- Windows 10 or 11 (Linux works with `./gradlew run`; the `.bat` file is for Windows).
- Nothing else to install: the Gradle wrapper downloads Gradle, and Gradle downloads the right JDK (a "toolchain")
  and every library, including the bundled FFmpeg. The first start takes a few minutes; later ones a few seconds.

## Start it

From the repository folder, in PowerShell, the VS Code terminal or Command Prompt (PowerShell needs the `.\`
in front; Command Prompt works with or without it):

| Command | Data it uses |
|---|---|
| `.\run-locally.bat` | your normal Prismo data: `%LOCALAPPDATA%\Prismo` (database, thumbnails, logs) |
| `.\run-locally.bat --test` | a separate test folder, `test-data\Prismo` in the repository (ignored by git) |
| `.\run-locally.bat --test D:\PrismoTest` | a test folder of your choice: `D:\PrismoTest\Prismo` |

A test run never touches your normal library, settings or thumbnails. To start a test from scratch, close Prismo
and delete the test folder. Prismo only reads your photo folders; it changes a file only when you ask it to
(Rename, Move, Delete permanently, or the File Explorer Hidden mark).

Logs: `<data folder>\Prismo\logs\prismo.log`.

## Hand-test checklist

Use `--test` and a folder with a mix of photos and videos (phone photos, a HEIC from an iPhone, a RAW file, a phone
video with sound, a screenshot).

1. **First start:** the empty screen shows the logo and "Add library folders…". Add your test folder.
2. **Timeline:** items appear while indexing, newest first, under date headers; thumbnails fill in. Ctrl + mouse
   wheel and the Size slider change the thumbnail size.
3. **HEIC and RAW:** they get real thumbnails and open in the viewer (not "Open with default app").
4. **Viewer:** click a photo. Left / Right move, + / - zoom, 0 fits, R rotates, Space starts the slideshow,
   I opens the Info panel (camera, exposure, GPS), F11 is full screen, Esc goes back.
5. **Video:** a phone video plays with sound in sync. Space, J / L, Left / Right, Up / Down, M, [ ], C (subtitles),
   A (sound track), double-click for full screen. Close it halfway: its thumbnail shows a progress bar, and it
   resumes there next time.
6. **Search:** Ctrl+F, type part of a name, a folder, a camera or a year.
7. **Filters + Sort:** Show (Photos / Videos …) and Sort change every grid.
8. **Collections:** Folders (tree + "Include subfolders"), Albums, Favorites, Recently Added, Hidden, Trash.
9. **Item actions:** F (favorite), H (hide), Delete (to Trash), then Restore from Trash. Delete permanently asks
   first and moves the file to the Recycle Bin.
10. **Selection:** Ctrl-click a few tiles, Shift-click a range, Ctrl+A. The bar at the bottom favorites, hides,
    trashes, copies or moves them all at once; Esc clears.
11. **Files:** right-click > Show in folder, Open with default app, Copy (then paste in File Explorer), Rename (F2),
    Move to folder (with a name clash: Keep both / Skip).
12. **Live updates:** copy a new photo into the folder in File Explorer: it shows within a few seconds. Delete or
    rename one there: Prismo follows.
13. **Offline folder:** add a folder on a USB stick, then unplug it: a banner names it within about 30 s, its tiles
    are greyed, and the viewer says it can't be reached. Plug it back in: everything comes back.
14. **Network folder (if you have a NAS or shared folder):** add it; the Library folders dialog says "network
    folder: no live updates"; new files show up within 10 minutes.
15. **Settings (Ctrl+,):** each look (Light, Dark, AMOLED black, Follow system), Hidden items, Ignored folders,
    Timeline grouping, Performance (thumbnail threads, Clear thumbnails), Keyboard shortcuts, About.
16. **Restart:** close and start again: window place and size, look, thumbnail size, video volume / speed and every
    setting are as you left them.
17. **Small window:** make the window as small as it goes: nothing overlaps, and the viewer's video bar still fits.
