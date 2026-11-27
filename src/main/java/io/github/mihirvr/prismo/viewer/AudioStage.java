package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.thumbs.AudioCovers;
import io.github.mihirvr.prismo.ui.AudioView;
import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.video.SoundTap;
import io.github.mihirvr.prismo.video.VideoPlayer;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Map;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 2.0.0: what the viewer shows while an audio file plays ({@link AudioView}): the album art, or a music note when the
 * file has none, with the title and artist under it; or a picture of the sound itself (a waveform, bars, or bars around
 * a ring) drawn from the player's {@link SoundTap} at the playback position, so it moves with what is heard.
 *
 * <p>Sits over the video pane's empty picture, under its controls. Leaves room at the top for the viewer's bar and at
 * the bottom for the controls and the filmstrip.
 */
final class AudioStage extends Pane {

    private static final Logger log = LoggerFactory.getLogger(AudioStage.class);
    /** The viewer's top bar. */
    private static final double TOP_ROOM = 72;
    /** The controls and the filmstrip under them. */
    private static final double BOTTOM_ROOM = 150;
    /** Samples per picture of the sound: 46 ms at 44.1 kHz, a power of two for the FFT. */
    private static final int WINDOW = 2048;
    private static final int BANDS = 56;
    private static final double LOWEST_HZ = 40;
    private static final double HIGHEST_HZ = 16_000;
    /** The palette of the website's moving background: violet, pink, orange, blue. */
    private static final Color[] COLOURS = {Color.web("#7c3aed"), Color.web("#ec4899"), Color.web("#f97316"),
        Color.web("#3b82f6")};

    private final ObjectProperty<AudioView> view = new SimpleObjectProperty<>(AudioView.DEFAULT);
    private final Canvas canvas = new Canvas();
    private final ImageView cover = new ImageView();
    private final Rectangle coverClip = new Rectangle();
    private final Circle roundClip = new Circle();
    private final StackPane art = new StackPane();
    private final Node note = Icon.MUSIC.node(96);
    private final Label title = new Label();
    private final Label artist = new Label();
    private final VBox caption = new VBox(2, title, artist);
    private final float[] samples = new float[WINDOW];
    private final double[] real = new double[WINDOW];
    private final double[] imaginary = new double[WINDOW];
    private final double[] window = hann(WINDOW);
    private final double[] levels = new double[BANDS];
    private final double[] peaks = new double[BANDS];
    private double waveGain = 1;

