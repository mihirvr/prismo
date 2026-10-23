package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.Menus;
import io.github.mihirvr.prismo.ui.SubtitleBackground;
import io.github.mihirvr.prismo.ui.TimelineItem;
import io.github.mihirvr.prismo.ui.WatchProgress;
import io.github.mihirvr.prismo.video.SubtitleSources;
import io.github.mihirvr.prismo.video.Subtitles;
import io.github.mihirvr.prismo.video.Track;
import io.github.mihirvr.prismo.video.TrackChoice;
import io.github.mihirvr.prismo.video.VideoNatives;
import io.github.mihirvr.prismo.video.VideoPlayer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextLineInfo;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.util.Duration;

/**
 * Plays one video with {@link VideoPlayer} (FFmpeg): the picture, the controls (previous / next item, play /
 * pause, skip ±10 s, the seek bar and time, subtitles, sound track, speed, loop, volume / mute), the subtitle line and short notes
 * ("Speed 1.5×").
 *
 * <p>One player at a time. It holds native decoders, a sound device and two threads, so {@link #stop} closes
 * it (the viewer calls it whenever it moves to another item or closes). The player opens the file on its own
 * thread; anything it reports for an older video (a generation counter) is ignored.
 *
 * <p>Pictures are drawn on every JavaFX pulse (an {@link AnimationTimer}): the newest one the player has ready
 * is copied into a {@link WritableImage} and handed back. The same pulse moves the seek bar and the time, shows
 * the subtitle line for that moment and saves the watch progress every few seconds.
 *
 * <p><b>Watch progress</b> (PROJECT.md): a video opens where it was left ({@link WatchProgress#resumeAt}), with
 * a short note; the position is saved every {@link #SAVE_EVERY_MICROS} while playing, on pause, at the end and
 * when the video is closed. Volume, mute and speed are kept across videos (and, by the app, across runs).
 *
 * <p><b>Remembered tracks</b> (P8): what the user picks (sound track, subtitles, delay) is saved for the video
 * ({@link TrackMemory}) and comes back next time; a video with nothing picked starts with the language picked last
 * ({@link TrackChoice} has the rules). Only the user's own picks are saved, never what the rules turned on.
 *
 * <p><b>The subtitle line</b> (P8): centred, its box only as wide as the text, with the box's look from
 * {@link SubtitleBackground} (a style class; the colours are in the stylesheet).
 */
final class VideoPane extends StackPane {

    /** Within this much of the end, Play starts again from the top. */
    private static final long END_SLACK_MICROS = 100_000;
    private static final double ICON_SIZE = 20;
    /** The speeds offered, slowest first. */
    static final double[] SPEEDS = {0.5, 0.75, 1, 1.25, 1.5, 1.75, 2};
    static final long SKIP_MICROS = 10_000_000;
    /** Left / Right in a video: a shorter skip than J / L. */
    static final long ARROW_SKIP_MICROS = 5_000_000;
    static final long SUBTITLE_DELAY_STEP_MICROS = 100_000;
    private static final double VOLUME_STEP = 0.1;
    private static final long SAVE_EVERY_MICROS = 5_000_000;
    private static final Duration NOTE_TIME = Duration.seconds(1.6);
    /** The seek bar never gets shorter than this; narrower than that, other controls make room. */
    private static final double MIN_SEEK_WIDTH = 60;
    /**
     * The video area widths at which the bar still fits everything, then without the volume slider, without the
     * ±10 s buttons, without loop, without the speed button (see {@link #controlsLeftOut}). Measured in the app at
     * 125% scaling with a sound-track button and an hour-long video's "1:02:03 / 1:30:00", plus 15 px to spare.
     */
    static final double[] ROOM_FOR = {650, 600, 535, 500, 450};
    /** The subtitle line's distance from the bottom: above the controls, or lower while they're hidden. */
    private static final double SUBTITLE_ABOVE_CONTROLS = 84;
    private static final double SUBTITLE_LOW = 36;
    /** The subtitle box is at most this part of the video's width; longer lines wrap. */
    static final double SUBTITLE_MAX_WIDTH = 0.8;

    private final PlaybackMemory memory;
    /** What the user picked in videos (P8); the app sets the real one. */
    private TrackMemory trackMemory = TrackMemory.NONE;
    private final ImageView picture = new ImageView();
    private final Button playPause = new Button();
    /** True while the play / pause button shows "pause" (so its icon is only swapped when that changes). */
    private boolean showingPause;
    private final Button back = new Button();
    private final Button forward = new Button();
    /** The previous / next photo or video in the list (the viewer's, see {@link #setMediaSteps}). */
    private final Button previousMedia = new Button();
    private final Button nextMedia = new Button();
    private final Slider seek = new Slider(0, 1, 0);
    private final Label time = new Label(PlaybackTime.format(0, 0));
    private final MenuButton subtitlesButton = new MenuButton();
    private final MenuButton soundButton = new MenuButton();
    private final MenuButton speedButton = new MenuButton();
    private final ToggleButton loopButton = new ToggleButton();
    private final Button mute = new Button();
    private final Slider volume = new Slider(0, 1, 1);
    private final BooleanProperty muted = new SimpleBooleanProperty(false);
    private final DoubleProperty speed = new SimpleDoubleProperty(1);
    private final HBox controls;
    /** The subtitle line, near the bottom over the picture, centred; its box fits the text. */
    private final Label subtitle = new SubtitleLine();
    /** The box behind the subtitle line (Settings › Video, the Subtitles menu › Background); the app saves it. */
    private final ObjectProperty<SubtitleBackground> subtitleBackground =
            new SimpleObjectProperty<>(SubtitleBackground.DEFAULT);
    /** A short note in the middle near the top ("Speed 1.5×"), gone after {@link #NOTE_TIME}. */
    private final Label note = new Label();
    private final PauseTransition noteTimer = new PauseTransition(NOTE_TIME);
    private final AnimationTimer pulse = new AnimationTimer() {
        @Override
        public void handle(long now) {
            drawNewestPicture();
            showTime();
        }
    };

