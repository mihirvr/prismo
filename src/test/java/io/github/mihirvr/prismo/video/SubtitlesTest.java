package io.github.mihirvr.prismo.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SubtitlesTest {

    private static final String SRT = """
            1
            00:00:01,000 --> 00:00:02,500
            <i>Hello</i> <font color="#ff0000">there</font>

            2
            00:00:03,000 --> 00:00:04,000
            {\\an8}Two
            lines &amp; more
            """;

    @Test
    void showsEachLineFromItsStartUntilJustBeforeItsEnd() {
        Subtitles subtitles = Subtitles.parseSrt(SRT);

        assertEquals(2, subtitles.cues().size());
        assertEquals("", subtitles.at(999_999));
        assertEquals("Hello there", subtitles.at(1_000_000), "formatting dropped");
        assertEquals("Hello there", subtitles.at(2_499_999));
        assertEquals("", subtitles.at(2_500_000), "gone at its end time");
        assertEquals("Two\nlines & more", subtitles.at(3_500_000), "ASS codes dropped, lines kept");
        assertEquals("", subtitles.at(60_000_000));
    }

    @Test
    void readsWindowsLineEndsAByteOrderMarkDotsAndShortTimes() {
        String text = "﻿1\r\n00:00:01.5 --> 00:00:02.25\r\nDots\r\n\r\n2\r\n1:00:00,000 --> 1:00:01,000\r\nLate\r\n";
        Subtitles subtitles = Subtitles.parseSrt(text);

        assertEquals(new Subtitles.Cue(1_500_000, 2_250_000, "Dots"), subtitles.cues().get(0));
        assertEquals(3_600_000_000L, subtitles.cues().get(1).startMicros(), "an hour in");
    }

    @Test
    void overlappingLinesShowTogetherInOrder() {
        Subtitles subtitles = Subtitles.parseSrt("""
                1
                00:00:01,000 --> 00:00:05,000
                Song

                2
                00:00:02,000 --> 00:00:03,000
                Talk
                """);

        assertEquals("Song\nTalk", subtitles.at(2_500_000));
        assertEquals("Song", subtitles.at(4_000_000));
    }

    @Test
    void skipsBrokenBlocksAndKeepsTheRest() {
        Subtitles subtitles = Subtitles.parseSrt("""
                1
                not a time
                Lost

                2
                00:00:05,000 --> 00:00:04,000
                Ends before it starts

                3
                00:00:06,000 --> 00:00:07,000
                Kept
                """);

        assertEquals(1, subtitles.cues().size());
        assertEquals("Kept", subtitles.at(6_500_000));
        assertTrue(Subtitles.parseSrt("").isEmpty());
    }

    @Test
    void decodesUtf8Utf16AndOlderWindowsFiles() {
        assertEquals("Café", Subtitles.decode("Café".getBytes(StandardCharsets.UTF_8)));
        byte[] utf16 = "Café".getBytes(StandardCharsets.UTF_16LE);
        byte[] marked = new byte[utf16.length + 2];
        marked[0] = (byte) 0xFF;
        marked[1] = (byte) 0xFE;
        System.arraycopy(utf16, 0, marked, 2, utf16.length);
        assertEquals("Café", Subtitles.decode(marked));
        assertEquals("Café", Subtitles.decode("Café".getBytes(Charset.forName("windows-1252"))),
                "not valid UTF-8: read as Windows-1252");
    }
}
