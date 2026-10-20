package io.github.mihirvr.prismo.video;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subtitle lines with their times, read from SubRip (SRT) text, and "what's on screen at time t".
 *
 * <p>SRT is a numbered list of blocks: a number, {@code 00:01:02,500 --> 00:01:04,000}, then the text lines, then
 * a blank line. Formatting is dropped (HTML-like tags such as {@code <i>}, and ASS override codes such as
 * {@code {\an8}} that some converters leave in), so text is shown plain. Subtitles inside a video in other text
 * formats (ASS, MP4 text, WebVTT) are converted to SRT by {@code ffmpeg} first ({@link SubtitleSources}).
 */
public final class Subtitles {

    /** One line (or a few) on screen from {@code startMicros} until just before {@code endMicros}. */
    public record Cue(long startMicros, long endMicros, String text) {
    }

    public static final Subtitles NONE = new Subtitles(List.of());

    /** "00:01:02,500" or "1:02.5" (hours optional, comma or dot, 1–3 decimals). */
    private static final Pattern TIME = Pattern.compile("(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})[,.](\\d{1,3})");
    private static final Pattern TIMES = Pattern.compile(TIME.pattern() + "\\s*-->\\s*" + TIME.pattern());
    private static final Pattern TAG = Pattern.compile("<[^>]*>|\\{\\\\[^}]*}");
    /** At most this many earlier cues are checked for overlapping a later one (songs over dialogue). */
    private static final int OVERLAP_LOOKBACK = 20;

    private final List<Cue> cues;

    private Subtitles(List<Cue> cues) {
        this.cues = cues;
    }

    /** Reads SRT text. Blocks it can't read are skipped. */
    public static Subtitles parseSrt(String text) {
        List<Cue> cues = new ArrayList<>();
        String clean = text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n');
        for (String block : clean.split("\n\\s*\n")) {
            String[] lines = block.strip().split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher times = TIMES.matcher(lines[i]);
                if (times.find()) {
                    long start = micros(times, 1);
                    long end = micros(times, 5);
                    String body = textOf(lines, i + 1);
                    if (end > start && !body.isEmpty()) {
                        cues.add(new Cue(start, end, body));
                    }
                    break;
                }
            }
        }
        cues.sort(Comparator.comparingLong(Cue::startMicros));
        return new Subtitles(List.copyOf(cues));
    }

    /**
     * Subtitle file bytes as text: UTF-8 (with or without a byte-order mark), UTF-16 with a mark, otherwise
     * Windows-1252 (what older Western subtitle files use).
     */
    public static String decode(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /** The text on screen at {@code micros}, lines of overlapping cues one under the other; "" for none. */
    public String at(long micros) {
        int last = lastStartingBy(micros);
        if (last < 0) {
            return "";
        }
        List<String> shown = new ArrayList<>();
        for (int i = Math.max(0, last - OVERLAP_LOOKBACK); i <= last; i++) {
            Cue cue = cues.get(i);
            if (cue.endMicros() > micros) {
                shown.add(cue.text());
            }
        }
        return String.join("\n", shown);
    }

    public List<Cue> cues() {
        return cues;
    }

    public boolean isEmpty() {
        return cues.isEmpty();
    }

    /** The last cue that starts at or before {@code micros} (binary search), or -1. */
    private int lastStartingBy(long micros) {
        int low = 0;
        int high = cues.size() - 1;
        int found = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (cues.get(middle).startMicros() <= micros) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found;
    }

    private static long micros(Matcher m, int group) {
        long hours = m.group(group) == null ? 0 : Long.parseLong(m.group(group));
        long minutes = Long.parseLong(m.group(group + 1));
        long seconds = Long.parseLong(m.group(group + 2));
        String fraction = (m.group(group + 3) + "00").substring(0, 3); // "5" → 500 ms
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000_000 + Long.parseLong(fraction) * 1_000;
    }

    private static String textOf(String[] lines, int from) {
        List<String> text = new ArrayList<>();
        for (int i = from; i < lines.length; i++) {
            String line = TAG.matcher(lines[i]).replaceAll("").replace("\\N", "\n").replace("\\n", "\n")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&nbsp;", " ").strip();
            if (!line.isEmpty()) {
                text.add(line);
            }
        }
        return String.join("\n", text);
    }
}