    private VideoPlayer player;
    private TimelineItem item;
    private VideoPlayer.Info info;
    private WritableImage image;
    /** Clockwise quarter turn the picture is shown at (phones store upright videos sideways plus a tag). */
    private int rotation;
    private boolean pictureShown;
    private Runnable onFirstPicture = () -> { };
    private Runnable onDoubleClick = () -> { };
    /** Told what the player found in the file once it's open, and null when it's closed (the Info panel). */
    private Consumer<VideoPlayer.Info> onInfo = _ -> { };
    /** Bumped on every {@link #play} and {@link #stop}: reports for an older player are dropped. */
    private long generation;
    /** True while the seek bar is being moved to follow playback, so that move isn't taken as a user seek. */
    private boolean followingPlayback;
    /** Where the position was last saved (watch progress). */
    private long savedMicros;

    private List<SubtitleSources.Source> subtitleSources = List.of();
    /** The chosen entry of {@link #subtitleSources}, or -1 for Off. */
    private int subtitleChoice = -1;
    private Subtitles subtitles = Subtitles.NONE;
    /** Positive: subtitles come later than the file says. */
    private long subtitleDelayMicros;
    /** Bumped for each subtitle load, so a slow one that's no longer wanted is dropped. */
    private long subtitleLoads;
    /** False while the bar is too narrow for the sound-track button ({@link #fitControls}). */
    private boolean soundRoom = true;
    /** What the user picked for the open video (loaded when it opens, updated by each pick, saved at once). */
    private TrackChoice.Picked picked = TrackChoice.Picked.NOTHING;

