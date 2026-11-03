package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.ui.FolderArrange.Order;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Folders view (1.1.0): splits a grid's items into sections, one per folder, under the folder's name. Pure
 * functions, no JavaFX, so they are unit-tested directly.
 *
 * <p>Each top folder (a library folder on the Grid, the chosen folder on Collections) gives two blocks: its own
 * files, under its own name, and its folders. {@link FolderArrange} sets which block comes first and the order of
 * the folders and of the files in each. On the Grid only the first level counts: media deeper down rolls up into
 * its first-level folder. On Collections every folder is its own section, named by its path below the top folder.
 */
public final class FolderSections {

    /** A header and the items under it, in order. */
    public record Section(String label, List<TimelineItem> items) {
    }

    /** How names are compared: folders and files alike. */
    static final Comparator<String> NAMES = NaturalOrder.INSTANCE;

    private FolderSections() {
    }

    /**
     * @param items        in the Sort bar's order (kept for {@link Order#SORT})
     * @param tops         the top folders; an item under none of them counts its own folder as its top
     * @param firstLevelOnly true on the Grid: deeper folders roll up into their first-level folder
     */
    public static List<Section> build(List<TimelineItem> items, List<Path> tops, boolean firstLevelOnly,
            FolderArrange arrange) {
        // top folder -> (folder -> items), both in first-seen order; the top's own files under the top itself
        Map<Path, Map<Path, List<TimelineItem>>> byTop = new LinkedHashMap<>();
        for (TimelineItem item : items) {
            Path folder = item.ref().path().getParent();
            Path top = topOf(folder, tops);
            Path group = folder.equals(top) || !firstLevelOnly ? folder
                    : top.resolve(top.relativize(folder).getName(0));
            byTop.computeIfAbsent(top, _ -> new LinkedHashMap<>())
                    .computeIfAbsent(group, _ -> new ArrayList<>()).add(item);
        }

        List<Map.Entry<Path, Map<Path, List<TimelineItem>>>> topList = new ArrayList<>(byTop.entrySet());
        topList.sort(Map.Entry.comparingByKey(folderOrder(arrange.folders(), byTop)));

        List<Section> sections = new ArrayList<>();
        for (Map.Entry<Path, Map<Path, List<TimelineItem>>> entry : topList) {
            Path top = entry.getKey();
            Map<Path, List<TimelineItem>> groups = entry.getValue();
            List<Section> own = new ArrayList<>();
            List<TimelineItem> ownFiles = groups.get(top);
            if (ownFiles != null) {
                own.add(new Section(nameOf(top), ordered(ownFiles, arrange.topFiles())));
            }
            List<Path> folders = new ArrayList<>(groups.keySet());
            folders.remove(top);
            folders.sort(subfolderOrder(arrange.folders(), top, groups));
            List<Section> sub = new ArrayList<>();
            for (Path folder : folders) {
                sub.add(new Section(labelOf(top, folder), ordered(groups.get(folder), arrange.folderFiles())));
            }
            sections.addAll(arrange.foldersFirst() ? sub : own);
            sections.addAll(arrange.foldersFirst() ? own : sub);
        }
        return sections;
    }

    /** The items of all sections, in order: what the grid holds and the viewer steps through. */
    public static List<TimelineItem> flatten(List<Section> sections) {
        List<TimelineItem> all = new ArrayList<>();
        for (Section section : sections) {
            all.addAll(section.items());
        }
        return all;
    }

    /** The deepest top that holds {@code folder}; the folder itself when none does. */
    static Path topOf(Path folder, List<Path> tops) {
        Path best = null;
        for (Path top : tops) {
            if (folder.startsWith(top) && (best == null || top.getNameCount() > best.getNameCount())) {
                best = top;
            }
        }
        return best == null ? folder : best;
    }

    /** A folder's own name; a drive root ({@code D:\}) as is. */
    static String nameOf(Path folder) {
        Path name = folder.getFileName();
        return name == null ? folder.toString() : name.toString();
    }

