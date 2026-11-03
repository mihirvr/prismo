package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.index.MediaType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Types menu (1.1.0): file types as people know them. "JPG" covers {@code .jpg} and {@code .jpeg}, "HEIC"
 * {@code .heic} and {@code .heif}, "TIF" {@code .tif} and {@code .tiff}; every other type is its own extension.
 * A type is stored by its first extension ({@code jpg}). Only extensions Prismo indexes get in, so the SQL built from
 * them never holds user text.
 */
public final class FileTypes {

    private static final Map<String, String> SAME_AS = Map.of("jpeg", "jpg", "heif", "heic", "tiff", "tif");
    private static final Map<String, List<String>> MEMBERS = Map.of(
            "jpg", List.of("jpg", "jpeg"), "heic", List.of("heic", "heif"), "tif", List.of("tif", "tiff"));

    private FileTypes() {
    }

    /** The type an extension belongs to ({@code jpeg} gives {@code jpg}), or null if Prismo doesn't index it. */
    public static String typeOf(String extension) {
        String lower = extension.toLowerCase(Locale.ROOT);
        if (!MediaType.isKnown(lower)) {
            return null;
        }
        return SAME_AS.getOrDefault(lower, lower);
    }

    /** The types of these extensions, sorted, without duplicates or unknown ones. */
    public static List<String> typesOf(Collection<String> extensions) {
        Set<String> types = new TreeSet<>();
        for (String extension : extensions) {
            String type = typeOf(extension);
            if (type != null) {
                types.add(type);
            }
        }
        return List.copyOf(types);
    }

    /** What the menu shows: "JPG". */
    public static String label(String type) {
        return type.toUpperCase(Locale.ROOT);
    }

    /** {@code extension IN ('jpg', 'jpeg', 'png')} for these types, or null for none (= all). */
    static String sqlCondition(Collection<String> types) {
        List<String> extensions = new ArrayList<>();
        for (String type : new TreeSet<>(types)) {
            if (typeOf(type) != null) {
                extensions.addAll(MEMBERS.getOrDefault(type, List.of(type)));
            }
        }
        if (extensions.isEmpty()) {
            return null;
        }
        return "extension IN ('" + String.join("', '", extensions) + "')";
    }
}
