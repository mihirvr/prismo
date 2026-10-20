package io.github.mihirvr.prismo.settings;

import java.util.List;

/**
 * Every keyboard shortcut, for the Settings page "Keyboard shortcuts" (a reference; the keys themselves are handled
 * where they work: the main window, the grids, the viewer). Keys are written as the menus write them; "/" between
 * keys means either one (or a pair: back / forward). Arrow keys are named in words: Windows draws the up and down
 * arrows from a colour font in some looks. Kept here as data, so a test checks it stays plain ASCII text.
 */
public final class Shortcuts {

    /** One line of the guide: the key(s) and what they do. */
    public record Shortcut(String keys, String does) {
    }

    /** A group of lines under a heading. */
    public record Section(String title, List<Shortcut> shortcuts) {
    }

    public static final List<Section> SECTIONS = List.of(
            new Section("Everywhere", List.of(
                    new Shortcut("Ctrl+F", "Search"),
                    new Shortcut("Ctrl+1 / Ctrl+2", "Timeline / Collections"),
                    new Shortcut("Ctrl+,", "Settings"),
                    new Shortcut("Ctrl + mouse wheel", "Bigger or smaller thumbnails"))),
            new Section("Photo grid", List.of(
                    new Shortcut("Arrow keys", "Move between photos"),
                    new Shortcut("Enter", "Open"),
                    new Shortcut("I / Alt+Enter", "Open with the Info panel"),
                    new Shortcut("F", "Add to Favorites, or remove"),
                    new Shortcut("H", "Hide, or unhide"),
                    new Shortcut("Delete", "Move to Trash (in Trash: delete permanently)"),
                    new Shortcut("Ctrl+C", "Copy the file"),
                    new Shortcut("F2", "Rename"))),
            new Section("Selecting", List.of(
                    new Shortcut("Ctrl + click / Space", "Select or unselect one"),
                    new Shortcut("Shift + click", "Select everything up to here"),
                    new Shortcut("Shift + arrow keys", "Select while moving"),
                    new Shortcut("Ctrl+A", "Select all"),
                    new Shortcut("F / H / Delete / Ctrl+C", "Do it to every selected item"),
                    new Shortcut("Esc", "Clear the selection"))),
            new Section("Viewer", List.of(
                    new Shortcut("Esc", "Back to the grid (closes the Info panel first)"),
                    new Shortcut("Page Up / Page Down", "Previous / next photo or video"),
                    new Shortcut("F11", "Full screen"),
                    new Shortcut("I", "Info panel"),
                    new Shortcut("F / H / Delete", "Favorite, hide, Trash"),
                    new Shortcut("Ctrl+C / F2", "Copy the file / rename"))),
            new Section("Photos", List.of(
                    new Shortcut("Left / Right", "Previous / next"),
                    new Shortcut("Space", "Start or stop the slideshow"),
                    new Shortcut("+ / -", "Zoom in / out"),
                    new Shortcut("0", "Fit to the window"),
                    new Shortcut("Double-click", "Zoom to 100 %, or back to fit"),
                    new Shortcut("R", "Rotate"))),
            new Section("Videos", List.of(
                    new Shortcut("Space", "Play or pause"),
                    new Shortcut("Double-click", "Full screen"),
                    new Shortcut("Left / Right", "Back / forward 5 s"),
                    new Shortcut("J / L", "Back / forward 10 s"),
                    new Shortcut(", / .", "One picture back / forward (paused)"),
                    new Shortcut("Home", "From the start"),
                    new Shortcut("Up / Down", "Volume up / down"),
                    new Shortcut("M", "Mute"),
                    new Shortcut("[ / ]", "Slower / faster"),
                    new Shortcut("C", "Next subtitles"),
                    new Shortcut("Z / X", "Subtitles 0.1 s earlier / later"),
                    new Shortcut("A", "Next sound track"))));

    private Shortcuts() {
    }
}
