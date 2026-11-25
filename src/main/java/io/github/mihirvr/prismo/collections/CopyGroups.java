package io.github.mihirvr.prismo.collections;

import io.github.mihirvr.prismo.ui.TimelineItem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which files are copies of which (1.4.1, Collections › Duplicates): every file not in Trash whose content (SHA-256 in
 * {@code media_hash}) another such file shares, in "groups" of copies. The page uses it to select every copy but one
 * of each file, to keep and label each file's copies together (1.4.2), and to warn before the last copy of a file goes.
 *
 * <p><b>Which copy stays</b> (the "keeper"): the one added to the library first ({@code date_added}, then the lowest
 * id), among the copies on screen; the user can pick another with "Keep this copy" (an override, kept while Prismo
 * runs). Copies not on screen (hidden, in a turned-off folder, filtered out) are never selected, but they do count as
 * a copy that is left, so trashing every copy on screen of a file that also has a hidden copy is not warned about.
 *
 * <p>Pure apart from {@link #load}: the rules are unit-tested.
 */
public final class CopyGroups {

    /** One file that has a copy: its group (the content's SHA-256 in hex), size and when it was added. */
    record Copy(long id, String group, long size, long added) {
    }

    /** What the Duplicates page says: how many extra copies (all but one per file), of how many files, how big. */
    public record Summary(int extras, int files, long bytes) {
    }

    /** Every file that has a copy, by group, from {@code media_hash} (files in Trash are left out). */
    static final String LOAD = "SELECT h.media_id, h.sha256, m.size_bytes, m.date_added FROM media_hash h "
            + "JOIN media m ON m.id = h.media_id WHERE m.trashed_at IS NULL AND h.sha256 IN ("
            + "SELECT h2.sha256 FROM media_hash h2 JOIN media m2 ON m2.id = h2.media_id WHERE m2.trashed_at IS NULL"
            + " GROUP BY h2.sha256 HAVING COUNT(*) > 1)";

    public static final CopyGroups NONE = new CopyGroups(List.of());

    private final Map<Long, Copy> byId = new HashMap<>();
    private final Map<String, List<Copy>> groups = new LinkedHashMap<>();

    CopyGroups(Collection<Copy> copies) {
        for (Copy copy : copies) {
            byId.put(copy.id(), copy);
            groups.computeIfAbsent(copy.group(), _ -> new ArrayList<>()).add(copy);
        }
    }

    /** Reads every group of copies. Runs on the load thread. */
    public static CopyGroups load(Connection connection) throws SQLException {
        List<Copy> copies = new ArrayList<>();
        HexFormat hex = HexFormat.of();
        try (PreparedStatement query = connection.prepareStatement(LOAD); ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                copies.add(new Copy(rows.getLong(1), hex.formatHex(rows.getBytes(2)), rows.getLong(3), rows.getLong(4)));
            }
        }
        return new CopyGroups(copies);
    }

    /** The group of media {@code id}, or null if it has no copy. */
    public String groupOf(long id) {
        Copy copy = byId.get(id);
        return copy == null ? null : copy.group();
    }

    /**
     * The copy of each group that stays, among {@code shown}: the override if it is shown, else the first added.
     *
     * @param overrides group -> the media id the user chose with "Keep this copy"
     * @return group -> media id
     */
    public Map<String, Long> keepers(List<TimelineItem> shown, Map<String, Long> overrides) {
        Map<String, Copy> best = new HashMap<>();
        Set<Long> shownIds = new HashSet<>();
        for (TimelineItem item : shown) {
            Copy copy = byId.get(item.ref().id());
            if (copy == null) {
                continue;
            }
            shownIds.add(copy.id());
            Copy now = best.get(copy.group());
            if (now == null || copy.added() < now.added() || copy.added() == now.added() && copy.id() < now.id()) {
                best.put(copy.group(), copy);
            }
        }
        Map<String, Long> keepers = new HashMap<>();
        best.forEach((group, copy) -> {
            Long chosen = overrides.get(group);
            keepers.put(group, chosen != null && shownIds.contains(chosen) ? chosen : copy.id());
        });
        return keepers;
    }

    /**
     * The extra copies among {@code some} (a page, or one of its sections): every one that has a copy and is not its
     * group's keeper, in {@code some}'s order.
     *
     * @param keepers from {@link #keepers}, worked out on the whole page
     */
    public List<TimelineItem> extras(List<TimelineItem> some, Map<String, Long> keepers) {
        List<TimelineItem> extras = new ArrayList<>();
        for (TimelineItem item : some) {
            Copy copy = byId.get(item.ref().id());
            if (copy != null && keepers.containsKey(copy.group()) && keepers.get(copy.group()) != copy.id()) {
                extras.add(item);
            }
        }
        return extras;
    }

    /** The other copies of {@code id} among {@code shown} ("Keep this copy" selects them). */
    public List<TimelineItem> othersShown(long id, List<TimelineItem> shown) {
        String group = groupOf(id);
        List<TimelineItem> others = new ArrayList<>();
        for (TimelineItem item : shown) {
            if (item.ref().id() != id && group != null && group.equals(groupOf(item.ref().id()))) {
                others.add(item);
            }
        }
        return others;
    }

    /**
     * The groups that would have no copy left in the library if {@code chosen} went to Trash: every copy of them not
     * in Trash is chosen (copies not on screen count as left).
     */
    public Set<String> leftWithoutCopy(Collection<TimelineItem> chosen) {
        Map<String, Integer> chosenPerGroup = new HashMap<>();
        for (TimelineItem item : chosen) {
            String group = groupOf(item.ref().id());
            if (group != null) {
                chosenPerGroup.merge(group, 1, Integer::sum);
            }
        }
        Set<String> lost = new HashSet<>();
        chosenPerGroup.forEach((group, count) -> {
            if (count >= groups.get(group).size()) {
                lost.add(group);
            }
        });
        return lost;
    }

    /** How many extra copies {@code shown} has (all but each group's keeper), of how many files, and their size. */
    public Summary summary(List<TimelineItem> shown, Map<String, Long> keepers) {
        List<TimelineItem> extras = extras(shown, keepers);
        Set<String> files = new HashSet<>();
        long bytes = 0;
        for (TimelineItem item : extras) {
            Copy copy = byId.get(item.ref().id());
            files.add(copy.group());
            bytes += copy.size();
        }
        return new Summary(extras.size(), files.size(), bytes);
    }

    /**
     * A file's header on Duplicates (1.4.2): the first copy's name and the size of one copy, "IMG_1234.jpg · 4.2 MB
     * each" (the header's count says how many copies). Without a group (found since the last load), only the name.
     */
    public String label(List<TimelineItem> copiesOfOne) {
        TimelineItem first = copiesOfOne.getFirst();
        Copy copy = byId.get(first.ref().id());
        String name = String.valueOf(first.ref().path().getFileName());
        return copy == null ? name : name + "  \u00b7  " + sizeText(copy.size()) + " each";
    }

    /**
     * Only the items whose file has another copy among {@code items} (1.4.2): a file whose other copy is hidden, or
     * filtered out, would stand alone on Duplicates with nothing to compare it with.
     */
    public List<TimelineItem> withCopiesShown(List<TimelineItem> items) {
        Map<String, Integer> shownPerGroup = new HashMap<>();
        for (TimelineItem item : items) {
            String group = groupOf(item.ref().id());
            if (group != null) {
                shownPerGroup.merge(group, 1, Integer::sum);
            }
        }
        List<TimelineItem> kept = new ArrayList<>(items.size());
        for (TimelineItem item : items) {
            String group = groupOf(item.ref().id());
            if (group != null && shownPerGroup.get(group) > 1) {
                kept.add(item);
            }
        }
        return kept;
    }

    /** "340 KB", "4.2 MB", "1.3 GB". */
    public static String sizeText(long bytes) {
        if (bytes < 1024 * 1024) {
            return "%,d KB".formatted(Math.max(1, Math.round(bytes / 1024.0)));
        }
        double mb = bytes / (1024.0 * 1024);
        return mb < 1024 ? "%.1f MB".formatted(mb) : "%.1f GB".formatted(mb / 1024);
    }
}
