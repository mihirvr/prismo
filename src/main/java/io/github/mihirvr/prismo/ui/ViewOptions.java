package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The shared Filters + Sort choice, applied the same way to the Timeline, search results and the Collections
 * pages, plus the Settings switch "Show files hidden in File Explorer" (P6), which every view applies the same way
 * too: carried here, a change reloads every view like a new filter does.
 *
 * <p>The SQL pieces come only from the fixed text in {@link MediaFilter}, {@link MediaSort} and the known
 * extensions in {@link FileTypes}, never from user input.
 *
 * @param descending    true = newest / largest / Z first
 * @param osHiddenShown true = files with the operating system's Hidden mark show like any other file; false (the
 *                      default) = only on Collections › Hidden ({@link MediaScope#sqlCondition})
 * @param types         the file types ticked in the Types menu ({@link FileTypes}: {@code jpg}, {@code png}…);
 *                      empty = every type
 * @param people        the People filter (1.2.0); {@link PeopleFilter#NONE} = everyone
 */
public record ViewOptions(MediaFilter filter, MediaSort sort, boolean descending, boolean osHiddenShown,
        Set<String> types, PeopleFilter people) {

    /** Everything, date taken, newest first: the Timeline as it was before Filters and Sort existed. */
    public static final ViewOptions DEFAULT = new ViewOptions(MediaFilter.ALL, MediaSort.DATE_TAKEN, true, false);

    /** The filters that became Types in 1.1.0, so a saved "JPEG" filter comes back as the JPG type. */
    private static final Map<String, String> OLD_FILTERS = Map.of("JPEG", "jpg", "PNG", "png", "HEIC", "heic",
            "WEBP", "webp");
    /** Marks the People part of the setting (types never contain "="). */
    private static final String PEOPLE_PREFIX = "p=";

    public ViewOptions {
        types = Set.copyOf(FileTypes.typesOf(types)); // only known types, each once
        people = people == null ? PeopleFilter.NONE : people;
    }

    /** Nobody filtered by people. */
    public ViewOptions(MediaFilter filter, MediaSort sort, boolean descending, boolean osHiddenShown,
            Set<String> types) {
        this(filter, sort, descending, osHiddenShown, types, PeopleFilter.NONE);
    }

    /** Every file type. */
    public ViewOptions(MediaFilter filter, MediaSort sort, boolean descending, boolean osHiddenShown) {
        this(filter, sort, descending, osHiddenShown, Set.of());
    }

    /** With the File Explorer switch off (the default). */
    public ViewOptions(MediaFilter filter, MediaSort sort, boolean descending) {
        this(filter, sort, descending, false);
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    /** True when a filter or a type narrows what is shown. */
    public boolean isFiltered() {
        return filter != MediaFilter.ALL || !types.isEmpty() || people.active();
    }

    /** What is shown, in words: "Videos", "JPG, PNG", "Photos, JPG"; "All" when nothing narrows it. */
    public String filterLabel() {
        List<String> parts = new ArrayList<>();
        if (filter != MediaFilter.ALL) {
            parts.add(filter.toString());
        }
        for (String type : new TreeSet<>(types)) {
            parts.add(FileTypes.label(type));
        }
        if (people.label() != null) {
            parts.add(people.label());
        }
        return parts.isEmpty() ? MediaFilter.ALL.toString() : String.join(", ", parts);
    }

    /** True when the view shows date headers (a date sort). */
    public boolean grouped() {
        return sort.isDate();
    }

    /** The headers this sort gets in a grid: dates for a date sort, letters for Name, none otherwise. */
    public Headers headers() {
        return sort.isDate() ? Headers.DATES : sort == MediaSort.NAME ? Headers.LETTERS : Headers.NONE;
    }

    /** The filter's, the types' and the people's SQL condition, or null when everything is shown. */
    public String sqlCondition() {
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {filter.condition(), FileTypes.sqlCondition(types), people.sqlCondition()}) {
            if (part != null) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join(" AND ", parts);
    }

    /**
     * The ORDER BY list: the sort, then {@code id} in the same direction, so equal values always come out in
     * the same order.
     *
     * <p>A date-taken sort is written {@code +sort_date}. The unary {@code +} gives the same value but isn't
     * the bare column, so SQLite can't use {@code idx_media_sort_date} for the order: it reads the table
     * straight through and sorts the result, instead of walking the index and fetching every row one at a
     * time in date order. Measured in #7 (synthetic, 100,000 rows): the whole Timeline 250–262 ms against
     * 718–739 ms for the walk; a Videos filter 48 against 520 ms; a 2-letter search 35 against 639 ms. Only
     * {@code sort_date} has an index, so the other sorts read the table either way.
     */
    public String sqlOrderBy() {
        String direction = descending ? " DESC" : " ASC";
        String prefix = sort == MediaSort.DATE_TAKEN ? "+" : "";
        return prefix + sort.expression() + direction + ", id" + direction;
    }

    public ViewOptions withFilter(MediaFilter newFilter) {
        return new ViewOptions(newFilter, sort, descending, osHiddenShown, types, people);
    }

    /** A new sort starts in its natural direction (see {@link MediaSort#descendingByDefault()}). */
    public ViewOptions withSort(MediaSort newSort) {
        return new ViewOptions(filter, newSort, newSort.descendingByDefault(), osHiddenShown, types, people);
    }

    public ViewOptions withDescending(boolean newDescending) {
        return new ViewOptions(filter, sort, newDescending, osHiddenShown, types, people);
    }

    public ViewOptions withOsHiddenShown(boolean shown) {
        return new ViewOptions(filter, sort, descending, shown, types, people);
    }

    /** The ticked file types; empty = every type. */
    public ViewOptions withTypes(Set<String> newTypes) {
        return new ViewOptions(filter, sort, descending, osHiddenShown, newTypes, people);
    }

    /** The People filter (1.2.0). */
    public ViewOptions withPeople(PeopleFilter newPeople) {
        return new ViewOptions(filter, sort, descending, osHiddenShown, types, newPeople);
    }

    /**
     * The Filters + Sort as a setting, kept across restarts (P7): {@code "PHOTOS,NAME,asc"}, then the ticked types
     * if any: {@code "ALL,NAME,asc,jpg+png"}, then the People filter if any (1.2.0): {@code ",p=all:3+7"}. The File
     * Explorer switch has its own setting, so it isn't part of this.
     */
    public String toSetting() {
        String value = filter.name() + "," + sort.name() + "," + (descending ? "desc" : "asc");
        if (!types.isEmpty()) {
            value += "," + String.join("+", new TreeSet<>(types));
        }
        String byPeople = people.toSetting();
        return byPeople == null ? value : value + "," + PEOPLE_PREFIX + byPeople;
    }

    /**
     * The Filters + Sort saved by {@link #toSetting}; missing or unreadable (an older or edited value): the default.
     * A filter that became a type in 1.1.0 (JPEG, PNG, HEIC, WEBP) comes back as that type.
     */
    public static ViewOptions fromSetting(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String[] parts = value.split(",");
        if (parts.length < 3 || parts.length > 5 || !(parts[2].equals("desc") || parts[2].equals("asc"))) {
            return DEFAULT;
        }
        // After the sort: the types, the People part (1.2.0), or the types then the People part; nothing else.
        String typesPart = parts.length > 3 && !parts[3].startsWith(PEOPLE_PREFIX) ? parts[3] : null;
        String peoplePart = parts.length == 5 ? parts[4] : parts.length == 4 && typesPart == null ? parts[3] : null;
        if ((parts.length == 5 && typesPart == null) || (peoplePart != null && !peoplePart.startsWith(PEOPLE_PREFIX))) {
            return DEFAULT;
        }
        Set<String> types = new TreeSet<>(typesPart == null ? List.of() : List.of(typesPart.split("\\+")));
        PeopleFilter people = peoplePart == null ? PeopleFilter.NONE
                : PeopleFilter.fromSetting(peoplePart.substring(PEOPLE_PREFIX.length()));
        MediaFilter filter;
        if (OLD_FILTERS.containsKey(parts[0])) {
            filter = MediaFilter.ALL;
            types.add(OLD_FILTERS.get(parts[0]));
        } else {
            try {
                filter = MediaFilter.valueOf(parts[0]);
            } catch (IllegalArgumentException e) {
                return DEFAULT; // a filter that no longer exists
            }
        }
        try {
            return new ViewOptions(filter, MediaSort.valueOf(parts[1]), parts[2].equals("desc"), false, types, people);
        } catch (IllegalArgumentException e) {
            return DEFAULT; // a sort that no longer exists
        }
    }

    /**
     * True if both show the same list in the same order, apart from the File Explorer switch: a view then stays
     * where it is on a reload (only which items are in it changed), instead of starting from the top.
     */
    public boolean sameOrder(ViewOptions other) {
        return other != null && withOsHiddenShown(false).equals(other.withOsHiddenShown(false));
    }
}
