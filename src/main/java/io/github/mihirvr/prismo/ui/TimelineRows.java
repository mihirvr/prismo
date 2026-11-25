package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Turns the Timeline's items into list rows. Pure functions: no JavaFX, so they're unit-tested directly. */
final class TimelineRows {

    private TimelineRows() {
    }

    /**
     * Splits {@code items} (in date order, either way) into groups, and each group into rows of {@code columns}.
     * Every group starts with a {@link TimelineRow.Header}.
     *
     * @param today the current day ({@link DateGroups#dayOf} units), for "Today" / "Yesterday"
     */
    static List<TimelineRow> build(List<TimelineItem> items, int columns, long today) {
        return build(items, columns, today, Grouping.SMART);
    }

    /** Like {@link #build(List, int, long)}, grouped the way {@code grouping} says. */
    static List<TimelineRow> build(List<TimelineItem> items, int columns, long today, Grouping grouping) {
        return build(items, columns, today, grouping, true);
    }

    /** Like {@link #build(List, int, long, Grouping, boolean)} with Smart grouping. */
    static List<TimelineRow> build(List<TimelineItem> items, int columns, long today, boolean grouped) {
        return build(items, columns, today, Grouping.SMART, grouped);
    }

    /**
     * Like {@link #build(List, int, long)}, or with {@code grouped} false (sorts that aren't by date): no
     * headers, just rows of {@code columns}.
     */
    static List<TimelineRow> build(List<TimelineItem> items, int columns, long today, Grouping grouping,
            boolean grouped) {
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be at least 1: " + columns);
        }
        List<TimelineRow> rows = new ArrayList<>(items.size() / columns + 16);
        if (!grouped) {
            for (int from = 0; from < items.size(); from += columns) {
                rows.add(new TimelineRow.Thumbs(items.subList(from, Math.min(from + columns, items.size()))));
            }
            return rows;
        }
        int start = 0;
        while (start < items.size()) {
            long group = DateGroups.groupOf(DateGroups.dayOf(items.get(start).sortDate()), today, grouping);
            int end = start + 1;
            while (end < items.size()
                    && DateGroups.groupOf(DateGroups.dayOf(items.get(end).sortDate()), today, grouping) == group) {
                end++;
            }
            rows.add(new TimelineRow.Header(DateGroups.labelOf(group, grouping), end - start));
            for (int from = start; from < end; from += columns) {
                // A view into the caller's list, not a copy: the caller must not change the list afterwards.
                rows.add(new TimelineRow.Thumbs(items.subList(from, Math.min(from + columns, end))));
            }
            start = end;
        }
        return rows;
    }

    /**
     * The Name sort: a header per first letter ("0-9", "A" to "Z", "#"; {@link NaturalOrder#headerOf}), items in
     * the order given (natural name order keeps each letter in one piece).
     */
    static List<TimelineRow> byLetter(List<TimelineItem> items, int columns) {
        List<FolderSections.Section> sections = new ArrayList<>();
        int start = 0;
        while (start < items.size()) {
            String letter = NaturalOrder.headerOf(fileName(items.get(start)));
            int end = start + 1;
            while (end < items.size() && NaturalOrder.headerOf(fileName(items.get(end))).equals(letter)) {
                end++;
            }
            sections.add(new FolderSections.Section(letter, items.subList(start, end)));
            start = end;
        }
        return build(sections, columns);
    }

    private static String fileName(TimelineItem item) {
        return item.ref().path().getFileName().toString();
    }

    /**
     * The Folders view: a header per section (its label and count), then its items in rows of {@code columns}.
     * Empty sections are left out. The rows hold views into the sections' lists, which must not change afterwards.
     */
    static List<TimelineRow> build(List<FolderSections.Section> sections, int columns) {
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be at least 1: " + columns);
        }
        List<TimelineRow> rows = new ArrayList<>();
        for (FolderSections.Section section : sections) {
            List<TimelineItem> items = section.items();
            if (items.isEmpty()) {
                continue;
            }
            rows.add(new TimelineRow.Header(section.label(), items.size()));
            for (int from = 0; from < items.size(); from += columns) {
                rows.add(new TimelineRow.Thumbs(items.subList(from, Math.min(from + columns, items.size()))));
            }
        }
        return rows;
    }

    /**
     * 1.4.2, Duplicates: the items as files, each with all its copies, in the order each file's first copy comes in
     * {@code items} (the Sort's order). Items without a key (no copy group) are a file each.
     *
     * @param key   an item's file (its copy group), or null
     * @param label a file's header text, from its copies
     */
    static List<FolderSections.Section> files(List<TimelineItem> items, Function<TimelineItem, String> key,
            Function<List<TimelineItem>, String> label) {
        Map<String, List<TimelineItem>> byFile = new LinkedHashMap<>();
        for (TimelineItem item : items) {
            String file = key.apply(item);
            byFile.computeIfAbsent(file != null ? file : "id:" + item.ref().id(), _ -> new ArrayList<>()).add(item);
        }
        List<FolderSections.Section> files = new ArrayList<>(byFile.size());
        for (List<TimelineItem> copies : byFile.values()) {
            files.add(new FolderSections.Section(label.apply(copies), List.copyOf(copies)));
        }
        return files;
    }

    /**
     * 1.4.2, Duplicates on a date sort: date headers (as {@code grouping} splits them) with whole files under them, a
     * file under the date of its first copy; {@link CopyLayout#DATES_AND_FILES} adds a {@link TimelineRow.SubHeader}
     * per file, {@link CopyLayout#DATES} puts each date's files one after another, {@link CopyLayout#FILES} has only
     * the files' headers. Files come in date order already (from {@link #files}), so each date is one run.
     */
    static List<TimelineRow> byCopies(List<FolderSections.Section> files, int columns, long today, Grouping grouping,
            CopyLayout layout) {
        if (columns < 1) {
            throw new IllegalArgumentException("columns must be at least 1: " + columns);
        }
        if (layout == CopyLayout.FILES) {
            return build(files, columns);
        }
        List<TimelineRow> rows = new ArrayList<>();
        int start = 0;
        while (start < files.size()) {
            long group = groupOfFile(files.get(start), today, grouping);
            int end = start + 1;
            while (end < files.size() && groupOfFile(files.get(end), today, grouping) == group) {
                end++;
            }
            List<FolderSections.Section> day = files.subList(start, end);
            int count = 0;
            for (FolderSections.Section file : day) {
                count += file.items().size();
            }
            rows.add(new TimelineRow.Header(DateGroups.labelOf(group, grouping), count));
            if (layout == CopyLayout.DATES_AND_FILES) {
                for (FolderSections.Section file : day) {
                    rows.add(new TimelineRow.SubHeader(file.label(), file.items().size()));
                    addRows(rows, file.items(), columns);
                }
            } else {
                List<TimelineItem> all = new ArrayList<>(count);
                for (FolderSections.Section file : day) {
                    all.addAll(file.items());
                }
                addRows(rows, List.copyOf(all), columns);
            }
            start = end;
        }
        return rows;
    }

    private static long groupOfFile(FolderSections.Section file, long today, Grouping grouping) {
        return DateGroups.groupOf(DateGroups.dayOf(file.items().getFirst().sortDate()), today, grouping);
    }

    /** Rows of {@code columns} (views into {@code items}, which must not change afterwards). */
    private static void addRows(List<TimelineRow> rows, List<TimelineItem> items, int columns) {
        for (int from = 0; from < items.size(); from += columns) {
            rows.add(new TimelineRow.Thumbs(items.subList(from, Math.min(from + columns, items.size()))));
        }
    }

    /**
     * Arrow Up / Down on the Timeline: the item in the same column of the next thumbnail row above
     * ({@code direction} −1) or below (+1), skipping headers. A shorter row gives its last item.
     *
     * @return that item's media id, or -1 if {@code id} isn't in the rows or there is no row that way
     */
    static long verticalNeighbour(List<TimelineRow> rows, long id, int direction) {
        int row = rowOf(rows, id);
        if (row < 0) {
            return -1;
        }
        List<TimelineItem> from = ((TimelineRow.Thumbs) rows.get(row)).items();
        int column = 0;
        while (from.get(column).ref().id() != id) {
            column++;
        }
        for (int r = row + direction; r >= 0 && r < rows.size(); r += direction) {
            if (rows.get(r) instanceof TimelineRow.Thumbs(List<TimelineItem> items)) {
                return items.get(Math.min(column, items.size() - 1)).ref().id();
            }
        }
        return -1;
    }

    /** Index of the thumbnail row holding media {@code id}, or -1. */
    static int rowOf(List<TimelineRow> rows, long id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof TimelineRow.Thumbs(List<TimelineItem> items)) {
                for (TimelineItem item : items) {
                    if (item.ref().id() == id) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }
}
