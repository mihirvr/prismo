package io.github.mihirvr.prismo.ui;

import java.nio.IntBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.paint.Paint;

/**
 * The main window's background, under everything else. With colours on it is the website's moving gradient (the
 * same formula as frontend/src/Gradient.jsx: noise warping noise, walking the logo's colours there and back, under
 * the same light or dark veil); with colours off it is the theme's plain colour; AMOLED is pure black.
 *
 * <p>JavaFX has no pixel shaders, so the formula runs in Java on a small picture (the pattern is very soft, so a
 * picture about 112 pixels wide, scaled up smoothly, looks the same as a full-size one) on a background thread.
 * Still: one picture. Moving: about 10 pictures a second, driven by a plain timer (not a JavaFX animation, which
 * would wake JavaFX 60 times a second for the whole window).
 *
 * <p>Step 3 (2026-11-10): the still picture is the most colourful of {@link #STILL_CANDIDATES} moments of the swirl
 * (the same one every time, so the main window and Settings match); the movement starts from it and only goes on
 * while moving, so a pause never jumps. Each moving picture's drawing time is measured, so {@link BackdropMotion}
 * can keep a slow computer still. Whether it moves is decided there, not here.
 */
public final class Backdrop extends Pane {
    /** What the background shows. */
    public enum Mode { CALM_LIGHT, CALM_DARK, COLOURS_LIGHT, COLOURS_DARK, BLACK }

    // The logo's colours, cyan to yellow, with yellow twice (as on the website: it gets a band of its own).
    private static final int[] LOOP = {0x16affb, 0x1a59ee, 0x5a3df0, 0xb72ae8, 0xe43bba, 0xf64b77, 0xfc7842,
            0xfbbe43, 0xffd23f};
    private static final int N = LOOP.length;
    private static final double SPEED = 0.035; // the website's pace: noticed only when you look for it
    private static final int PICTURE_WIDTH = 112;
    private static final long FRAME_MS = 100; // about 10 pictures a second

    /** How much colour shows by default, in percent: a bit calmer than the website, for an app that stays open. */
    public static final int DEFAULT_STRENGTH = 40;