    VideoPane(PlaybackMemory memory) {
        this.memory = memory;
        getStyleClass().add("viewer-video");
        // The picture is sized to this pane (fitPicture); without this its size would count as the pane's minimum,
        // so the viewer could grow with the window but never shrink back (controls pushed off-screen on restore).
        setMinSize(0, 0);
        setPickOnBounds(true); // clicks anywhere on the video (not only on the picture) play / pause
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
        volume.setTooltip(new Tooltip("Volume (Up / Down)"));
        volume.valueProperty().addListener((_, _, value) -> {
            if (player != null) {
                player.setVolume(value.doubleValue());
            }
        });
        mute.graphicProperty().bind(muted.map(m -> (m ? Icon.MUTED : Icon.VOLUME).node(ICON_SIZE)));
        Tooltip muteTip = new Tooltip();
        muteTip.textProperty().bind(muted.map(m -> m ? "Unmute (M)" : "Mute (M)"));
        mute.setTooltip(muteTip);
        mute.setOnAction(_ -> toggleMute());
        muted.addListener((_, _, on) -> {
            if (player != null) {
                player.setMuted(on);
            }
        });
        playPause.setTooltip(new Tooltip("Play / pause (Space)"));
        playPause.setOnAction(_ -> togglePlay());
        showPlaying(true);
        back.setGraphic(Icon.SKIP_BACK.node(ICON_SIZE));
        back.setTooltip(new Tooltip("Back 10 s (J)"));
        back.setOnAction(_ -> skip(-SKIP_MICROS));
        forward.setGraphic(Icon.SKIP_FORWARD.node(ICON_SIZE));
        forward.setTooltip(new Tooltip("Forward 10 s (L)"));
        forward.setOnAction(_ -> skip(SKIP_MICROS));
        previousMedia.setGraphic(Icon.PREVIOUS_MEDIA.node(ICON_SIZE));
        previousMedia.setTooltip(new Tooltip("Previous (Page Up)"));
        nextMedia.setGraphic(Icon.NEXT_MEDIA.node(ICON_SIZE));
        nextMedia.setTooltip(new Tooltip("Next (Page Down)"));

        subtitlesButton.setGraphic(Icon.SUBTITLES.node(ICON_SIZE));
        subtitlesButton.setTooltip(new Tooltip("Subtitles (C)"));
        soundButton.setGraphic(Icon.SOUND_TRACK.node(ICON_SIZE));
        soundButton.setTooltip(new Tooltip("Sound track (A)"));
        speedButton.setTooltip(new Tooltip("Speed ([ slower, ] faster)"));
        speedButton.getStyleClass().add("viewer-speed");
        speed.addListener((_, _, value) -> {
            if (player != null) {
                player.setSpeed(value.doubleValue());
            }
            speedButton.setText(speedText(value.doubleValue()));
        });
        speedButton.setText(speedText(1));
        loopButton.setGraphic(Icon.LOOP.node(ICON_SIZE));
        loopButton.setTooltip(new Tooltip("Loop: play again from the start at the end"));
        for (Control control : new Control[] {previousMedia, playPause, nextMedia, back, forward, mute, subtitlesButton,
                soundButton, loopButton}) {
            control.getStyleClass().add("icon-button");
        }
        for (Control control : new Control[] {previousMedia, playPause, nextMedia, back, forward, seek, mute, volume,
                subtitlesButton, soundButton, speedButton, loopButton}) {
            control.setFocusTraversable(false); // the viewer handles every key itself
        }
        updateMenus();

        controls = new HBox(6, previousMedia, playPause, nextMedia, back, forward, seek, time, subtitlesButton, soundButton, speedButton,
                loopButton, mute, volume);
        controls.getStyleClass().add("viewer-video-controls");
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setMaxHeight(USE_PREF_SIZE);
        StackPane.setAlignment(controls, Pos.BOTTOM_CENTER);
        // A narrow video area (a small window, or the Info panel open): the time and speed keep their whole text,
        // and the least-needed controls make room instead (they stay on their keys and in the right-click menu).
        time.setMinWidth(USE_PREF_SIZE);
        speedButton.setMinWidth(USE_PREF_SIZE); // the stylesheet's fixed width wins over this (.viewer-speed)
        seek.setMinWidth(MIN_SEEK_WIDTH);
        widthProperty().addListener((_, _, width) -> fitControls(width.doubleValue()));

        subtitle.getStyleClass().add("viewer-subtitle");
        subtitle.setWrapText(true);
        subtitle.setTextAlignment(TextAlignment.CENTER);
        subtitle.setAlignment(Pos.CENTER);
        subtitle.setMouseTransparent(true);
        subtitle.setVisible(false);
        // Its widest is a part of the video's width (SubtitleLine): measured again when that changes.
        widthProperty().addListener((_, _, _) -> subtitle.requestLayout());
        subtitleBackground.subscribe(this::showBackground);
        // Readable at any window size: about a 26th of the height, within 15–42 px.
        subtitle.styleProperty().bind(heightProperty().map(h -> "-fx-font-size: %.0fpx;".formatted(
                Math.clamp(h.doubleValue() / 26, 15, 42))));
        StackPane.setAlignment(subtitle, Pos.BOTTOM_CENTER);
        StackPane.setMargin(subtitle, new Insets(0, 0, SUBTITLE_ABOVE_CONTROLS, 0));
        note.getStyleClass().add("viewer-note");
        note.setMouseTransparent(true);
        note.setVisible(false);
        StackPane.setAlignment(note, Pos.TOP_CENTER);
        StackPane.setMargin(note, new Insets(76, 0, 0, 0));
        noteTimer.setOnFinished(_ -> note.setVisible(false));

        addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            boolean onVideo = event.getTarget() == this || event.getTarget() == picture;
            if (onVideo && event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()) {
                togglePlay(); // a double click toggles twice (back as it was) and goes full screen
                if (event.getClickCount() == 2) {
                    onDoubleClick.run();
                }
            }
        });
        getChildren().addAll(picture, subtitle, note, controls);
    }

    /**
     * How many of the optional controls to leave out at this width: 0 = all shown, 1 = no volume slider,
     * 2 = also no ±10 s buttons, 3 = also no loop button, 4 = also no speed button, 5 = also no sound-track button.
     * Each stays on its key and in the right-click menu.
     */
    static int controlsLeftOut(double width) {
        for (int i = 0; i < ROOM_FOR.length; i++) {
            if (width >= ROOM_FOR[i]) {
                return i;
            }
        }
        return ROOM_FOR.length;
    }

    private void fitControls(double width) {
        int leftOut = controlsLeftOut(width);
        show(volume, leftOut < 1);
        show(back, leftOut < 2);
        show(forward, leftOut < 2);
        show(loopButton, leftOut < 3);
        show(speedButton, leftOut < 4);
        soundRoom = leftOut < 5;
        showSoundButton();
    }

    /** The sound-track button: only for a video with more than one track, and only when there is room. */
    private void showSoundButton() {
        show(soundButton, soundRoom && info != null && info.audioTracks().size() > 1);
    }

    private static void show(Control control, boolean shown) {
        control.setVisible(shown);
        control.setManaged(shown);
    }

    /** What the previous / next buttons do (the viewer moves to the previous / next item). */
    void setMediaSteps(Runnable previous, Runnable next) {
        previousMedia.setOnAction(_ -> previous.run());
        nextMedia.setOnAction(_ -> next.run());
    }

    /** Greys out the previous / next button at the start / end of the list. */
    void setNeighbours(boolean hasPrevious, boolean hasNext) {
        previousMedia.setDisable(!hasPrevious);
        nextMedia.setDisable(!hasNext);
    }

    /** Called with what the player found in the file once it opens, and with null when it closes. */
    void setOnInfo(Consumer<VideoPlayer.Info> onInfo) {
        this.onInfo = onInfo;
    }

    /** What the player found in the file, or null before it opened (or if it couldn't). */
    VideoPlayer.Info info() {
        return info;
    }

    /** What a double click on the video does (the viewer: full screen). */
    void setOnDoubleClick(Runnable onDoubleClick) {
        this.onDoubleClick = onDoubleClick;
    }

    /**
     * Opens {@code video} and starts playing, where it was left last time. {@code onError} gets a short reason on
     * the UI thread if it can't be played (missing, damaged, unknown format); {@code onFirstPicture} runs when the
     * first picture is on screen.
     */
    void play(TimelineItem video, Consumer<String> onError, Runnable onFirstPicture) {
        stop();
        long mine = generation;
        item = video;
        this.onFirstPicture = onFirstPicture;
        // What was picked for this video last time (P8); the sound track is chosen by the player before any sound.
        picked = trackMemory.load(video.ref().id());
        subtitleDelayMicros = picked.subtitleDelayMicros();
        TrackChoice.Picked pickedBefore = picked;
        String audioLanguage = trackMemory.audioLanguage();
        double resume = WatchProgress.resumeAt(memory.load(video.ref().id()), video.durationSeconds());
        savedMicros = Math.round(resume * 1_000_000);
        // Decode at most at the screen's real pixel size: a 4K video on a 1080p screen needs a quarter of the memory.
        Screen screen = Screen.getPrimary();
        Rectangle2D bounds = screen.getBounds();
        int maxWidth = (int) Math.ceil(bounds.getWidth() * screen.getOutputScaleX());
        int maxHeight = (int) Math.ceil(bounds.getHeight() * screen.getOutputScaleY());
        player = VideoPlayer.open(video.ref().path(), maxWidth, maxHeight, savedMicros, new VideoPlayer.Listener() {
            @Override
            public int chooseAudio(List<Track> tracks, int fileDefault) { // the player's thread: final values only
                return TrackChoice.audioStream(tracks, fileDefault, pickedBefore.audioStream(), audioLanguage);
            }

            @Override
            public void opened(VideoPlayer.Info opened) {
                Platform.runLater(() -> {
                    if (generation == mine) {
                        info = opened;
                        seek.setMax(Math.max(0.001, opened.durationMicros() / 1e6));
                        rotation = opened.rotation();
                        picture.setRotate(rotation);
                        fitPicture();
                        findSubtitles(mine);
                        updateMenus();
                        onInfo.accept(opened);
                    }
                });
            }

            @Override
            public void ended() {
                Platform.runLater(() -> {
                    if (generation == mine) {
                        saveProgress(); // at the end: counts as watched
                        if (loopButton.isSelected()) {
                            player.seek(0);
                            player.play();
                        }
                    }
                });
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
        player.setSpeed(speed.get());
        if (resume > 0) {
            showNote("Resumed at " + PlaybackTime.clock(resume) + "  ·  Home: from the start");
        }
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
        long position = player.position();
        double current = position / 1e6;
        time.setText(PlaybackTime.format(current, player.durationMicros() / 1e6));
        if (!seek.isValueChanging()) {
            followingPlayback = true;
            seek.setValue(current);
            followingPlayback = false;
        }
        showPlaying(player.isPlaying());
        String line = subtitles.at(position - subtitleDelayMicros);
        if (!line.equals(subtitle.getText())) {
            subtitle.setText(line);
        }
        subtitle.setVisible(!line.isEmpty());
        if (player.isPlaying() && Math.abs(position - savedMicros) >= SAVE_EVERY_MICROS) {
            saveProgress();
        }
    }

    /** The button shows what a click does: pause while playing, play while paused. */
    private void showPlaying(boolean playing) {
        if (playing != showingPause || playPause.getGraphic() == null) {
            showingPause = playing;
            playPause.setGraphic((playing ? Icon.PAUSE : Icon.PLAY).node(ICON_SIZE));
        }
    }

    // --- Controls (buttons, keys and the right-click menu all come here) ---

    /** Space or the button. At the end, plays again from the start. */
    void togglePlay() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            saveProgress();
        } else {
            long duration = player.durationMicros();
            if (duration > 0 && player.position() >= duration - END_SLACK_MICROS) {
                player.seek(0);
            }
            player.play();
        }
    }

    /** J / L and the ±10 s buttons; Left / Right ±5 s. */
    void skip(long micros) {
        if (player != null) {
            player.seek(player.position() + micros);
            showNote((micros < 0 ? "Back " : "Forward ") + Math.abs(micros) / 1_000_000 + " s");
        }
    }

    /** Home: back to the start. */
    void restart() {
        if (player != null) {
            player.seek(0);
            showNote("From the start");
        }
    }

    /** , and . : one picture back or on (pauses). */
    void step(int pictures) {
        if (player != null) {
            player.step(pictures);
        }
    }

    private void changeVolume(double delta) {
        volume.setValue(Math.clamp(volume.getValue() + delta, 0, 1));
        if (delta > 0) {
            muted.set(false);
        }
        showNote("Volume " + Math.round(volume.getValue() * 100) + "%");
    }

    void volumeUp() {
        changeVolume(VOLUME_STEP);
    }

    void volumeDown() {
        changeVolume(-VOLUME_STEP);
    }

    void toggleMute() {
        muted.set(!muted.get());
        showNote(muted.get() ? "Muted" : "Volume " + Math.round(volume.getValue() * 100) + "%");
    }

    /** [ and ] : the next speed down or up the list. */
    void changeSpeed(int steps) {
        setSpeed(nextSpeed(speed.get(), steps));
    }

    /** The speed {@code steps} along {@link #SPEEDS} from the one nearest {@code current}; stops at the ends. */
    static double nextSpeed(double current, int steps) {
        int at = 0;
        for (int i = 0; i < SPEEDS.length; i++) {
            if (Math.abs(SPEEDS[i] - current) < Math.abs(SPEEDS[at] - current)) {
                at = i;
            }
        }
        return SPEEDS[Math.clamp(at + steps, 0, SPEEDS.length - 1)];
    }

    private void setSpeed(double newSpeed) {
        speed.set(newSpeed);
        showNote("Speed " + speedText(newSpeed));
        updateMenus();
    }

    void toggleLoop() {
        loopButton.setSelected(!loopButton.isSelected());
        showNote(loopButton.isSelected() ? "Loop on" : "Loop off");
    }

    /** C: the next subtitles that can be shown, then Off, then round again. */
    void nextSubtitles() {
        for (int i = subtitleChoice + 1; i < subtitleSources.size(); i++) {
            if (subtitleSources.get(i).supported()) {
                chooseSubtitles(i, true);
                return;
            }
        }
        if (subtitleSources.isEmpty()) {
            showNote("No subtitles for this video");
        } else {
            chooseSubtitles(-1, true);
        }
    }

    /** "Choose subtitle file…": an .srt from anywhere (the video's folder first), added to the choices and shown. */
    private void chooseSubtitleFile() {
        if (item == null || getScene() == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose a subtitle file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SubRip subtitles (*.srt)", "*.srt"));
        Path folder = item.ref().path().toAbsolutePath().getParent();
        if (folder != null) {
            chooser.setInitialDirectory(folder.toFile()); // the video is open, so its folder is there
        }
        File picked = chooser.showOpenDialog(getScene().getWindow());
        if (picked != null) {
            useSubtitleFile(picked.toPath());
        }
    }

    /** Adds {@code file} to the subtitle choices (once) and turns it on (the user's pick: remembered). */
    void useSubtitleFile(Path file) {
        subtitleSources = SubtitleSources.withChosenFile(subtitleSources, file);
        Path wanted = file.toAbsolutePath().normalize();
        for (int i = 0; i < subtitleSources.size(); i++) {
            if (wanted.equals(subtitleSources.get(i).file())) {
                chooseSubtitles(i, true);
                return;
            }
        }
    }

    /** Z / X: subtitles earlier or later by {@link #SUBTITLE_DELAY_STEP_MICROS}; 0 = back to what the file says. */
    void shiftSubtitles(long micros) {
        subtitleDelayMicros = micros == 0 ? 0 : subtitleDelayMicros + micros;
        showNote("Subtitle delay " + delayText(subtitleDelayMicros));
        remember(picked.withDelay(subtitleDelayMicros));
        updateMenus();
    }

    /** A: the next sound track. */
    void nextSoundTrack() {
        if (info == null || player == null || info.audioTracks().size() < 2) {
            showNote("This video has one sound track");
            return;
        }
        List<Track> tracks = info.audioTracks();
        int at = 0;
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).stream() == player.audioStream()) {
                at = i;
            }
        }
        chooseSoundTrack((at + 1) % tracks.size());
    }

    /** The user picked sound track {@code index} (the menu or A): played, and remembered for this video. */
    private void chooseSoundTrack(int index) {
        Track track = info.audioTracks().get(index);
        player.selectAudio(track.stream());
        showNote("Sound: " + track.label(index + 1));
        remember(picked.withAudio(track.stream()));
        trackMemory.setAudioLanguage(TrackChoice.audioLanguageAfter(track, trackMemory.audioLanguage()));
        updateMenus();
    }

    // --- Subtitles ---

    /**
     * Lists the subtitles for the video just opened and turns on the ones {@link TrackChoice#subtitles} picks: what
     * was picked for this video, else the language picked last, else a file next to the video (like other players).
     * Listing the folder and checking a remembered file are disk work, so they run on a virtual thread.
     */
    private void findSubtitles(long mine) {
        Path video = item.ref().path();
        List<Track> tracks = info.subtitleTracks();
        TrackChoice.SubtitlePick remembered = picked.subtitles();
        String language = trackMemory.subtitleLanguage();
        Thread.ofVirtual().name("prismo-subtitle-list").start(() -> {
            TrackChoice.SubtitleStart start;
            try {
                start = TrackChoice.subtitles(video, SubtitleSources.forVideo(video, tracks), remembered, language,
                        Files::isRegularFile);
            } catch (RuntimeException e) {
                start = new TrackChoice.SubtitleStart(List.of(), -1);
            }
            TrackChoice.SubtitleStart found = start;
            Platform.runLater(() -> {
                if (generation == mine) {
                    subtitleSources = found.sources();
                    if (found.choice() >= 0) {
                        chooseSubtitles(found.choice(), false);
                    } else {
                        updateMenus();
                    }
                }
            });
        });
    }

    /**
     * Turns on subtitles {@code index} of {@link #subtitleSources}, or Off (-1). {@code byUser}: picked in the menu,
     * with C or with "Choose subtitle file…", so it is remembered for this video, and its language for the next ones.
     */
    private void chooseSubtitles(int index, boolean byUser) {
        subtitleChoice = index;
        subtitles = Subtitles.NONE;
        long load = ++subtitleLoads;
        if (byUser && item != null) {
            SubtitleSources.Source source = index < 0 ? null : subtitleSources.get(index);
            remember(picked.withSubtitles(source == null ? TrackChoice.SubtitlePick.SUBTITLES_OFF
                    : source.file() != null ? TrackChoice.SubtitlePick.file(item.ref().path(), source.file())
                    : TrackChoice.SubtitlePick.track(source.stream())));
            trackMemory.setSubtitleLanguage(TrackChoice.subtitleLanguageAfter(source, trackMemory.subtitleLanguage()));
        }
        updateMenus();
        if (index < 0) {
            showNote("Subtitles off");
            return;
        }
        SubtitleSources.Source source = subtitleSources.get(index);
        if (source.file() == null) {
            showNote("Loading subtitles…"); // ffmpeg reads them out of the video: a moment for a long one
        }
        Path video = item.ref().path();
        Thread.ofVirtual().name("prismo-subtitles").start(() -> {
            try {
                Subtitles loaded = SubtitleSources.load(source, video, VideoNatives::ffmpegExecutable);
                Platform.runLater(() -> {
                    if (load == subtitleLoads) {
                        subtitles = loaded;
                        showNote(loaded.isEmpty() ? "These subtitles are empty" : "Subtitles: " + source.label());
                    }
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    if (load == subtitleLoads) {
                        subtitleChoice = -1;
                        updateMenus();
                        showNote("Can't show these subtitles: " + e.getMessage());
                    }
                });
            }
        });
    }

    // --- Menus: the buttons' menus and the viewer's right-click menu share these ---

    /** Rebuilds the subtitle, sound and speed menus for the current state. */
    private void updateMenus() {
        subtitlesButton.getItems().setAll(subtitleItems());
        soundButton.getItems().setAll(soundItems());
        showSoundButton();
        speedButton.getItems().setAll(speedItems());
    }

    private List<MenuItem> subtitleItems() {
        List<MenuItem> items = new ArrayList<>();
        ToggleGroup group = new ToggleGroup();
        RadioMenuItem off = new RadioMenuItem("Off");
        off.setToggleGroup(group);
        off.setSelected(subtitleChoice < 0);
        off.setOnAction(_ -> chooseSubtitles(-1, true));
        items.add(off);
        for (int i = 0; i < subtitleSources.size(); i++) {
            int index = i;
            SubtitleSources.Source source = subtitleSources.get(i);
            RadioMenuItem choice = new RadioMenuItem(source.label());
            choice.setToggleGroup(group);
            choice.setSelected(i == subtitleChoice);
            choice.setDisable(!source.supported());
            choice.setOnAction(_ -> chooseSubtitles(index, true));
            items.add(choice);
        }
        if (subtitleSources.isEmpty()) {
            MenuItem none = new MenuItem("None found (an .srt file named like the video works)");
            none.setDisable(true);
            items.add(none);
        }
        items.add(Menus.item("Choose subtitle file…", null, null, this::chooseSubtitleFile));
        items.add(Menus.item("Next subtitles", null, new KeyCodeCombination(KeyCode.C), this::nextSubtitles));
        items.add(new SeparatorMenuItem());
        MenuItem delay = new MenuItem("Delay: " + delayText(subtitleDelayMicros));
        delay.setDisable(true);
        items.add(delay);
        items.add(Menus.item("Earlier by 0.1 s", null, new KeyCodeCombination(KeyCode.Z),
                () -> shiftSubtitles(-SUBTITLE_DELAY_STEP_MICROS)));
        items.add(Menus.item("Later by 0.1 s", null, new KeyCodeCombination(KeyCode.X),
                () -> shiftSubtitles(SUBTITLE_DELAY_STEP_MICROS)));
        MenuItem reset = Menus.item("Reset delay", null, null, () -> shiftSubtitles(0));
        reset.setDisable(subtitleDelayMicros == 0);
        items.add(reset);
        items.add(new SeparatorMenuItem());
        items.add(backgroundMenu());
        return items;
    }

    /** Subtitles › Background: the box behind the line, the same choice as Settings › Video. */
    private Menu backgroundMenu() {
        Menu menu = new Menu("Background");
        ToggleGroup group = new ToggleGroup();
        for (SubtitleBackground choice : SubtitleBackground.values()) {
            RadioMenuItem entry = new RadioMenuItem(choice == SubtitleBackground.NONE ? "None (outlined text)"
                    : choice.label());
            entry.setToggleGroup(group);
            entry.setSelected(choice == subtitleBackground.get());
            entry.setOnAction(_ -> {
                subtitleBackground.set(choice);
                showNote("Subtitle background " + choice.label());
            });
            menu.getItems().add(entry);
        }
        return menu;
    }

    private List<MenuItem> soundItems() {
        List<MenuItem> items = new ArrayList<>();
        if (info == null || player == null) {
            return items;
        }
        ToggleGroup group = new ToggleGroup();
        List<Track> tracks = info.audioTracks();
        for (int i = 0; i < tracks.size(); i++) {
            int index = i;
            RadioMenuItem choice = new RadioMenuItem(tracks.get(i).label(i + 1));
            choice.setToggleGroup(group);
            choice.setSelected(tracks.get(i).stream() == player.audioStream());
            choice.setOnAction(_ -> chooseSoundTrack(index));
            items.add(choice);
        }
        if (tracks.size() > 1) {
            items.add(new SeparatorMenuItem());
            items.add(Menus.item("Next sound track", null, new KeyCodeCombination(KeyCode.A), this::nextSoundTrack));
        }
        return items;
    }

    private List<MenuItem> speedItems() {
        List<MenuItem> items = new ArrayList<>();
        ToggleGroup group = new ToggleGroup();
        for (double value : SPEEDS) {
            RadioMenuItem choice = new RadioMenuItem(value == 1 ? "Normal (1×)" : speedText(value));
            choice.setToggleGroup(group);
            choice.setSelected(value == speed.get());
            choice.setOnAction(_ -> setSpeed(value));
            items.add(choice);
        }
        items.add(new SeparatorMenuItem());
        items.add(Menus.item("Slower", null, new KeyCodeCombination(KeyCode.OPEN_BRACKET), () -> changeSpeed(-1)));
        items.add(Menus.item("Faster", null, new KeyCodeCombination(KeyCode.CLOSE_BRACKET), () -> changeSpeed(1)));
        return items;
    }

    /** The video's part of the viewer's right-click menu: playback, then subtitles / sound / speed submenus. */
    List<MenuItem> menuItems() {
        List<MenuItem> items = new ArrayList<>();
        boolean playing = player != null && player.isPlaying();
        items.add(Menus.item(playing ? "Pause" : "Play", playing ? Icon.PAUSE : Icon.PLAY,
                new KeyCodeCombination(KeyCode.SPACE), this::togglePlay));
        items.add(Menus.item("Back 10 s", Icon.SKIP_BACK, new KeyCodeCombination(KeyCode.J), () -> skip(-SKIP_MICROS)));
        items.add(Menus.item("Forward 10 s", Icon.SKIP_FORWARD, new KeyCodeCombination(KeyCode.L),
                () -> skip(SKIP_MICROS)));
        items.add(Menus.item("Back 5 s", null, new KeyCodeCombination(KeyCode.LEFT), () -> skip(-ARROW_SKIP_MICROS)));
        items.add(Menus.item("Forward 5 s", null, new KeyCodeCombination(KeyCode.RIGHT),
                () -> skip(ARROW_SKIP_MICROS)));
        items.add(Menus.item("Previous picture", null, new KeyCodeCombination(KeyCode.COMMA), () -> step(-1)));
        items.add(Menus.item("Next picture", null, new KeyCodeCombination(KeyCode.PERIOD), () -> step(1)));
        items.add(Menus.item("From the start", null, new KeyCodeCombination(KeyCode.HOME), this::restart));
        items.add(new SeparatorMenuItem());
        Menu subtitleMenu = new Menu("Subtitles", Icon.SUBTITLES.node(Menus.ICON_SIZE));
        subtitleMenu.getItems().setAll(subtitleItems());
        items.add(subtitleMenu);
        Menu soundMenu = new Menu("Sound track", Icon.SOUND_TRACK.node(Menus.ICON_SIZE));
        List<MenuItem> sound = soundItems();
        soundMenu.getItems().setAll(sound);
        soundMenu.setDisable(sound.size() < 2);
        items.add(soundMenu);
        Menu speedMenu = new Menu("Speed: " + speedText(speed.get()));
        speedMenu.getItems().setAll(speedItems());
        items.add(speedMenu);
        CheckMenuItem loop = new CheckMenuItem("Loop", Icon.LOOP.node(Menus.ICON_SIZE));
        loop.setSelected(loopButton.isSelected());
        loop.setOnAction(_ -> toggleLoop());
        items.add(loop);
        items.add(Menus.item(muted.get() ? "Unmute" : "Mute", muted.get() ? Icon.VOLUME : Icon.MUTED,
                new KeyCodeCombination(KeyCode.M), this::toggleMute));
        items.add(Menus.item("Volume up", null, new KeyCodeCombination(KeyCode.UP), this::volumeUp));
        items.add(Menus.item("Volume down", null, new KeyCodeCombination(KeyCode.DOWN), this::volumeDown));
        return items;
    }

    // --- Notes, saving, stopping ---

    /** The user picked something in this video (P8): kept for it and saved at once (through the DB writer). */
    private void remember(TrackChoice.Picked now) {
        picked = now;
        if (item != null) {
            trackMemory.save(item.ref().id(), now);
        }
    }

    /** Puts {@code background}'s style class on the subtitle line (only one at a time). */
    private void showBackground(SubtitleBackground background) {
        for (SubtitleBackground each : SubtitleBackground.values()) {
            subtitle.getStyleClass().remove(each.styleClass());
        }
        subtitle.getStyleClass().add(background.styleClass());
        updateMenus();
    }

    void showNote(String text) {
        note.setText(text);
        note.setVisible(true);
        noteTimer.playFromStart();
    }

    /** Saves where the video is (watch progress), if it opened. */
    private void saveProgress() {
        if (player != null && item != null && player.durationMicros() > 0) {
            long position = player.position();
            savedMicros = position;
            memory.save(item.ref().id(), position / 1e6, player.durationMicros() / 1e6);
        }
    }

    /** Saves the watch progress, then stops and closes the player (frees its decoders and the sound device). */
    void stop() {
        saveProgress();
        generation++;
        pulse.stop();
        if (player != null) {
            player.close();
            player = null;
        }
        item = null;
        if (info != null) {
            info = null;
            onInfo.accept(null);
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
        showPlaying(true);
        subtitleSources = List.of();
        subtitleChoice = -1;
        subtitles = Subtitles.NONE;
        subtitleDelayMicros = 0;
        picked = TrackChoice.Picked.NOTHING;
        subtitleLoads++;
        subtitle.setText("");
        subtitle.setVisible(false);
        note.setVisible(false);
        updateMenus();
    }

    /** "1×", "1.25×", "0.5×". */
    static String speedText(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
        return text + "×";
    }

    /** "+0.3 s", "-1.2 s", "0.0 s". */
    static String delayText(long micros) {
        return micros == 0 ? "0.0 s" : String.format(Locale.ROOT, "%+.1f s", micros / 1e6);
    }

    // --- For the viewer, tests and tools ---

    /** Where the user's picks are kept (the app's; {@link TrackMemory#NONE} until set). */
    void setTrackMemory(TrackMemory trackMemory) {
        this.trackMemory = trackMemory;
    }

    /** The subtitle box's look; the app saves it and Settings › Video changes it too. */
    ObjectProperty<SubtitleBackground> subtitleBackgroundProperty() {
        return subtitleBackground;
    }

    /** The subtitle choices for the open video and the one turned on (-1 = Off). For tests and tools. */
    List<SubtitleSources.Source> subtitleSources() {
        return subtitleSources;
    }

    int subtitleChoice() {
        return subtitleChoice;
    }

    long subtitleDelayMicros() {
        return subtitleDelayMicros;
    }

    /** The control bar at the bottom (the viewer hides it with the rest in full screen). */
    HBox controls() {
        return controls;
    }

    /** Full screen with the controls faded out (the viewer does that): the subtitle line moves down into their place. */
    void setControlsHidden(boolean hidden) {
        StackPane.setMargin(subtitle, new Insets(0, 0, hidden ? SUBTITLE_LOW : SUBTITLE_ABOVE_CONTROLS, 0));
    }

    /** True while one of the controls' menus is open (the viewer then keeps the controls on screen). */
    boolean menuShowing() {
        return subtitlesButton.isShowing() || soundButton.isShowing() || speedButton.isShowing();
    }

    VideoPlayer player() {
        return player;
    }

    ImageView picture() {
        return picture;
    }

    Button previousMediaButton() {
        return previousMedia;
    }

    Button nextMediaButton() {
        return nextMedia;
    }

    Slider seekBar() {
        return seek;
    }

    BooleanProperty mutedProperty() {
        return muted;
    }

    DoubleProperty volumeProperty() {
        return volume.valueProperty();
    }

    DoubleProperty speedProperty() {
        return speed;
    }

    Label subtitleLine() {
        return subtitle;
    }

    Label noteLabel() {
        return note;
    }

    /** True while the play / pause button shows "pause". */
    boolean showsPause() {
        return showingPause;
    }

    /**
     * The subtitle line (P8): as wide as its text, so the box behind it shrinks to fit, but at most
     * {@link #SUBTITLE_MAX_WIDTH} of the video's width. A longer line wraps there, and the box is as wide as the widest
     * wrapped line (measured with a {@link Text} wrapped at the same width, line by line: JavaFX wraps greedily, so
     * wrapping again at that widest line's width gives the same lines). A Label's widest size is its preferred one, so
     * the stack pane never stretches it; centred by its alignment in the pane.
     */
    private final class SubtitleLine extends Label {
        private final Text measure = new Text();

        @Override
        protected double computePrefWidth(double height) {
            double whole = super.computePrefWidth(height);
            double limit = Math.floor(VideoPane.this.getWidth() * SUBTITLE_MAX_WIDTH);
            if (limit <= 0 || whole <= limit) {
                return whole;
            }
            Insets insets = getInsets();
            double sides = insets.getLeft() + insets.getRight();
            measure.setFont(getFont());
            measure.setLineSpacing(getLineSpacing());
            measure.setText(getText());
            measure.setWrappingWidth(Math.max(1, limit - sides));
            // The text's own bounds are the wrapping width; each line's bounds (JavaFX 24+ LayoutInfo) are its own.
            double widest = 0;
            for (TextLineInfo line : measure.getLayoutInfo().getTextLines(false)) {
                widest = Math.max(widest, line.bounds().getWidth());
            }
            return Math.min(limit, Math.ceil(widest) + sides + 1);
        }
    }
}
