# Installing Prismo

Prismo comes as an installer that brings everything it needs with it: its own Java runtime, JavaFX and FFmpeg
(for video). You don't need to install Java or anything else first.

- **Windows 10 or 11, 64-bit:** `Prismo-<version>.exe`
- **Linux, 64-bit, Debian / Ubuntu and their relatives (Linux Mint, Pop!_OS, ...):** `prismo_<version>_amd64.deb`

Download them from the [Releases page](https://github.com/mihirvr/prismo/releases) or the website,
[prismo.mihirr.in](https://prismo.mihirr.in).

---

## Windows

### Install

1. Double-click `Prismo-<version>.exe`.
2. Windows may show a blue **"Windows protected your PC"** screen. That's because the installer isn't signed with a
   paid code-signing certificate, not because something is wrong with it. Click **More info**, then **Run anyway**.
3. Accept the licence (MIT), keep or change the install folder, and tick **desktop shortcut** if you want one.
   No administrator password is needed: Prismo installs for your Windows user only.
4. Start Prismo from the Start menu (or the desktop shortcut). The first time you open a video, Prismo unpacks
   its video files once, which takes a second or two.

Nothing else to install. DirectX is part of Windows 10 and 11, and the Visual C++ runtime files Prismo needs come
in the installer.

### Update

Run the newer installer. It replaces the old version; your library, favorites and settings stay.

### Uninstall

**Settings > Apps > Installed apps > Prismo > Uninstall.** This removes the program. Your library data (the
database, thumbnails, settings) stays, so a later install picks up where you left off. To remove that too, delete
the folder `%LOCALAPPDATA%\Prismo` (paste it into File Explorer's address bar). Your photos and videos are never
touched: Prismo never copies, moves or changes them unless you ask it to (rename, move, Trash).

### Where things go

| What | Where |
|---|---|
| The program | `%LOCALAPPDATA%\Programs\Prismo` |
| Library data (database, settings) | `%LOCALAPPDATA%\Prismo` |
| Thumbnails and unpacked video files | `%LOCALAPPDATA%\Prismo\cache` |
| Logs (attach these to a bug report) | `%LOCALAPPDATA%\Prismo\logs` |

### First start with a big library

The first time you add a big library, Prismo reads every file once (its date taken, size and so on) and makes a
thumbnail for each one. With tens of thousands of photos and videos this takes a while: on a laptop hard disk,
about 15 minutes to read 40,000 files, and longer for all the thumbnails. Photos show up as they are found, and
the thumbnails on screen are always made first.

While this runs, the disk is busy, so **opening photos can be slow and videos can stutter**. That's expected,
and the status bar says so ("Setting up a big library..."). It happens only once: afterwards Prismo reads just
what changed, and the thumbnails are kept. SSDs are much faster here than hard disks and network drives (NAS).

### If something goes wrong

- **The window stays blank, flickers or draws garbage:** usually an old or broken graphics driver. Update it
  first. If that doesn't help, make Prismo draw without the graphics card: open Start, type
  `environment variables`, open **Edit environment variables for your account**, add a **New** variable named
  `JAVA_TOOL_OPTIONS` with the value `-Dprism.order=sw`, and start Prismo again. (This affects other Java apps
  of yours too; delete the variable again once the driver is fixed.)
- **Anything else:** open an issue on GitHub and attach the newest file from `%LOCALAPPDATA%\Prismo\logs`.

---

## Linux (Debian / Ubuntu)

Prismo is built and tested on Windows first. The Linux package is built and checked automatically on Ubuntu 24.04
(it installs and its window opens), but nobody has used it on a real Linux desktop yet, so expect rough edges and
please report them.

### Install

Install it with `apt`, not by double-clicking it and not with `dpkg -i`. `apt` also installs the system libraries
Prismo needs if they're missing:

```
cd ~/Downloads
sudo apt install ./prismo_<version>_amd64.deb
```

The `./` matters: without it, `apt` looks for a package named that in its online lists instead of the file.

Then start **Prismo** from your applications menu, or run `/opt/prismo/bin/Prismo` in a terminal (a terminal
shows any error messages).

**If you already used `dpkg -i`** and it complained about missing dependencies, run `sudo apt -f install`. That
fetches them and finishes the install.

### What it needs from the system

The package declares these, so `apt` installs them for you. Listed here in case you install another way or
something is still missing:

| Package (Ubuntu 24.04 / Debian 13 name, or the older name) | What for |
|---|---|
| `libgtk-3-0t64` or `libgtk-3-0` | Windows and dialogs (JavaFX draws through GTK 3) |
| `libasound2t64` or `libasound2` | Sound (ALSA) |
| `libpulse0` | Sound (PulseAudio / PipeWire) |
| `libudev1` | Finding the graphics hardware (FFmpeg's hardware video support) |
| `libxcb1`, `libxcb-shm0` | Talking to the X11 display (also used under Wayland) |
| `libstdc++6` | The C++ runtime the video libraries use |

To install them all by hand on Ubuntu 24.04 or newer:

```
sudo apt install libgtk-3-0t64 libasound2t64 libpulse0 libudev1 libxcb1 libxcb-shm0 libstdc++6
```

On Ubuntu 22.04, Debian 12 or older, use `libgtk-3-0` and `libasound2` instead of the `t64` names.

### Update and uninstall

- **Update:** `sudo apt install ./prismo_<newer version>_amd64.deb`
- **Uninstall:** `sudo apt remove prismo`. Your library data stays in `~/.local/share/prismo` (database,
  settings), `~/.cache/prismo` (thumbnails, unpacked video files) and `~/.local/state/prismo/logs` (logs).
  Delete those folders to remove it too.

### If something goes wrong

- **No Prismo in the applications menu** (very minimal installs without a desktop menu): the install still works;
  start it with `/opt/prismo/bin/Prismo`, or log out and back in after installing a desktop.
- **It doesn't start from the menu:** run `/opt/prismo/bin/Prismo` in a terminal and read the error. A line with
  `cannot open shared object file` names a missing library: install the package for it (table above).
- **Blank or broken window:** try software drawing: `JAVA_TOOL_OPTIONS=-Dprism.order=sw /opt/prismo/bin/Prismo`
- **Problems on a Wayland desktop** (window in the wrong place, no keyboard input): JavaFX runs through XWayland.
  Try `GDK_BACKEND=x11 /opt/prismo/bin/Prismo`
- **No sound in videos:** check `libpulse0` (or `libasound2t64`) is installed and the right output is chosen in your
  sound settings.
- **Other distributions** (Fedora, Arch, openSUSE, ...): there is no package for them yet. You can run Prismo from
  the source: install a JDK 25 (any vendor), the GTK 3, ALSA, PulseAudio and libxcb packages under your distribution's
  names, then `git clone` the repository and run `./gradlew run`.

Please report Linux problems as a GitHub issue, with the output of the terminal and the newest file from
`~/.local/state/prismo/logs`.

---

## Building the installer yourself

See [DEVELOPMENT.md](DEVELOPMENT.md), "Cross-Platform CI/CD Awareness": `./gradlew appImage` builds a ready-to-run
folder, `./gradlew installer` builds the installer for the system you're on (Windows needs WiX 5.0.2 once).
