package io.github.mihirvr.prismo.ui;

/** Which headers a grid shows between its rows (1.1.0). The Folders view has its own folder headers. */
public enum Headers {
    /** A plain grid. */
    NONE,
    /** Date headers (a date sort): "October 2026". */
    DATES,
    /** Letter headers (the Name sort): "0-9", "A" to "Z", "#". */
    LETTERS
}
