package io.github.mihirvr.prismo.ui;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;

/**
 * Natural order for file and folder names (1.1.0): numbers inside names compare by their value, so "2.jpg" comes
 * before "10.jpg". Letters compare without case and without accents ("École" next to "Ecole").
 *
 * <p>The first character decides the block: names that start with a digit first, then letters, then everything
 * else (symbols, spaces). That keeps each letter header ("0-9", "A" to "Z", "#") in one piece. Used in memory
 * ({@link TimelineQuery#items}, folder lists); SQLite calling it as a collation was 14 times slower than NOCASE.
 *
 * <p>Equal-looking names ("img1" and "IMG1", "1" and "01") are told apart at the end (fewer leading zeros first,
 * then by exact text), so the order is total and never changes between runs.
 */
public final class NaturalOrder implements Comparator<String> {

    public static final NaturalOrder INSTANCE = new NaturalOrder();

    private NaturalOrder() {
    }

    @Override
    public int compare(String a, String b) {
        int byBlock = Integer.compare(block(a), block(b));
        if (byBlock != 0) {
            return byBlock;
        }
        int i = 0;
        int j = 0;
        int zeros = 0; // leading-zero difference of the first tied number run, for the final tie-break
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (isDigit(ca) && isDigit(cb)) {
                int endA = runEnd(a, i);
                int endB = runEnd(b, j);
                int startA = skipZeros(a, i, endA);
                int startB = skipZeros(b, j, endB);
                int lengthA = endA - startA;
                int lengthB = endB - startB;
                if (lengthA != lengthB) {
                    return Integer.compare(lengthA, lengthB);
                }
                for (int k = 0; k < lengthA; k++) {
                    int byDigit = Character.compare(a.charAt(startA + k), b.charAt(startB + k));
                    if (byDigit != 0) {
                        return byDigit;
                    }
                }
                if (zeros == 0) {
                    zeros = Integer.compare(startA - i, startB - j);
                }
                i = endA;
                j = endB;
            } else {
                int byChar = Character.compare(fold(ca), fold(cb));
                if (byChar != 0) {
                    return byChar;
                }
                i++;
                j++;
            }
        }
        int byLength = Integer.compare(a.length() - i, b.length() - j);
        if (byLength != 0) {
            return byLength;
        }
        return zeros != 0 ? zeros : a.compareTo(b);
    }

    /**
     * The header a name goes under when sorted by name: "0-9", a capital letter ("A"; "É" counts as "E"), or "#".
     */
    public static String headerOf(String name) {
        return switch (block(name)) {
            case 0 -> "0-9";
            case 1 -> String.valueOf(Character.toUpperCase(fold(name.charAt(0))));
            default -> "#";
        };
    }

    /** 0 = starts with a digit, 1 = with a letter, 2 = anything else (or empty). */
    private static int block(String name) {
        if (name.isEmpty()) {
            return 2;
        }
        char first = name.charAt(0);
        return isDigit(first) ? 0 : Character.isLetter(first) ? 1 : 2;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static int runEnd(String s, int from) {
        int end = from;
        while (end < s.length() && isDigit(s.charAt(end))) {
            end++;
        }
        return end;
    }

    private static int skipZeros(String s, int from, int end) {
        int start = from;
        while (start < end - 1 && s.charAt(start) == '0') {
            start++;
        }
        return start;
    }

    /** Lower case without accents: 'É' and 'e' compare equal. ASCII takes the fast path. */
    private static char fold(char c) {
        if (c < 128) {
            return Character.toLowerCase(c);
        }
        String plain = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        return plain.toLowerCase(Locale.ROOT).charAt(0);
    }
}