    private final ImageView view = new ImageView();
    private volatile double strength = DEFAULT_STRENGTH / 100.0;
    /** Moments of the swirl tried for the still picture, {@link #STILL_STEP} apart. */
    static final int STILL_CANDIDATES = 16;
    static final double STILL_STEP = 2.5;
    /** Where the swirl is: starts at the still picture, moves on only while moving. Worker thread. */
    private double time = -1;
    private long lastFrame;
    private volatile double averageFrameMs;
    /** How long a moving picture waits for the JavaFX thread, on average: high = the whole window lags. */
    private volatile double averageLagMs;
    /** Moving pictures drawn; the first {@link #WARM_UP_FRAMES} are not timed (Java is still compiling the code). */
    private int movingFrames;
    static final int WARM_UP_FRAMES = 20;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "backdrop");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private ScheduledFuture<?> ticking;
    private volatile Mode mode;
    private volatile int pictureW = PICTURE_WIDTH;
    private volatile int pictureH = 70;
    private boolean moving;

    public Backdrop(Mode mode) {
        setMouseTransparent(true);
        view.setSmooth(true); // scaled up with smoothing: that is what makes the small picture soft
        view.setManaged(false);
        getChildren().add(view);
        widthProperty().addListener((_, _, _) -> resized());
        heightProperty().addListener((_, _, _) -> resized());
        setMode(mode);
    }

    /** The background for a look (Follow system already resolved) and the Colours switch. */
    public static Mode modeFor(Theme shown, boolean colours) {
        return switch (shown) {
            case AMOLED -> Mode.BLACK;
            case DARK -> colours ? Mode.COLOURS_DARK : Mode.CALM_DARK;
            default -> colours ? Mode.COLOURS_LIGHT : Mode.CALM_LIGHT;
        };
    }

    /** Stops the background's worker thread; for a background that goes away with its window (a dialog). */
    public void dispose() {
        setMoving(false);
        worker.shutdownNow();
    }

    /**
     * A still copy of what the background shows now, as a fill for another window (a dialog's scene): the
     * current colour picture stretched over the window, or the plain colour.
     */
    public Paint stillFill() {
        if (hasPattern() && view.getImage() != null) {
            return new ImagePattern(view.getImage(), 0, 0, 1, 1, true);
        }
        return getBackground() != null && !getBackground().getFills().isEmpty()
                ? getBackground().getFills().getFirst().getFill() : Color.TRANSPARENT;
    }

    /** The colour background of a window's scene, if it has one (the main window's root holds it). */
    public static java.util.Optional<Backdrop> of(javafx.scene.Scene scene) {
        if (scene != null && scene.getRoot() instanceof javafx.scene.Parent root) {
            for (javafx.scene.Node child : root.getChildrenUnmodifiable()) {
                if (child instanceof Backdrop backdrop) {
                    return java.util.Optional.of(backdrop);
                }
            }
        }
        return java.util.Optional.empty();
    }

    public Mode getMode() {
        return mode;
    }

    /** Changes what the background shows (a theme or colours change). */
    public void setMode(Mode newMode) {
        mode = newMode;
        Color base = switch (newMode) {
            case CALM_LIGHT -> Color.web("#f5f6f8"); // the plain themes' own page colours (prismo.css, theme-dark.css)
            case CALM_DARK -> Color.web("#14161b");
            case COLOURS_LIGHT -> Color.web("#eef0f6");
            case COLOURS_DARK -> Color.web("#090a14");
            case BLACK -> Color.BLACK;
        };
        setBackground(new Background(new BackgroundFill(base, CornerRadii.EMPTY, Insets.EMPTY)));
        view.setVisible(hasPattern());
        setMoving(moving);
        drawOnce();
    }

    /** How much colour shows, 0 to 100 %: 0 is only the veil (near black, or the light page), 100 no veil. */
    public void setStrength(int percent) {
        strength = Math.clamp(percent, 0, 100) / 100.0;
        drawOnce();
    }

    /** Starts or stops the movement. Only the colourful modes move. */
    public void setMoving(boolean on) {
        moving = on;
        boolean run = on && hasPattern() && !worker.isShutdown();
        if (run && ticking == null) {
            worker.execute(() -> lastFrame = System.nanoTime()); // the first moving picture moves on from here
            ticking = worker.scheduleAtFixedRate(() -> draw(true), FRAME_MS, FRAME_MS, TimeUnit.MILLISECONDS);
        } else if (!run && ticking != null) {
            ticking.cancel(false);
            ticking = null;
        }
    }

    /** The moving pictures' average drawing time in milliseconds (0 until it has moved). */
    public double averageFrameMs() {
        return averageFrameMs;
    }

    /** How long a moving picture waits to be shown, on average, in milliseconds (0 until it has moved). */
    public double averageLagMs() {
        return averageLagMs;
    }

    /** Forgets the timings (the user turned movement back on: it gets a fresh start, warm-up included). */
    public void resetTiming() {
        if (!worker.isShutdown()) {
            worker.execute(() -> {
                movingFrames = 0;
                averageFrameMs = 0;
                averageLagMs = 0;
            });
        }
    }

    public boolean isMoving() {
        return ticking != null;
    }

    /** True when it shows colours (Colours on, not AMOLED): only then can it move. */
    public boolean hasColours() {
        return hasPattern();
    }

    private boolean hasPattern() {
        return mode == Mode.COLOURS_LIGHT || mode == Mode.COLOURS_DARK;
    }

    private void resized() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        view.setFitWidth(w);
        view.setFitHeight(h);
        pictureW = PICTURE_WIDTH;
        pictureH = Math.max(8, (int) Math.round(PICTURE_WIDTH * h / w));
        drawOnce();
    }

    private void drawOnce() {
        if (hasPattern() && ticking == null && !worker.isShutdown()) {
            worker.execute(() -> draw(false));
        }
    }

    // Runs on the worker thread: computes one picture, then hands it to the JavaFX thread. A moving picture moves
    // the swirl on by the time since the last one (at most a third of a second, so a stall never jumps).
    private void draw(boolean move) {
        Mode m = mode;
        if (m != Mode.COLOURS_LIGHT && m != Mode.COLOURS_DARK) {
            return;
        }
        int w = pictureW, h = pictureH;
        if (time < 0) {
            time = mostColourful(w, h);
        }
        long began = System.nanoTime();
        if (move) {
            time += Math.min(0.33, (began - lastFrame) / 1e9) * SPEED;
            lastFrame = began;
        }
        int[] pixels = render(w, h, time, m == Mode.COLOURS_DARK, strength);
        if (move && ++movingFrames > WARM_UP_FRAMES) {
            double ms = (System.nanoTime() - began) / 1e6;
            averageFrameMs = averageFrameMs == 0 ? ms : averageFrameMs * 0.9 + ms * 0.1;
        }
        boolean timed = move && movingFrames > WARM_UP_FRAMES;
        long posted = System.nanoTime();
        Platform.runLater(() -> {
            if (timed) {
                double lag = (System.nanoTime() - posted) / 1e6;
                averageLagMs = averageLagMs == 0 ? lag : averageLagMs * 0.9 + lag * 0.1;
            }
            WritableImage image = view.getImage() instanceof WritableImage wi
                    && (int) wi.getWidth() == w && (int) wi.getHeight() == h ? wi : new WritableImage(w, h);
            image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbPreInstance(), IntBuffer.wrap(pixels), w);
            if (view.getImage() != image) {
                view.setImage(image);
            }
        });
    }

    /**
     * The moment of the swirl with the most colours (STILL_CANDIDATES moments tried on a small picture): the still
     * picture should show the logo's whole range, not a moment that happens to be mostly one colour.
     */
    static double mostColourful(int w, int h) {
        int sw = 48, sh = Math.max(8, Math.round(48f * h / Math.max(1, w)));
        double best = 0, bestScore = -1;
        for (int k = 0; k < STILL_CANDIDATES; k++) {
            double t = k * STILL_STEP;
            double score = colourfulness(render(sw, sh, t, true, 1.0));
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    /**
     * How many colours a picture shows: the hues (12 slices of the colour wheel) that cover at least 3 % of it in a
     * clearly coloured pixel, then how saturated it is on average (a tie-break below one hue).
     */
    static double colourfulness(int[] argb) {
        int[] slices = new int[12];
        double saturation = 0;
        float[] hsb = new float[3];
        for (int pixel : argb) {
            java.awt.Color.RGBtoHSB((pixel >> 16) & 0xff, (pixel >> 8) & 0xff, pixel & 0xff, hsb);
            saturation += hsb[1];
            if (hsb[1] > 0.25f && hsb[2] > 0.2f) {
                slices[Math.min(11, (int) (hsb[0] * 12))]++;
            }
        }
        int hues = 0;
        for (int count : slices) {
            if (count >= argb.length * 0.03) {
                hues++;
            }
        }
        return hues + saturation / Math.max(1, argb.length) * 0.99;
    }

    /** The website's gradient as {@code w} x {@code h} opaque ARGB pixels at time {@code t}. */
    static int[] render(int w, int h, double t, boolean dark, double strength) {
        // The website's moods: light lifts the colours towards white, dark deepens them.
        double lift = dark ? 0.0 : 0.12, gain = dark ? 0.92 : 1.0;
        // The website's canvas is zoomed 1.18 times (styles.css .bg .gl), so the field shown is a little smaller.
        double scale = 1.6 / 1.18 / Math.min(w, h);
        int n = w * h;
        double[] red = new double[n], green = new double[n], blue = new double[n];
        double[] c = new double[3];
        for (int y = 0; y < h; y++) {
            // The shader's y runs bottom to top (gl_FragCoord); flipped so the picture matches the website.
            double py = ((h - 1 - y) + 0.5 - h / 2.0) * scale;
            for (int x = 0; x < w; x++) {
                double px = (x + 0.5 - w / 2.0) * scale;
                double qx = fbm(px, py + t);
                double qy = fbm(px + 5.2 - t * 0.7, py + 1.3 - t * 0.7);
                double rx = fbm(px + 2.2 * qx + 1.7 + t * 0.6, py + 2.2 * qy + 9.2 + t * 0.6);
                double ry = fbm(px + 2.2 * qx + 8.3 - t * 0.5, py + 2.2 * qy + 2.8 - t * 0.5);
                double v = fbm(px + 2.4 * rx, py + 2.4 * ry);
                palette(v * 3.0 + rx * 1.1 + qy * 0.5 + t * 0.15, c);
                int i = y * w + x;
                red[i] = (c[0] + (1 - c[0]) * lift) * gain;
                green[i] = (c[1] + (1 - c[1]) * lift) * gain;
                blue[i] = (c[2] + (1 - c[2]) * lift) * gain;
            }
        }
        // The website's blur(48px) on a full-size canvas is about 3 % of the width; three box blurs of radius
        // w / 37 come to the same soft Gaussian on this small picture.
        int radius = Math.max(1, Math.round(w / 37f));
        for (double[] ch : new double[][] {red, green, blue}) {
            for (int pass = 0; pass < 3; pass++) {
                boxBlur(ch, w, h, radius, true);
                boxBlur(ch, w, h, radius, false);
            }
        }
        // Then saturate(1.15) (the CSS filter's matrix) and the page's see-through veil (styles.css --veil).
        // The veil hides (1 - strength) of the colour: the website itself shows about half (strength 0.5).
        double veilA = 1 - strength;
        double veilR = dark ? 7 : 244, veilG = dark ? 8 : 245, veilB = dark ? 18 : 250;
        double sat = 1.15;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            double r0 = red[i], g0 = green[i], b0 = blue[i];
            double r = (0.213 + 0.787 * sat) * r0 + (0.715 - 0.715 * sat) * g0 + (0.072 - 0.072 * sat) * b0;
            double g = (0.213 - 0.213 * sat) * r0 + (0.715 + 0.285 * sat) * g0 + (0.072 - 0.072 * sat) * b0;
            double b = (0.213 - 0.213 * sat) * r0 + (0.715 - 0.715 * sat) * g0 + (0.072 + 0.928 * sat) * b0;
            r = Math.min(1, Math.max(0, r)) * 255 * (1 - veilA) + veilR * veilA;
            g = Math.min(1, Math.max(0, g)) * 255 * (1 - veilA) + veilG * veilA;
            b = Math.min(1, Math.max(0, b)) * 255 * (1 - veilA) + veilB * veilA;
            out[i] = 0xff000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
        }
        return out;
    }

    // One box blur along rows (horizontal) or columns; the edge pixels repeat, so the borders don't darken.
    private static void boxBlur(double[] ch, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        double[] line = new double[len];
        double norm = 1.0 / (2 * r + 1);
        for (int l = 0; l < lines; l++) {
            for (int k = 0; k < len; k++) {
                line[k] = ch[horizontal ? l * w + k : k * w + l];
            }
            double sum = 0;
            for (int k = -r; k <= r; k++) {
                sum += line[Math.min(len - 1, Math.max(0, k))];
            }
            for (int k = 0; k < len; k++) {
                ch[horizontal ? l * w + k : k * w + l] = sum * norm;
                sum += line[Math.min(len - 1, k + r + 1)] - line[Math.max(0, k - r)];
            }
        }
    }

    private static int clamp(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }

    private static double hash(double x, double y) {
        double s = Math.sin(x * 127.1 + y * 311.7) * 43758.5453;
        return s - Math.floor(s);
    }

    // Smooth value noise, 0..1 (the shader's noise()).
    private static double noise(double x, double y) {
        double ix = Math.floor(x), iy = Math.floor(y);
        double fx = x - ix, fy = y - iy;
        double ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy);
        double a = hash(ix, iy), b = hash(ix + 1, iy), c = hash(ix, iy + 1), d = hash(ix + 1, iy + 1);
        double top = a + (b - a) * ux, bottom = c + (d - c) * ux;
        return top + (bottom - top) * uy;
    }

    private static double fbm(double x, double y) {
        double v = 0, amp = 0.5;
        for (int k = 0; k < 4; k++) {
            v += amp * noise(x, y);
            x = x * 2.03 + 17.0;
            y = y * 2.03 + 9.0;
            amp *= 0.5;
        }
        return v;
    }

    // The palette walked there and back: cyan > ... > yellow > ... > cyan, never straight from yellow to cyan.
    private static void palette(double x, double[] out) {
        double y = (x * 0.5 - Math.floor(x * 0.5)) * 2.0;
        y = y < 1 ? y : 2 - y;
        double s = y * (N - 1);
        int i = (int) Math.min(Math.floor(s), N - 2);
        double f = s - i;
        f = f * f * (3 - 2 * f); // smoothstep
        int a = LOOP[i], b = LOOP[i + 1];
        out[0] = (((a >> 16) & 255) + ((((b >> 16) & 255) - ((a >> 16) & 255)) * f)) / 255.0;
        out[1] = (((a >> 8) & 255) + ((((b >> 8) & 255) - ((a >> 8) & 255)) * f)) / 255.0;
        out[2] = ((a & 255) + (((b & 255) - (a & 255)) * f)) / 255.0;
    }
}
