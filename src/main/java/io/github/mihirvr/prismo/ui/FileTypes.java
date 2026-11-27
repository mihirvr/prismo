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
 * The Types menu (1.1.0): file types as people know them. "JPG" covers {@code .jpg}, {@code .jpeg}, {@code .jpe} and
 * {@code .jfif}, "HEIC" {@code .heic}, {@code .heif} and {@code .hif}, "TIF" {@code .tif} and {@code .tiff}, "MTS"
 * {@code .mts} and {@code .m2ts}, "MPG" {@code .mpg} and {@code .mpeg}; every other type is its own extension.
 * A type is stored by its first extension ({@code jpg}). Only extensions Prismo indexes get in, so the SQL built from
 * them never holds user text.
 */
public final class FileTypes {

    // 2.0.0: JPE and JFIF are JPG too, HIF is HEIC, M2TS is MTS, MPEG is MPG.
    private static final Map<String, String> SAME_AS = Map.of("jpeg", "jpg", "jpe", "jpg", "jfif", "jpg",
            "heif", "heic", "hif", "heic", "tiff", "tif", "m2ts", "mts", "mpeg", "mpg");
    private static final Map<String, List<String>> MEMBERS = Map.of(
            "jpg", List.of("jpg", "jpeg", "jpe", "jfif"), "heic", List.of("heic", "heif", "hif"),
            "tif", List.of("tif", "tiff"), "mts", List.of("mts", "m2ts"), "mpg", List.of("mpg", "mpeg"));

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
