package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A tag filter, part of the shared Filters + Sort: the People filter (1.2.0, {@link ViewOptions#people()}) or the
 * Categories filter (1.3.0, {@link ViewOptions#categories()}). Shows only the items with the tags ticked, or only the
 * items with no tag of that kind.
 *
 * <p>With two or more tags ticked, {@code together} (the default) shows the items that have ALL of them, "Any of
 * them" ({@code together} false) the items with at least one. {@code untagged} ("No people", "No category") finds
 * what is still untagged, for example the files added to a folder after the folder was tagged; it can't be combined
 * with tags. The People and the Categories filter are separate: both on means both must match.
 *
 * <p>The SQL is fixed text ({@link TagKind}'s table names) plus the tags' ids, numbers from Prismo's own database,
 * never user input.
 *
 * @param kind     which tags
 * @param ids      the ids of the tags ticked; empty = no filter
 * @param together true = all of them on the item; false = any of them
 * @param untagged true = only items with no tag of this kind (then {@code ids} is empty)
 */
public record TagFilter(TagKind kind, Set<Long> ids, boolean together, boolean untagged) {

    /** Nothing filtered by people. */
    public static final TagFilter NO_PEOPLE = none(TagKind.PEOPLE);
    /** Nothing filtered by categories. */
    public static final TagFilter NO_CATEGORIES = none(TagKind.CATEGORIES);

    public TagFilter {
        if (kind == null) {
            throw new IllegalArgumentException("a tag filter needs its kind");
        }
        ids = untagged ? Set.of() : Set.copyOf(ids);
    }

    /** Nothing filtered, for {@code kind}. */
    public static TagFilter none(TagKind kind) {
        return new TagFilter(kind, Set.of(), true, false);
    }

    /** True when it narrows what is shown. */
    public boolean active() {
        return untagged || !ids.isEmpty();
    }

    /** The SQL condition on {@code media}, or null when nothing is filtered. */
    public String sqlCondition() {
        if (untagged) {
            return "id NOT IN (SELECT media_id FROM " + kind.linkTable + ")";
        }
        if (ids.isEmpty()) {
            return null;
        }
        String list = new TreeSet<>(ids).stream().map(String::valueOf).collect(Collectors.joining(", "));
        String chosen = "SELECT media_id FROM " + kind.linkTable + " WHERE " + kind.column + " IN (" + list + ")";
        if (together && ids.size() > 1) {
            return "id IN (" + chosen + " GROUP BY media_id HAVING count(*) = " + ids.size() + ")";
        }
        return "id IN (" + chosen + ")";
    }

    /** With tag {@code id} ticked or unticked; ticking a tag turns "No people" / "No category" off. */
    public TagFilter withTag(long id, boolean ticked) {
        Set<Long> next = new TreeSet<>(ids);
        if (ticked) {
            next.add(id);
        } else {
            next.remove(id);
        }
        return new TagFilter(kind, next, together, false);
    }

    public TagFilter withTogether(boolean newTogether) {
        return new TagFilter(kind, ids, newTogether, untagged);
    }

    /** "No people" / "No category" on clears the tags ticked. */
    public TagFilter withUntagged(boolean newUntagged) {
        return new TagFilter(kind, ids, together, newUntagged);
    }

    /** Without tags that no longer exist (deleted while the filter named them). */
    public TagFilter keepOnly(Set<Long> existing) {
        Set<Long> kept = new TreeSet<>(ids);
        kept.retainAll(existing);
        return kept.size() == ids.size() ? this : new TagFilter(kind, kept, together, untagged);
    }

    /** For the filter button: "No people", "People: 2", "Categories: 1", or null when nothing is filtered. */
    public String label() {
        if (untagged) {
            return kind.untagged;
        }
        return ids.isEmpty() ? null : kind.filterLabel(ids.size());
    }

    /** As part of the saved Filters + Sort: {@code all:3+7}, {@code any:3}, {@code none}; null when nothing. */
    public String toSetting() {
        if (untagged) {
            return "none";
        }
        if (ids.isEmpty()) {
            return null;
        }
        return (together ? "all:" : "any:")
                + new TreeSet<>(ids).stream().map(String::valueOf).collect(Collectors.joining("+"));
    }

    /** Reads {@link #toSetting} for {@code kind}; anything unreadable is nothing filtered. */
    public static TagFilter fromSetting(TagKind kind, String value) {
        if (value == null || value.isBlank()) {
            return none(kind);
        }
        if (value.equals("none")) {
            return new TagFilter(kind, Set.of(), true, true);
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            return none(kind);
        }
        String mode = value.substring(0, colon);
        if (!mode.equals("all") && !mode.equals("any")) {
            return none(kind);
        }
        List<Long> read = new ArrayList<>();
        for (String part : value.substring(colon + 1).split("\\+")) {
            try {
                read.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                return none(kind);
            }
        }
        return new TagFilter(kind, Set.copyOf(read), mode.equals("all"), false);
    }
}
