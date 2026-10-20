package io.github.mihirvr.prismo.ui;

import java.nio.file.Path;
import java.util.List;

/**
 * Text for dialog headings. A heading is one line that doesn't wrap, so a long file or folder name would make the
 * whole dialog as wide as the name; headings name things briefly and the full path goes in the body, which wraps.
 */
public final class DialogTexts {

    /** Longest name shown whole in a heading; longer ones keep their start and end with "…" between. */
    static final int MAX_NAME = 40;
    /** File names listed in a dialog's body before "and N more". */
    static final int LISTED = 5;

    private DialogTexts() {
    }

    /** {@code “name”}, shortened in the middle when long (the end keeps the extension or the folder's last part). */
    public static String quoted(String name) {
        String shown = name;
        if (name.length() > MAX_NAME) {
            int end = (MAX_NAME - 1) / 2;
            int start = MAX_NAME - 1 - end;
            shown = name.substring(0, start) + "…" + name.substring(name.length() - end);
        }
        return "\u201c" + shown + "\u201d";
    }

    /**
     * The files of a selection for a dialog's body: the first {@link #LISTED} names, one per line, then "and 12 more",
     * so a big selection doesn't make a dialog taller than the screen.
     */
    public static String fileList(List<Path> files) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(LISTED, files.size()); i++) {
            Path name = files.get(i).getFileName();
            text.append(i == 0 ? "" : "\n").append(name == null ? files.get(i) : name);
        }
        if (files.size() > LISTED) {
            text.append("\nand %,d more".formatted(files.size() - LISTED));
        }
        return text.toString();
    }

    /** A folder's own name for a heading ("Holidays" for {@code D:\Photos\Holidays}); a drive keeps its root. */
    public static String folderName(Path folder) {
        Path last = folder.getFileName();
        return last == null ? folder.toString() : last.toString();
    }
}
