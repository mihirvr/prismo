package io.github.mihirvr.prismo.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Folders the indexer skips (PROJECT.md "Ignored Paths"): nothing inside them is indexed or watched, and media
 * already indexed there leaves the library. Three kinds of rule, all matched without regard to case:
 * <ul>
 *   <li><b>A folder name</b> ({@code node_modules}): that name anywhere inside a library folder.</li>
 *   <li><b>A short path</b> ({@code Android\data}): those folders one inside the other, anywhere.</li>
 *   <li><b>A full folder</b> ({@code D:\Photos\Private}): that folder and everything in it.</li>
 * </ul>
 * Saved as one rule per line ({@link #toSetting}). Plain Java, unit-tested.
 */
public record IgnoreRules(List<String> rules) {

    /** For a new install: build output, version control, and Windows / Android system folders. */
    public static final List<String> DEFAULTS = List.of("node_modules", ".git", "$RECYCLE.BIN",
            "System Volume Information", "Android\\data");
    public static final IgnoreRules NONE = new IgnoreRules(List.of());

    public IgnoreRules {
        List<String> clean = new ArrayList<>();
        for (String rule : rules) {
            String trimmed = trim(rule);
            if (!trimmed.isEmpty() && clean.stream().noneMatch(r -> r.equalsIgnoreCase(trimmed))) {
                clean.add(trimmed);
            }
        }
        rules = List.copyOf(clean);
    }

    /** The saved text back to rules; null (never saved) gives the {@link #DEFAULTS}. */
    public static IgnoreRules fromSetting(String text) {
        return new IgnoreRules(text == null ? DEFAULTS : List.of(text.split("\\R")));
    }

    public String toSetting() {
        return String.join("\n", rules);
    }

    public IgnoreRules with(String rule) {
        List<String> more = new ArrayList<>(rules);
        more.add(rule);
        return new IgnoreRules(more);
    }

    public IgnoreRules without(String rule) {
        return new IgnoreRules(rules.stream().filter(r -> !r.equalsIgnoreCase(trim(rule))).toList());
    }

    /** True if {@code path} is an ignored folder or inside one. {@code root} (its library folder) never is. */
    public boolean ignores(Path root, Path path) {
        if (rules.isEmpty() || path.equals(root)) {
            return false;
        }
        String whole = path.toString().toLowerCase(Locale.ROOT);
        Path inside = path.startsWith(root) ? root.relativize(path) : path;
        List<String> names = new ArrayList<>();
        for (Path name : inside) {
            names.add(name.toString().toLowerCase(Locale.ROOT));
        }
        for (String rule : rules) {
            String wanted = rule.toLowerCase(Locale.ROOT);
            if (isFullFolder(rule)) {
                if (whole.equals(wanted) || whole.startsWith(wanted + "\\") || whole.startsWith(wanted + "/")) {
                    return true;
                }
            } else if (containsInOrder(names, List.of(wanted.split("[\\\\/]")))) {
                return true;
            }
        }
        return false;
    }

    /** "D:\..." or "\\server\..." or "/home/...": a whole folder, not a name to look for anywhere. */
    public static boolean isFullFolder(String rule) {
        return rule.startsWith("/") || rule.startsWith("\\\\") || rule.length() >= 2 && rule.charAt(1) == ':';
    }

    private static boolean containsInOrder(List<String> names, List<String> wanted) {
        for (int start = 0; start + wanted.size() <= names.size(); start++) {
            if (names.subList(start, start + wanted.size()).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static String trim(String rule) {
        String trimmed = rule.strip();
        while (trimmed.length() > 1 && (trimmed.endsWith("\\") || trimmed.endsWith("/"))
                && !(trimmed.length() == 3 && trimmed.charAt(1) == ':')) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
