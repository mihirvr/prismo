package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.video.VideoPlayer;
import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;

/**
 * Plays one video with {@link VideoPlayer} (FFmpeg): the picture, plus play/pause, a seek bar, the time and
 * volume/mute.
 *
 * <p>One player at a time. It holds native decoders, a sound device and two threads, so {@link #stop} closes
 * it (the viewer calls it whenever it moves to another item or closes). The player opens the file on its own
 * thread; anything it reports for an older video (a generation counter) is ignored.
 *
 * <p>Pictures are drawn on every JavaFX pulse (an {@link AnimationTimer}): the newest one the player has ready
 * is copied into a {@link WritableImage} and handed back. The same pulse moves the seek bar and the time.
 *
 * <p>Volume and mute are kept across videos while the app runs.
 */
final class VideoPane extends StackPane {

    /** Within this much of the end, Play starts again from the top. */
    private static final long END_SLACK_MICROS = 100_000;

    private final ImageView picture = new ImageView();
    private final Button playPause = new Button("❚❚");
    private final Slider seek = new Slider(0, 1, 0);
    private final Label time = new Label(PlaybackTime.format(0, 0));
    private final Button mute = new Button("Mute");
    private final Slider volume = new Slider(0, 1, 1);
    private final BooleanProperty muted = new SimpleBooleanProperty(false);
    private final AnimationTimer pulse = new AnimationTimer() {
        @Override
        public void handle(long now) {
            drawNewestPicture();
            showTime();
        }
    };

    private VideoPlayer player;
    private WritableImage image;
    /** Clockwise quarter turn the picture is shown at (phones store upright videos sideways plus a tag). */
    private int rotation;
    private boolean pictureShown;
    private Runnable onFirstPicture = () -> { };
    /** Bumped on every {@link #play} and {@link #stop}: reports for an older player are dropped. */
    private long generation;
    /** True while the seek bar is being moved to follow playback, so that move isn't taken as a user seek. */
    private boolean followingPlayback;

    VideoPane() {
        getStyleClass().add("viewer-video");
        picture.setPreserveRatio(true);
        picture.setSmooth(true);
        widthProperty().addListener((_, _, _) -> fitPicture());
        heightProperty().addListener((_, _, _) -> fitPicture());

        seek.getStyleClass().add("viewer-seek");
        HBox.setHgrow(seek, Priority.ALWAYS);
        seek.valueProperty().addListener((_, _, value) -> {
            if (!followingPlayback && player != null) {
                player.seek(Math.round(value.doubleValue() * 1_000_000));
            }
        });
        volume.setPrefWidth(90);
        volume.valueProperty().addListener((_, _, value) -> {
            if (player != null) {
                player.setVolume(value.doubleValue());
            }
        });
        mute.textProperty().bind(muted.map(m -> m ? "Unmute" : "Mute"));
        mute.setOnAction(_ -> muted.set(!muted.get()));
        muted.addListener((_, _, on) -> {
            if (player != null) {
                player.setMuted(on);
            }
        });
        playPause.setOnAction(_ -> togglePlay());
        for (Control control : new Control[] {playPause, seek, mute, volume}) {
            control.setFocusTraversable(false); // the viewer handles every key itself
        }

        HBox controls = new HBox(8, playPause, seek, time, mute, volume);
        controls.getStyleClass().add("viewer-video-controls");
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setMaxHeight(USE_PREF_SIZE);
        StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
        getChildren().addAll(picture, controls);
    }

    /**
     * Opens {@code file} and starts playing. {@code onError} gets a short reason on the UI thread if it can't
     * be played (missing, damaged, unknown format); {@code onFirstPicture} runs when the first picture is on
     * screen.
     */
    void play(Path file, Consumer<String> onError, Runnable onFirstPicture) {
        stop();
        long mine = generation;
        this.onFirstPicture = onFirstPicture;
        // Decode at most at the screen's real pixel size: a 4K video on a 1080p screen needs a quarter of the memory.
        Screen screen = Screen.getPrimary();
        Rectangle2D bounds = screen.getBounds();
        int maxWidth = (int) Math.ceil(bounds.getWidth() * screen.getOutputScaleX());
        int maxHeight = (int) Math.ceil(bounds.getHeight() * screen.getOutputScaleY());
        player = VideoPlayer.open(file, maxWidth, maxHeight, new VideoPlayer.Listener() {
            @Override
            public void opened(VideoPlayer.Info info) {
                Platform.runLater(() -> {
                    if (generation == mine) {
                        seek.setMax(Math.max(0.001, info.durationMicros() / 1e6));
                        rotation = info.rotation();
                        picture.setRotate(rotation);
                        fitPicture();
                    }
                });
            }

            @Override
            public void ended() {
                // The player paused itself on the last picture; the pulse shows ▶.
            }

            @Override
            public void failed(String reason) {
                Platform.runLater(() -> {
                    if (generation == mine) {
                        stop();
                        onError.accept(reason);
                    }
                });
            }
        });
        player.setVolume(volume.getValue());
        player.setMuted(muted.get());
        pulse.start();
    }

    private void drawNewestPicture() {
        if (player == null) {
            return;
        }
        VideoPlayer.Picture newest = player.takePicture();
        if (newest == null) {
            return;
        }
        int width = newest.width();
        int height = newest.height();
        if (image == null || image.getWidth() != width || image.getHeight() != height) {
            image = new WritableImage(width, height);
            picture.setImage(image);
        }
        image.getPixelWriter().setPixels(0, 0, width, height, PixelFormat.getIntArgbPreInstance(), newest.argb(), 0, width);
        player.recycle(newest);
        if (!pictureShown) {
            pictureShown = true;
            onFirstPicture.run();
        }
    }

    /** Fills the pane, keeping the shape; a picture turned by 90° swaps which side has to fit where. */
    private void fitPicture() {
        boolean sideways = rotation % 180 != 0;
        picture.setFitWidth(sideways ? getHeight() : getWidth());
        picture.setFitHeight(sideways ? getWidth() : getHeight());
    }

    private void showTime() {
        if (player == null) {
            return;
        }
        double current = player.position() / 1e6;
        time.setText(PlaybackTime.format(current, player.durationMicros() / 1e6));
        if (!seek.isValueChanging()) {
            followingPlayback = true;
            seek.setValue(current);
            followingPlayback = false;
        }
        playPause.setText(player.isPlaying() ? "❚❚" : "▶");
    }

    /** Space or the button. At the end, plays again from the start. */
    void togglePlay() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            long duration = player.durationMicros();
            if (duration > 0 && player.position() >= duration - END_SLACK_MICROS) {
                player.seek(0);
            }
            player.play();
        }
    }

    /** Stops and closes the player (frees its decoders and the sound device). Safe to call any time. */
    void stop() {
        generation++;
        pulse.stop();
        if (player != null) {
            player.close();
            player = null;
        }
        picture.setImage(null);
        image = null;
        pictureShown = false;
        rotation = 0;
        picture.setRotate(0);
        followingPlayback = true;
        seek.setValue(0);
        followingPlayback = false;
        time.setText(PlaybackTime.format(0, 0));
        playPause.setText("❚❚");
    }

    // --- For tests and tools ---

    /** The player now showing, or null. */
    VideoPlayer player() {
        return player;
    }

    ImageView picture() {
        return picture;
    }

    Slider seekBar() {
        return seek;
    }

    BooleanProperty mutedProperty() {
        return muted;
    }
}