    private VideoPlayer player;
    /** Bumped on every show and stop: a cover that loads late for an older file is dropped. */
    private long generation;

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            draw();
        }
    };

    AudioStage() {
        getStyleClass().add("audio-stage");
        setMouseTransparent(true); // clicks go to the video pane (play / pause, double click: full screen)
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        art.getStyleClass().add("audio-art");
        note.getStyleClass().add("audio-note");
        art.getChildren().addAll(note, cover);
        title.getStyleClass().add("audio-title");
        artist.getStyleClass().add("audio-artist");
        caption.setAlignment(Pos.CENTER);
        caption.setFillWidth(false);
        getChildren().addAll(canvas, art, caption);
        view.addListener((_, _, _) -> {
            java.util.Arrays.fill(levels, 0);
            java.util.Arrays.fill(peaks, 0);
            requestLayout();
            run();
        });
        visibleProperty().addListener((_, _, _) -> run());
    }

    ObjectProperty<AudioView> viewProperty() {
        return view;
    }

    /** Shows the file that {@code player} has just opened (it has no moving picture). */
    void show(Path file, VideoPlayer player, VideoPlayer.Info info) {
        long mine = ++generation;
        this.player = player;
        java.util.Arrays.fill(levels, 0);
        java.util.Arrays.fill(peaks, 0);
        Map<String, String> tags = info.tags();
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        title.setText(tags.getOrDefault("title", dot > 0 ? name.substring(0, dot) : name));
        String by = tags.getOrDefault("artist", tags.getOrDefault("album_artist", ""));
        String album = tags.getOrDefault("album", "");
        artist.setText(by.isEmpty() ? album : album.isEmpty() ? by : by + "  ·  " + album);
        artist.setVisible(!artist.getText().isEmpty());
        cover.setImage(null);
        note.setVisible(true);
        if (info.hasCover()) {
            Thread.ofVirtual().name("prismo-audio-cover").start(() -> {
                try {
                    Image image = toImage(AudioCovers.picture(file, 1200));
                    Platform.runLater(() -> {
                        if (generation == mine) {
                            cover.setImage(image);
                            note.setVisible(false);
                            requestLayout();
                        }
                    });
                } catch (Exception e) {
                    log.debug("No cover for {}: {}", file, e.toString());
                }
            });
        }
        requestLayout();
        run();
    }

    /** The file closed: stops drawing. */
    void stop() {
        generation++;
        player = null;
        timer.stop();
        cover.setImage(null);
        clearCanvas();
    }

    private void run() {
        if (player != null && isVisible() && view.get() != AudioView.ALBUM_ART) {
            timer.start();
        } else {
            timer.stop();
            clearCanvas();
        }
    }

    // --- Layout ---

    @Override
    protected void layoutChildren() {
        double width = getWidth();
        double height = getHeight();
        canvas.setWidth(width);
        canvas.setHeight(height);
        double top = TOP_ROOM;
        double usable = Math.max(120, height - TOP_ROOM - BOTTOM_ROOM);
        double captionHeight = 56;
        AudioView chosen = view.get();
        double side;
        double artX;
        double artY;
        boolean round = chosen == AudioView.CIRCLE;
        if (chosen == AudioView.ALBUM_ART) {
            side = Math.min(Math.min(width * 0.62, usable - captionHeight - 16), 640);
            artX = (width - side) / 2;
            artY = top + (usable - captionHeight - 16 - side) / 2;
        } else if (round) {
            double ring = ringRadius(width, usable);
            side = ring * 1.56;
            artX = width / 2 - side / 2;
            artY = ringCentreY(usable) - side / 2;
        } else {
            side = Math.min(Math.min(usable * 0.36, width * 0.3), 220);
            artX = (width - side) / 2;
            artY = top + 8;
        }
        side = Math.max(48, side);
        layoutArt(side, artX, artY, round);
        // Under the ring's longest bars (1.9 radii from the centre), or under the cover.
        double captionY = round ? ringCentreY(usable) + ringRadius(width, usable) * 1.95 + 6 : artY + side + 14;
        caption.setMaxWidth(width * 0.8);
        caption.autosize();
        caption.relocate((width - caption.getWidth()) / 2, captionY);
    }

    private void layoutArt(double side, double x, double y, boolean round) {
        Image image = cover.getImage();
        double coverWidth = side;
        double coverHeight = side;
        if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
            double scale = Math.min(side / image.getWidth(), side / image.getHeight());
            coverWidth = round ? side : image.getWidth() * scale;
            coverHeight = round ? side : image.getHeight() * scale;
        }
        cover.setFitWidth(coverWidth);
        cover.setFitHeight(coverHeight);
        cover.setPreserveRatio(!round);
        if (round) {
            cover.setViewport(squareViewport(image));
            roundClip.setRadius(side / 2);
            roundClip.setCenterX(side / 2);
            roundClip.setCenterY(side / 2);
            art.setClip(roundClip);
        } else {
            cover.setViewport(null);
            coverClip.setWidth(side);
            coverClip.setHeight(side);
            coverClip.setArcWidth(Math.min(28, side / 8));
            coverClip.setArcHeight(Math.min(28, side / 8));
            art.setClip(image == null ? null : coverClip);
        }
        art.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("round"), round);
        if (!round) {
            art.setScaleX(1); // the ring's beat
            art.setScaleY(1);
        }
        note.setScaleX(side / 96 * 0.42);
        note.setScaleY(side / 96 * 0.42);
        art.resizeRelocate(x, y, side, side);
    }

    private static javafx.geometry.Rectangle2D squareViewport(Image image) {
        if (image == null) {
            return null;
        }
        double edge = Math.min(image.getWidth(), image.getHeight());
        return new javafx.geometry.Rectangle2D((image.getWidth() - edge) / 2, (image.getHeight() - edge) / 2, edge,
                edge);
    }

    /** The ring, its longest bars and the caption under it fit the usable height. */
    private static double ringRadius(double width, double usable) {
        return Math.max(36, Math.min(width * 0.16, (usable - 60) / 4.1));
    }

    private static double ringCentreY(double usable) {
        return TOP_ROOM + (usable - 60) / 2;
    }

    // --- Drawing ---

    private void clearCanvas() {
        canvas.getGraphicsContext2D().clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        g.clearRect(0, 0, width, height);
        VideoPlayer playing = player;
        if (playing == null) {
            return;
        }
        SoundTap tap = playing.soundTap();
        boolean have = tap.copy(playing.position(), samples);
        if (!have) {
            java.util.Arrays.fill(samples, 0);
        }
        double usable = Math.max(120, height - TOP_ROOM - BOTTOM_ROOM);
        switch (view.get()) {
            case WAVEFORM -> drawWave(g, width, usable);
            case BARS -> {
                analyse(tap.rate(), have);
                drawBars(g, width, usable);
            }
            case CIRCLE -> {
                analyse(tap.rate(), have);
                drawRing(g, width, usable);
            }
            case ALBUM_ART -> {
                // nothing moves
            }
        }
    }

    /** Below the small cover and its caption: the rest of the usable height. */
    private double lowerTop(double usable) {
        double side = art.getHeight();
        return TOP_ROOM + 8 + side + 70;
    }

    private void drawWave(GraphicsContext g, double width, double usable) {
        double top = lowerTop(usable);
        double bottom = TOP_ROOM + usable;
        double middle = (top + bottom) / 2;
        double half = Math.max(24, (bottom - top) / 2 - 8);
        double left = width * 0.08;
        double span = width * 0.84;
        float loudest = 0;
        for (float sample : samples) {
            loudest = Math.max(loudest, Math.abs(sample));
        }
        // Quiet passages still show: the line is scaled up to the loudest of the last moments, at most 8 times.
        double wanted = loudest > 0.002 ? Math.min(8, 0.9 / loudest) : waveGain;
        waveGain += (wanted - waveGain) * (wanted < waveGain ? 0.35 : 0.04);
        int points = (int) Math.min(WINDOW, Math.max(64, span / 2));
        double step = (double) WINDOW / points;
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(gradient(left, left + span));
        for (int pass = 0; pass < 2; pass++) {
            g.setGlobalAlpha(pass == 0 ? 0.25 : 1);
            g.setLineWidth(pass == 0 ? 9 : 2.6);
            g.beginPath();
            for (int i = 0; i < points; i++) {
                float sample = samples[(int) (i * step)];
                double x = left + span * i / (points - 1);
                double y = middle - Math.clamp(sample * waveGain, -1, 1) * half;
                if (i == 0) {
                    g.moveTo(x, y);
                } else {
                    g.lineTo(x, y);
                }
            }
            g.stroke();
        }
        g.setGlobalAlpha(1);
    }

    private void drawBars(GraphicsContext g, double width, double usable) {
        double top = lowerTop(usable);
        double bottom = TOP_ROOM + usable;
        double tall = Math.max(30, bottom - top - 8);
        double left = width * 0.08;
        double span = width * 0.84;
        double slot = span / BANDS;
        double bar = Math.max(2, slot * 0.62);
        g.setFill(gradient(left, left + span));
        for (int i = 0; i < BANDS; i++) {
            double x = left + i * slot + (slot - bar) / 2;
            double h = Math.max(bar * 0.6, levels[i] * tall);
            g.setGlobalAlpha(1);
            g.fillRoundRect(x, bottom - h, bar, h, bar, bar);
            double cap = bottom - Math.max(bar * 0.6, peaks[i] * tall) - bar * 0.9;
            g.setGlobalAlpha(0.55);
            g.fillRoundRect(x, cap, bar, bar * 0.45, bar * 0.45, bar * 0.45);
        }
        g.setGlobalAlpha(1);
    }

    private void drawRing(GraphicsContext g, double width, double usable) {
        double radius = ringRadius(width, usable);
        double centreX = width / 2;
        double centreY = ringCentreY(usable);
        int spokes = BANDS * 2; // both halves mirrored: bass at the top, treble at the bottom
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineWidth(Math.max(2, 2 * Math.PI * radius / spokes * 0.5));
        for (int i = 0; i < spokes; i++) {
            int band = i < BANDS ? i : spokes - 1 - i;
            double angle = -Math.PI / 2 + 2 * Math.PI * (i + 0.5) / spokes;
            double length = 6 + levels[band] * radius * 0.85;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            double from = radius * 1.06;
            g.setStroke(COLOURS[band * COLOURS.length / BANDS].interpolate(
                    COLOURS[Math.min(COLOURS.length - 1, band * COLOURS.length / BANDS + 1)],
                    band * COLOURS.length / (double) BANDS % 1));
            g.strokeLine(centreX + cos * from, centreY + sin * from, centreX + cos * (from + length),
                    centreY + sin * (from + length));
        }
        double bass = (levels[0] + levels[1] + levels[2] + levels[3]) / 4;
        art.setScaleX(1 + bass * 0.05);
        art.setScaleY(1 + bass * 0.05);
    }

    private static LinearGradient gradient(double from, double to) {
        Stop[] stops = new Stop[COLOURS.length];
        for (int i = 0; i < COLOURS.length; i++) {
            stops[i] = new Stop((double) i / (COLOURS.length - 1), COLOURS[i]);
        }
        return new LinearGradient(from, 0, to, 0, false, CycleMethod.NO_CYCLE, stops);
    }

    /**
     * The window's loudness in {@link #BANDS} bands, spaced evenly in pitch (log scale) from {@link #LOWEST_HZ} to
     * {@link #HIGHEST_HZ} (or half the sample rate): 0 = silent (-75 dB) to 1 (-5 dB), tilted up by 3 dB an octave
     * above 1 kHz and down below it (music is louder in the bass, so the bass bars would otherwise sit at the top and
     * the treble ones at the bottom). Each band jumps up at once
     * and falls back slowly, which is how bars on a music player look; the peak marks fall slower still.
     */
    private void analyse(int rate, boolean have) {
        if (!have || rate <= 0) {
            for (int i = 0; i < BANDS; i++) {
                levels[i] *= 0.85;
                peaks[i] = Math.max(levels[i], peaks[i] - 0.012);
            }
            return;
        }
        for (int i = 0; i < WINDOW; i++) {
            real[i] = samples[i] * window[i];
            imaginary[i] = 0;
        }
        fft(real, imaginary);
        double binHz = (double) rate / WINDOW;
        double highest = Math.min(HIGHEST_HZ, rate / 2.0);
        for (int band = 0; band < BANDS; band++) {
            double lowHz = LOWEST_HZ * Math.pow(highest / LOWEST_HZ, (double) band / BANDS);
            double highHz = LOWEST_HZ * Math.pow(highest / LOWEST_HZ, (double) (band + 1) / BANDS);
            int low = Math.max(1, (int) Math.floor(lowHz / binHz));
            int high = Math.max(low, Math.min(WINDOW / 2 - 1, (int) Math.ceil(highHz / binHz)));
            double power = 0;
            for (int bin = low; bin <= high; bin++) {
                power = Math.max(power, real[bin] * real[bin] + imaginary[bin] * imaginary[bin]);
            }
            // 0 dB = a full-scale sine (a Hann window's peak is a quarter of the window's length).
            double decibels = 10 * Math.log10(power / (WINDOW * WINDOW / 16.0) + 1e-12);
            decibels += 3 * Math.log(Math.sqrt(lowHz * highHz) / 1000) / Math.log(2);
            double level = Math.clamp((decibels + 75) / 70, 0, 1);
            levels[band] = level > levels[band] ? level : levels[band] * 0.86 + level * 0.14;
            peaks[band] = Math.max(levels[band], peaks[band] - 0.012);
        }
    }

    static double[] hann(int size) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1));
        }
        return values;
    }

    /** In-place radix-2 FFT; the length is a power of two. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int length = 2; length <= n; length <<= 1) {
            double angle = -2 * Math.PI / length;
            double stepRe = Math.cos(angle);
            double stepIm = Math.sin(angle);
            for (int start = 0; start < n; start += length) {
                double wRe = 1;
                double wIm = 0;
                for (int k = 0; k < length / 2; k++) {
                    int a = start + k;
                    int b = a + length / 2;
                    double tRe = re[b] * wRe - im[b] * wIm;
                    double tIm = re[b] * wIm + im[b] * wRe;
                    re[b] = re[a] - tRe;
                    im[b] = im[a] - tIm;
                    re[a] += tRe;
                    im[a] += tIm;
                    double next = wRe * stepRe - wIm * stepIm;
                    wIm = wRe * stepIm + wIm * stepRe;
                    wRe = next;
                }
            }
        }
    }

    /** A decoded cover as a JavaFX image ({@code javafx.swing} isn't bundled; see StillDecoder). */
    private static Image toImage(BufferedImage picture) {
        int width = picture.getWidth();
        int height = picture.getHeight();
        int[] argb = picture.getRGB(0, 0, width, height, null, 0, width);
        WritableImage image = new WritableImage(width, height);
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        return image;
    }
}
