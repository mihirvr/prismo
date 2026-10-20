package io.github.mihirvr.prismo.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The library folders that can't be reached right now (P7, row 8): an unplugged drive, a network folder whose
 * computer is off. Prismo keeps their items (favorites, hidden, watch progress and cached thumbnails stay), shows them
 * greyed with a mark, and says so in a banner and in the viewer instead of "File not found". Which folders these are
 * is found by the app ({@code platform/Reachability}); this is only the answer and its words.
 *
 * @param roots offline library folders, as the library stores them (real paths)
 */
public record OfflineFolders(List<Path> roots) {

    public static final OfflineFolders NONE = new OfflineFolders(List.of());

    public OfflineFolders {
        roots = List.copyOf(roots);
    }

    public static OfflineFolders of(Set<Path> roots) {
        return roots.isEmpty() ? NONE : new OfflineFolders(List.copyOf(roots));
    }

    public boolean isEmpty() {
        return roots.isEmpty();
    }

    /** The offline library folder holding {@code file}, or null if its folder is reachable (as far as is known). */
    public Path rootOf(Path file) {
        for (Path root : roots) {
            if (file.startsWith(root)) {
                return root;
            }
        }
        return null;
    }

    public boolean contains(Path file) {
        return rootOf(file) != null;
    }

    /** The banner over the grids: which folder (or how many), and that nothing is lost. */
    public String bannerText() {
        String which = roots.size() == 1 ? roots.getFirst() + " can't be reached right now"
                : "%d library folders can't be reached right now: %s".formatted(roots.size(), String.join(", ",
                        roots.stream().map(Path::toString).toList()));
        return which + " (unplugged, or its network is off). " + (roots.size() == 1 ? "Its" : "Their")
                + " photos and videos are greyed out until " + (roots.size() == 1 ? "it is" : "they are")
                + " back; nothing is lost.";
    }

    /** The viewer's message for an item in {@code root}. */
    public static String viewerText(Path root) {
        return "This file's library folder can't be reached right now:\n" + root
                + "\nPlug the drive back in, or check the network. It opens again once the folder is back.";
    }
}
