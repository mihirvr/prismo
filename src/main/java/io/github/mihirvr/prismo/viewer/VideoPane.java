package io.github.mihirvr.prismo.viewer;

import java.nio.file.Path;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plays one MP4 / M4V with JavaFX Media: the picture, plus play/pause, a seek bar, the time and volume/mute.
 *
 * <p>One {@link MediaPlayer} at a time. It holds native decoder resources, so {@link #stop} disposes it (the
 * viewer calls it whenever it moves to another item or closes). Opening a file touches the disk, so the
 * player is created on a background platform thread (the player is native code, which would pin a virtual
 * thread) and handed to the UI thread; if the viewer has moved on by then, it's disposed unused.
 *
 * <p>Volume and mute are kept across videos while the app runs.
 */
final class VideoPane extends StackPane {

    private static final Logger log = LoggerFactory.getLogger(VideoPane.class);

    private final MediaView view = new MediaView();
    private final Button playPause = new Button("❚❚");
    private final Slider seek = new Slider(0, 1, 0);
    private final Label time = new Label(PlaybackTime.format(0, 0));
    private final Button mute = new Button("Mute");
    private final Slider volume = new Slider(0, 1, 1);
    private final BooleanProperty muted = new SimpleBooleanProperty(false);

    private MediaPlayer player;
    /** Bumped on every {@link #play} and {@link #stop}: a player that finishes opening for an older one is dropped. */
    private long generation;
    /** True while the seek bar is being moved to follow playback, so that move isn't taken as a user seek. */
    private boolean followingPlayback;

    VideoPane() {
        getStyleClass().add("viewer-video");
        view.setPreserveRatio(true);
        view.fitWidthProperty().bind(widthProperty());
        view.fitHeightProperty().bind(heightProperty());

        seek.getStyleClass().add("viewer-seek");
        HBox.setHgrow(seek, Priority.ALWAYS);
        seek.valueProperty().addListener((_, _, value) -> {
            if (!followingPlayback && player != null) {
                player.seek(Duration.seconds(value.doubleValue()));
            }
        });
        volume.setPrefWidth(90);
        mute.textProperty().bind(muted.map(m -> m ? "Unmute" : "Mute"));
        mute.setOnAction(_ -> muted.set(!muted.get()));
        playPause.setOnAction(_ -> togglePlay());
        for (Control control : new Control[] {playPause, seek, mute, volume}) {
            control.setFocusTraversable(false); // the viewer handles every key itself
        }

        HBox controls = new HBox(8, playPause, seek, time, mute, volume);
        controls.getStyleClass().add("viewer-video-controls");
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setMaxHeight(USE_PREF_SIZE);
        StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
        getChildren().addAll(view, controls);
    }

    /**
     * Opens {@code file} and starts playing. {@code onError} gets a short reason on the UI thread if JavaFX
     * can't play it (unsupported codec, missing or broken file).
     */
    void play(Path file, Consumer<String> onError) {
        stop();
        long mine = generation;
        Thread.ofPlatform().name("prismo-video-open").daemon(true).start(() -> {
            try {
                MediaPlayer opened = new MediaPlayer(new Media(file.toUri().toString()));
                Platform.runLater(() -> attach(mine, opened, onError));
            } catch (RuntimeException e) { // MediaException for unsupported or unreadable files
                log.info("Can't play {}: {}", file, e.toString());
                String reason = e instanceof MediaException media ? describe(media) : "the file couldn't be opened";
                Platform.runLater(() -> {
                    if (generation == mine) {
                        onError.accept(reason);
                    }
                });
            }
        });
    }

    private void attach(long mine, MediaPlayer opened, Consumer<String> onError) {
        if (generation != mine) {
            opened.dispose(); // the viewer moved on while this was opening
            return;
        }
        player = opened;
        view.setMediaPlayer(opened);
        opened.volumeProperty().bind(volume.valueProperty());
        opened.muteProperty().bind(muted);
        opened.setOnError(() -> {
            log.info("Playback failed: {}", opened.getError().toString());
            if (player == opened) {
                onError.accept(describe(opened.getError()));
            }
        });
        opened.setOnReady(() -> {
            seek.setMax(Math.max(0.001, opened.getTotalDuration().toSeconds()));
            showTime(opened);
        });
        opened.currentTimeProperty().addListener((_, _, _) -> showTime(opened));
        opened.statusProperty().addListener((_, _, status) ->
                playPause.setText(status == MediaPlayer.Status.PLAYING ? "❚❚" : "▶"));
        opened.setOnEndOfMedia(opened::pause); // stay on the last frame; Play starts again from the top
        opened.play(); // starts as soon as it's ready
    }

    private void showTime(MediaPlayer shown) {
        double current = shown.getCurrentTime().toSeconds();
        time.setText(PlaybackTime.format(current, shown.getTotalDuration().toSeconds()));
        if (!seek.isValueChanging()) {
            followingPlayback = true;
            seek.setValue(current);
            followingPlayback = false;
        }
    }

    /**
     * Plain words for JavaFX's error types; the technical message goes to the log. MEDIA_INVALID is also what
     * Windows gives when a codec is missing: on this project's machine every AAC track failed that way.
     */
    static String describe(MediaException error) {
        return switch (error.getType()) {
            case MEDIA_UNSUPPORTED, MEDIA_UNSPECIFIED -> "its format isn't supported";
            case MEDIA_CORRUPTED -> "the file looks damaged";
            case MEDIA_INACCESSIBLE, MEDIA_UNAVAILABLE -> "the file couldn't be read";
            default -> "it couldn't be decoded (a codec may be missing on this computer)";
        };
    }

    /** Space or the button. At the end, plays again from the start. */
    void togglePlay() {
        if (player == null) {
            return;
        }
        if (player.getStatus() == MediaPlayer.Status.PLAYING) {
            player.pause();
        } else {
            Duration total = player.getTotalDuration();
            if (total != null && player.getCurrentTime().greaterThanOrEqualTo(total.subtract(Duration.millis(100)))) {
                player.seek(Duration.ZERO);
            }
            player.play();
        }
    }

    /** Stops and disposes the player (frees its native decoder). Safe to call any time. */
    void stop() {
        generation++;
        if (player != null) {
            player.volumeProperty().unbind();
            player.muteProperty().unbind();
            player.dispose();
            player = null;
        }
        view.setMediaPlayer(null);
        followingPlayback = true;
        seek.setValue(0);
        followingPlayback = false;
        time.setText(PlaybackTime.format(0, 0));
        playPause.setText("❚❚");
    }

    /** The player now showing, or null. For tests and tools. */
    MediaPlayer player() {
        return player;
    }
}