    /** The path below the top folder, "Goa / Day 1". */
    static String labelOf(Path top, Path folder) {
        Path relative = top.relativize(folder);
        List<String> names = new ArrayList<>();
        for (Path name : relative) {
            names.add(name.toString());
        }
        return String.join(" / ", names);
    }

    private static List<TimelineItem> ordered(List<TimelineItem> files, Order order) {
        Comparator<TimelineItem> comparator = switch (order) {
            case SORT -> null; // already in the Sort bar's order
            case NAME_ASC -> Comparator.comparing(FolderSections::fileName, NAMES);
            case NAME_DESC -> Comparator.comparing(FolderSections::fileName, NAMES).reversed();
            case NEWEST -> Comparator.comparingLong(TimelineItem::sortDate).reversed();
            case OLDEST -> Comparator.comparingLong(TimelineItem::sortDate);
        };
        if (comparator == null) {
            return files;
        }
        List<TimelineItem> copy = new ArrayList<>(files);
        copy.sort(comparator.thenComparingLong(item -> item.ref().id()));
        return copy;
    }

    private static String fileName(TimelineItem item) {
        return item.ref().path().getFileName().toString();
    }

    /**
     * The order of the top folders: by name, or by date. "Newest first" puts the folder with the newest media first,
     * "Oldest first" the folder with the oldest media first.
     */
    private static Comparator<Path> folderOrder(Order order, Map<Path, Map<Path, List<TimelineItem>>> byTop) {
        return switch (order) {
            case NEWEST -> Comparator.comparingLong((Path top) -> -newest(byTop.get(top).values()))
                    .thenComparing(FolderSections::nameOf, NAMES);
            case OLDEST -> Comparator.comparingLong((Path top) -> oldest(byTop.get(top).values()))
                    .thenComparing(FolderSections::nameOf, NAMES);
            case NAME_DESC -> Comparator.comparing(FolderSections::nameOf, NAMES).reversed();
            case NAME_ASC, SORT -> Comparator.comparing(FolderSections::nameOf, NAMES);
        };
    }

    /** The same for the folders below one top. By name, a folder comes right before the folders inside it. */
    private static Comparator<Path> subfolderOrder(Order order, Path top, Map<Path, List<TimelineItem>> groups) {
        Comparator<Path> byName = (a, b) -> compareNames(top.relativize(a), top.relativize(b));
        return switch (order) {
            case NEWEST -> Comparator.comparingLong((Path f) -> -newest(List.of(groups.get(f)))).thenComparing(byName);
            case OLDEST -> Comparator.comparingLong((Path f) -> oldest(List.of(groups.get(f)))).thenComparing(byName);
            case NAME_DESC -> byName.reversed();
            case NAME_ASC, SORT -> byName;
        };
    }

    /** Name by name, so "Goa" comes before "Goa / Day 1" and both before "Goa trip". */
    private static int compareNames(Path a, Path b) {
        int shared = Math.min(a.getNameCount(), b.getNameCount());
        for (int i = 0; i < shared; i++) {
            int byName = NAMES.compare(a.getName(i).toString(), b.getName(i).toString());
            if (byName != 0) {
                return byName;
            }
        }
        return Integer.compare(a.getNameCount(), b.getNameCount());
    }

    private static long newest(Iterable<List<TimelineItem>> lists) {
        long newest = Long.MIN_VALUE;
        for (List<TimelineItem> list : lists) {
            for (TimelineItem item : list) {
                newest = Math.max(newest, item.sortDate());
            }
        }
        return newest;
    }

    private static long oldest(Iterable<List<TimelineItem>> lists) {
        long oldest = Long.MAX_VALUE;
        for (List<TimelineItem> list : lists) {
            for (TimelineItem item : list) {
                oldest = Math.min(oldest, item.sortDate());
            }
        }
        return oldest;
    }
}
