package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The People filter (1.2.0), part of the shared Filters + Sort ({@link ViewOptions#people()}): show only the items
 * of the people ticked, or only the items nobody is tagged in.
 *
 * <p>With two or more people ticked, {@code together} (the default) shows the items where ALL of them are, "Any of
 * them" ({@code together} false) the items where at least one is. "No people" ({@code untagged}) finds what is still
 * untagged, for example the files added to a folder after the folder was tagged; it can't be combined with people.
 *
 * <p>The SQL is fixed text plus the people's ids, numbers from Prismo's own database, never user input.
 *
 * @param people   the ids of the people ticked; empty = no person filter
 * @param together true = all of them in the item; false = any of them
 * @param untagged true = only items with no people at all (then {@code people} is empty)
 */
public record PeopleFilter(Set<Long> people, boolean together, boolean untagged) {

    /** Nothing filtered. */
    public static final PeopleFilter NONE = new PeopleFilter(Set.of(), true, false);

    public PeopleFilter {
        people = untagged ? Set.of() : Set.copyOf(people);
    }

    /** True when it narrows what is shown. */
    public boolean active() {
        return untagged || !people.isEmpty();
    }

    /** The SQL condition on {@code media}, or null when nothing is filtered. */
    public String sqlCondition() {
        if (untagged) {
            return "id NOT IN (SELECT media_id FROM media_person)";
        }
        if (people.isEmpty()) {
            return null;
        }
        String ids = new TreeSet<>(people).stream().map(String::valueOf).collect(Collectors.joining(", "));
        String chosen = "SELECT media_id FROM media_person WHERE person_id IN (" + ids + ")";
        if (together && people.size() > 1) {
            return "id IN (" + chosen + " GROUP BY media_id HAVING count(*) = " + people.size() + ")";
        }
        return "id IN (" + chosen + ")";
    }

    /** With {@code id} ticked or unticked; ticking a person turns "No people" off. */
    public PeopleFilter withPerson(long id, boolean ticked) {
        Set<Long> next = new TreeSet<>(people);
        if (ticked) {
            next.add(id);
        } else {
            next.remove(id);
        }
        return new PeopleFilter(next, together, false);
    }

    public PeopleFilter withTogether(boolean newTogether) {
        return new PeopleFilter(people, newTogether, untagged);
    }

    /** "No people" on clears the people ticked. */
    public PeopleFilter withUntagged(boolean newUntagged) {
        return new PeopleFilter(people, together, newUntagged);
    }

    /** Without people that no longer exist (a person deleted while the filter named them). */
    public PeopleFilter keepOnly(Set<Long> existing) {
        Set<Long> kept = new TreeSet<>(people);
        kept.retainAll(existing);
        return kept.size() == people.size() ? this : new PeopleFilter(kept, together, untagged);
    }

    /** For the filter button: "No people", "People: 2", or null when nothing is filtered. */
    public String label() {
        if (untagged) {
            return "No people";
        }
        return people.isEmpty() ? null : "People: " + people.size();
    }

    /** As part of the saved Filters + Sort: {@code all:3+7}, {@code any:3}, {@code none}; null when nothing. */
    public String toSetting() {
        if (untagged) {
            return "none";
        }
        if (people.isEmpty()) {
            return null;
        }
        return (together ? "all:" : "any:")
                + new TreeSet<>(people).stream().map(String::valueOf).collect(Collectors.joining("+"));
    }

    /** Reads {@link #toSetting}; anything unreadable is {@link #NONE}. */
    public static PeopleFilter fromSetting(String value) {
        if (value == null || value.isBlank()) {
            return NONE;
        }
        if (value.equals("none")) {
            return new PeopleFilter(Set.of(), true, true);
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            return NONE;
        }
        String mode = value.substring(0, colon);
        if (!mode.equals("all") && !mode.equals("any")) {
            return NONE;
        }
        List<Long> ids = new ArrayList<>();
        for (String part : value.substring(colon + 1).split("\\+")) {
            try {
                ids.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                return NONE;
            }
        }
        return new PeopleFilter(Set.copyOf(ids), mode.equals("all"), false);
    }
}
