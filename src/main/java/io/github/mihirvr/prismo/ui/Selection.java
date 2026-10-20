package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which items of a grid are selected (P7, Selection Mode), kept as media ids so it survives reloads and row
 * rebuilds, like the keyboard focus. The rules are File Explorer's: Ctrl-click adds or removes one item and makes it
 * the anchor; Shift-click selects everything from the anchor to the clicked item (Ctrl+Shift adds that range to what
 * is selected); Ctrl+A selects all. No JavaFX here, so the rules are unit-tested.
 */
final class Selection {

    private static final long NONE = -1;

    private final Set<Long> ids = new LinkedHashSet<>();
    /** Where a Shift range starts: the last item toggled, or {@link #NONE}. */
    private long anchor = NONE;

    boolean isEmpty() {
        return ids.isEmpty();
    }

    int size() {
        return ids.size();
    }

    boolean contains(long id) {
        return ids.contains(id);
    }

    /** Adds or removes one item (Ctrl-click, Space, a click while selecting); it becomes the anchor. */
    void toggle(long id) {
        if (!ids.remove(id)) {
            ids.add(id);
        }
        anchor = id;
    }

    /**
     * Selects every item from the anchor to {@code to}, both included, in {@code items} order (Shift-click). Without
     * an anchor (or with one no longer shown) it is just {@code to}, which becomes the anchor.
     *
     * @param keep add the range to what is selected (Ctrl+Shift), instead of replacing it
     */
    void selectRange(List<TimelineItem> items, long to, boolean keep) {
        int end = indexOf(items, to);
        if (end < 0) {
            return;
        }
        int start = indexOf(items, anchor);
        if (start < 0) {
            start = end;
            anchor = to;
        }
        if (!keep) {
            ids.clear();
        }
        for (int i = Math.min(start, end); i <= Math.max(start, end); i++) {
            ids.add(items.get(i).ref().id());
        }
    }

    /** Sets the anchor without changing what is selected (Shift + arrow starts a range at the focused item). */
    void anchorAt(long id) {
        if (anchor == NONE || !ids.contains(anchor)) {
            anchor = id;
        }
    }

    void selectAll(List<TimelineItem> items) {
        for (TimelineItem item : items) {
            ids.add(item.ref().id());
        }
    }

    void clear() {
        ids.clear();
        anchor = NONE;
    }

    /**
     * After a reload: forgets items no longer shown (hidden, trashed or deleted elsewhere, filtered out).
     *
     * @return true if that changed what is selected
     */
    boolean retainShown(List<TimelineItem> items) {
        if (ids.isEmpty()) {
            return false;
        }
        Set<Long> shown = new LinkedHashSet<>();
        for (TimelineItem item : items) {
            shown.add(item.ref().id());
        }
        boolean changed = ids.retainAll(shown);
        if (!shown.contains(anchor)) {
            anchor = NONE;
        }
        return changed;
    }

    /** The selected items, in {@code items} order (the order they are shown). */
    List<TimelineItem> items(List<TimelineItem> items) {
        List<TimelineItem> selected = new ArrayList<>(ids.size());
        for (TimelineItem item : items) {
            if (ids.contains(item.ref().id())) {
                selected.add(item);
            }
        }
        return selected;
    }

    private static int indexOf(List<TimelineItem> items, long id) {
        if (id == NONE) {
            return -1;
        }
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).ref().id() == id) {
                return i;
            }
        }
        return -1;
    }
}
