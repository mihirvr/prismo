package io.github.mihirvr.prismo.settings;

import io.github.mihirvr.prismo.collections.HiddenMode;
import io.github.mihirvr.prismo.index.IgnoreRules;
import io.github.mihirvr.prismo.platform.OsHiddenMark;
import io.github.mihirvr.prismo.thumbs.ThumbnailService;
import io.github.mihirvr.prismo.ui.Appearance;
import io.github.mihirvr.prismo.ui.DialogPlacement;
import io.github.mihirvr.prismo.ui.Grouping;
import io.github.mihirvr.prismo.ui.Icon;
import io.github.mihirvr.prismo.ui.MediaDetails;
import io.github.mihirvr.prismo.ui.SubtitleBackground;
import io.github.mihirvr.prismo.ui.Theme;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The Settings window (PROJECT.md "Settings"): a list of pages on the left, the chosen page on the right.
 * <ul>
 *   <li><b>Appearance:</b> four cards, Light, Dark, AMOLED black and Follow system, each with a small picture.</li>
 *   <li><b>Hidden items</b> (Windows only): "Show files hidden in File Explorer" (off by default) and whether
 *       Prismo's Hide also marks the file ({@link HiddenMode}).</li>
 *   <li><b>Ignored folders:</b> folders the library skips ({@link IgnoreRules}).</li>
 *   <li><b>Timeline:</b> how date-sorted grids are grouped ({@link Grouping}).</li>
 *   <li><b>Video:</b> the box behind subtitles ({@link SubtitleBackground}), with a small sample (P8).</li>
 *   <li><b>Performance:</b> how many cores make thumbnails; the thumbnails' size on disk and Clear.</li>
 *   <li><b>Keyboard shortcuts:</b> what every key does ({@link Shortcuts}), a reference that scrolls inside the
 *       page, so it doesn't make the window taller than the other pages.</li>
 *   <li><b>About:</b> version and the web page ({@link AppInfo}).</li>
 * </ul>
 * A change applies at once and is saved by the app (through the properties in {@link Options}); there is nothing
 * to confirm, so the only button is Close. Every page is in the window all the time (one shown, the others take
 * no room), and the window is as tall as the tallest page, so it doesn't jump when the page changes.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class SettingsDialog {

    /**
     * What the pages change. The app saves each property when it changes and acts on it.
     *
     * @param hiddenMode       Hidden items › what Prismo's Hide does
     * @param osHiddenShown    Hidden items › "Show files hidden in File Explorer"; the app reloads the views
     * @param ignoredFolders   Ignored folders; the app re-indexes
     * @param grouping         Timeline › date headers
     * @param subtitleBackground Video › the box behind subtitles (the video's Subtitles menu changes it too)
     * @param thumbnailThreads Performance › threads making thumbnails
     * @param thumbnailDisk    Performance › the thumbnails on disk
     * @param about            About › version and web page
     * @param openWebsite      opens the web page in the browser
     */
    public record Options(ObjectProperty<HiddenMode> hiddenMode, BooleanProperty osHiddenShown,
            ObjectProperty<IgnoreRules> ignoredFolders, ObjectProperty<Grouping> grouping,
            ObjectProperty<SubtitleBackground> subtitleBackground, IntegerProperty thumbnailThreads,
            ThumbnailDisk thumbnailDisk, AppInfo about, Consumer<URI> openWebsite) {
    }

    /** The thumbnails on disk, worked out off the UI thread (the futures complete on any thread). */
    public interface ThumbnailDisk {
        CompletableFuture<Long> size();

        /** Deletes them; completes with the bytes freed. */
        CompletableFuture<Long> clear();
    }

    /** The pages, in the order of the list on the left. */
    enum Page {
        APPEARANCE("Appearance", Icon.IMAGE), HIDDEN("Hidden items", Icon.EYE_OFF),
        IGNORED("Ignored folders", Icon.FOLDER), TIMELINE("Timeline", Icon.CLOCK), VIDEO("Video", Icon.SUBTITLES),
        PERFORMANCE("Performance", Icon.SETTINGS), SHORTCUTS("Keyboard shortcuts", Icon.KEYBOARD),
        ABOUT("About", Icon.INFO);

        final String label;
        final Icon icon;

        Page(String label, Icon icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    /** The size of a look's picture on its card. */
    private static final double PREVIEW_WIDTH = 112;
    private static final double PREVIEW_HEIGHT = 72;
    /** A first guess at {@link #contentWidth}, until the cards are measured with their CSS in {@link #show}. */
    private static final double CONTENT_WIDTH = 600;
    private static final double LOGO_WIDTH = 150;

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final Window owner;
    private final Options options;
    private final ScrollPane scroll;
    private final HBox cards = new HBox(12);
    private final StackPane pages = new StackPane();
    private final Map<Page, VBox> pageBoxes = new EnumMap<>(Page.class);
    /**
     * The width of everything on a page: the four look cards in a row. Every wrapping text is given this width,
     * because wrapping text with no width to wrap at is measured one word per line, which once made the window
     * taller than the screen.
     */
    private final DoubleProperty contentWidth = new SimpleDoubleProperty(CONTENT_WIDTH);

    private final Stage stage = new Stage();
    private final ToggleGroup pageList = new ToggleGroup();
    private final ToggleGroup themes = new ToggleGroup();
    private final ToggleGroup hiddenModes = new ToggleGroup();
    private final ToggleGroup groupings = new ToggleGroup();
    private final ToggleGroup subtitleBackgrounds = new ToggleGroup();
    private final CheckBox showOsHidden = new CheckBox("Show files hidden in File Explorer");
    private final VBox ignoredList = new VBox(6);
    private final Label diskSize = new Label("Working it out…");
    private final Button clearThumbnails = new Button("Clear thumbnails");
    /** The list of keys: scrolls inside its page, as tall as the other pages (set in {@link #show}). */
    private final ScrollPane shortcutsScroll = new ScrollPane();
    private final Label shortcutsHeading = heading("Keyboard shortcuts");

    /** @param save stores the look (the app's settings table); called on every change */
    public SettingsDialog(Window owner, Appearance appearance, Consumer<Theme> save, Options options) {
        this.owner = owner;
        this.options = options;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Settings");
        stage.setResizable(false);

        VBox nav = new VBox(4);
        nav.getStyleClass().add("settings-nav");
        for (Page page : Page.values()) {
            if (page == Page.HIDDEN && !OsHiddenMark.SUPPORTED) {
                continue; // the Windows Hidden mark only
            }
            VBox box = switch (page) {
                case APPEARANCE -> appearancePage(appearance, save);
                case HIDDEN -> hiddenSection();
                case IGNORED -> ignoredSection();
                case TIMELINE -> timelinePage();
                case VIDEO -> videoPage();
                case PERFORMANCE -> performancePage();
                case SHORTCUTS -> shortcutsPage();
                case ABOUT -> aboutPage();
            };
            box.prefWidthProperty().bind(contentWidth);
            box.maxWidthProperty().bind(contentWidth);
            box.setVisible(page == Page.APPEARANCE);
            box.setManaged(page == Page.APPEARANCE); // a page not shown takes no room (no scroll bar for it)
            pageBoxes.put(page, box);
            pages.getChildren().add(box);
            StackPane.setAlignment(box, Pos.TOP_LEFT);
            ToggleButton entry = new ToggleButton(page.label, page.icon.node(16));
            entry.getStyleClass().add("settings-nav-item");
            entry.setUserData(page);
            entry.setToggleGroup(pageList);
            entry.setMaxWidth(Double.MAX_VALUE);
            entry.setAlignment(Pos.CENTER_LEFT);
            nav.getChildren().add(entry);
        }
        pageList.selectToggle(pageList.getToggles().getFirst());
        pageList.selectedToggleProperty().addListener((_, old, selected) -> {
            if (selected == null) {
                old.setSelected(true); // one page is always shown
            } else {
                showPage((Page) selected.getUserData());
            }
        });

        // Scrolls only if the screen is too small for the tallest page (DialogPlacement keeps it on screen).
        scroll = new ScrollPane(pages);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("settings-scroll");

        Button close = new Button("Close");
        close.setCancelButton(true);
        close.setDefaultButton(true);
        close.setOnAction(_ -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(spacer, close);
        BorderPane root = new BorderPane(scroll, null, null, buttons, nav);
        BorderPane.setMargin(buttons, new Insets(20, 0, 0, 0));
        BorderPane.setMargin(nav, new Insets(0, 24, 0, 0));
        root.setPadding(new Insets(20));
        root.getStyleClass().add("settings-dialog");
        Scene scene = new Scene(root);
        Appearance.matchOwner(scene, owner);
        stage.setScene(scene);
        stage.getProperties().put(SettingsDialog.class, this); // lets tools find the dialog from its window
    }

    /** Shows the window (not blocking), on screen and centred on the main window, keyboard on the chosen look. */
    public void show() {
        stage.show();
        // Exactly as tall as the tallest page (now that the looks' CSS is applied), so it scrolls only if the
        // screen is too small for it; DialogPlacement then keeps the window on that screen.
        contentWidth.set(Math.ceil(cards.prefWidth(-1)));
        scroll.setPrefViewportWidth(contentWidth.get());
        double tallest = 0;
        for (Map.Entry<Page, VBox> page : pageBoxes.entrySet()) {
            if (page.getKey() != Page.SHORTCUTS) {
                tallest = Math.max(tallest, page.getValue().prefHeight(contentWidth.get()));
            }
        }
        // The key list is long: it fills the same height and scrolls inside.
        shortcutsScroll.setPrefViewportHeight(Math.max(120, tallest - shortcutsHeading.prefHeight(-1)
                - pageBoxes.get(Page.SHORTCUTS).getSpacing()));
        scroll.setPrefViewportHeight(Math.ceil(tallest));
        DialogPlacement.fit(stage, owner);
        if (themes.getSelectedToggle() instanceof ToggleButton chosen) {
            chosen.requestFocus();
        }
    }

    private void showPage(Page page) {
        pageBoxes.forEach((each, box) -> {
            box.setVisible(each == page);
            box.setManaged(each == page);
        });
        scroll.setVvalue(0);
        if (page == Page.PERFORMANCE) {
            updateDiskSize();
        }
    }

    // --- Appearance ---

    private VBox appearancePage(Appearance appearance, Consumer<Theme> save) {
        for (Theme theme : Theme.values()) {
            cards.getChildren().add(card(theme));
        }
        themes.selectToggle(themes.getToggles().get(appearance.choiceProperty().get().ordinal()));
        themes.selectedToggleProperty().addListener((_, old, selected) -> {
            if (selected == null) {
                old.setSelected(true); // one look is always chosen
            } else {
                Theme theme = (Theme) selected.getUserData();
                appearance.choiceProperty().set(theme);
                save.accept(theme);
            }
        });
        Label note = wrapping("settings-note", 0);
        note.textProperty().bind(appearance.systemSchemeProperty().map(scheme -> "Follow system matches your"
                + " computer's light or dark mode (now " + (scheme == ColorScheme.DARK ? "dark" : "light")
                + ") and changes with it. AMOLED black uses pure black, which saves power on OLED screens."));
        return new VBox(12, heading("Appearance"), cards, note);
    }

    // --- Hidden items, Ignored folders ---

    /**
     * Hidden items: first the switch for files hidden in File Explorer, then what Prismo's Hide does: two choices,
     * each a card with its name and what it does.
     */
    private VBox hiddenSection() {
        showOsHidden.getStyleClass().add("choice-name");
        showOsHidden.setSelected(options.osHiddenShown().get());
        showOsHidden.selectedProperty().addListener((_, _, on) -> options.osHiddenShown().set(on));
        Label switchDescription = wrapping("choice-description", 58);
        switchDescription.setText("Off: files marked Hidden in File Explorer, or in a folder marked Hidden, show"
                + " only in Collections › Hidden. On: they show everywhere, like other files.");
        VBox.setMargin(switchDescription, new Insets(0, 0, 0, 26));
        VBox switchCard = new VBox(3, showOsHidden, switchDescription);
        switchCard.getStyleClass().add("choice-card");
        switchCard.setOnMouseClicked(event -> {
            if (!inside(event.getTarget(), showOsHidden)) {
                showOsHidden.setSelected(!showOsHidden.isSelected()); // the whole card flips it
            }
        });
        showOsHidden.selectedProperty().subscribe(on -> switchCard.pseudoClassStateChanged(SELECTED, on));
        Label hideHeading = wrapping("settings-subheading", 0);
        hideHeading.setText("When you hide an item in Prismo");
        VBox choices = new VBox(8);
        for (HiddenMode mode : HiddenMode.values()) {
            choices.getChildren().add(choiceCard(mode.label(), mode.description(), mode, hiddenModes));
        }
        hiddenModes.selectToggle(hiddenModes.getToggles().get(options.hiddenMode().get().ordinal()));
        hiddenModes.selectedToggleProperty().addListener((_, old, selected) -> {
            if (selected == null) {
                old.setSelected(true); // one choice is always on
            } else {
                options.hiddenMode().set((HiddenMode) selected.getUserData());
            }
        });
        Label note = wrapping("settings-note", 0);
        note.setText("The right-click menu can also mark a file Hidden in File Explorer, or clear that mark.");
        VBox section = new VBox(12, heading("Hidden items"), switchCard, hideHeading, choices, note);
        VBox.setMargin(hideHeading, new Insets(6, 0, 0, 0));
        return section;
    }

    /** Ignored folders: the rules as a list (each with a remove button), then ways to add one. */
    private VBox ignoredSection() {
        Label note = wrapping("settings-note", 0);
        note.setText("Prismo skips these folders inside your library folders: their photos and videos don't show,"
                + " and any already shown leave Prismo (the files themselves aren't touched). A name like"
                + " node_modules counts in every folder; a folder you choose counts only there.");
        ignoredList.getStyleClass().add("ignored-list");
        options.ignoredFolders().subscribe(this::fillIgnoredList);

        TextField name = new TextField();
        name.setPromptText("A folder name, e.g. node_modules");
        HBox.setHgrow(name, Priority.ALWAYS);
        Button add = new Button("Add", Icon.PLUS.node(14));
        add.disableProperty().bind(name.textProperty().map(String::isBlank));
        Runnable addName = () -> {
            if (!name.getText().isBlank()) {
                options.ignoredFolders().set(options.ignoredFolders().get().with(name.getText()));
                name.clear();
            }
        };
        add.setOnAction(_ -> addName.run());
        name.setOnAction(_ -> addName.run());
        Button choose = new Button("Choose folder…", Icon.FOLDER.node(14));
        choose.setOnAction(_ -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Choose a folder to ignore");
            File folder = chooser.showDialog(stage);
            if (folder != null) {
                options.ignoredFolders().set(options.ignoredFolders().get().with(folder.getAbsolutePath()));
            }
        });
        HBox addRow = new HBox(8, name, add, choose);
        addRow.setAlignment(Pos.CENTER_LEFT);
        Hyperlink defaults = new Hyperlink("Restore the usual list");
        defaults.getStyleClass().add("settings-link");
        defaults.setOnAction(_ -> options.ignoredFolders().set(new IgnoreRules(IgnoreRules.DEFAULTS)));
        return new VBox(12, heading("Ignored folders"), note, ignoredList, addRow, defaults);
    }

    private void fillIgnoredList(IgnoreRules rules) {
        ignoredList.getChildren().clear();
        if (rules.rules().isEmpty()) {
            Label none = new Label("No folders are ignored.");
            none.getStyleClass().add("settings-note");
            ignoredList.getChildren().add(none);
            return;
        }
        for (String rule : rules.rules()) {
            Label text = new Label(rule);
            text.getStyleClass().add("ignored-rule");
            text.setTooltip(new Tooltip(IgnoreRules.isFullFolder(rule) ? "This folder and everything in it"
                    : "Every folder with this name, in all your library folders"));
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button remove = new Button(null, Icon.CLOSE.node(14));
            remove.getStyleClass().add("icon-button");
            remove.setTooltip(new Tooltip("Stop ignoring " + rule));
            remove.setOnAction(_ -> options.ignoredFolders().set(options.ignoredFolders().get().without(rule)));
            HBox row = new HBox(10, Icon.FOLDER.node(16), text, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("ignored-row");
            ignoredList.getChildren().add(row);
        }
    }

    // --- Timeline ---

    private VBox timelinePage() {
        VBox choices = new VBox(8);
        for (Grouping grouping : Grouping.values()) {
            choices.getChildren().add(choiceCard(grouping.label(), grouping.description(), grouping, groupings));
        }
        groupings.selectToggle(groupings.getToggles().get(options.grouping().get().ordinal()));
        groupings.selectedToggleProperty().addListener((_, old, selected) -> {
            if (selected == null) {
                old.setSelected(true);
            } else {
                options.grouping().set((Grouping) selected.getUserData());
            }
        });
        Label note = wrapping("settings-note", 0);
        note.setText("Used on the Timeline, in search results and on the Collections pages when they are sorted by a"
                + " date. Sorting by name, size or resolution shows no headers.");
        return new VBox(12, heading("Date headers"), choices, note);
    }

    // --- Video ---

    /** The box behind subtitles: five cards (None, 25 % … 100 %), a sample line on a light-to-dark strip, a note. */
    private VBox videoPage() {
        VBox choices = new VBox(8);
        for (SubtitleBackground background : SubtitleBackground.values()) {
            choices.getChildren().add(choiceCard(background.label(), background.description(), background,
                    subtitleBackgrounds));
        }
        // A sample: the same style classes as the real line (prismo.css), over a strip from light to dark.
        Label sample = new Label("Subtitles look like this");
        sample.getStyleClass().addAll("viewer-subtitle", options.subtitleBackground().get().styleClass());
        subtitleBackgrounds.selectToggle(subtitleBackgrounds.getToggles().get(
                options.subtitleBackground().get().ordinal()));
        subtitleBackgrounds.selectedToggleProperty().addListener((_, old, selected) -> {
            if (selected == null) {
                old.setSelected(true);
            } else {
                SubtitleBackground background = (SubtitleBackground) selected.getUserData();
                options.subtitleBackground().set(background);
                for (SubtitleBackground each : SubtitleBackground.values()) {
                    sample.getStyleClass().remove(each.styleClass());
                }
                sample.getStyleClass().add(background.styleClass());
            }
        });
        StackPane strip = new StackPane(sample);
        strip.getStyleClass().add("subtitle-sample");
        Label note = wrapping("settings-note", 0);
        note.setText("Also in a video's right-click menu: Subtitles › Background. Prismo remembers the sound track and"
                + " subtitles you pick in each video, and starts other videos in the language you picked last.");
        return new VBox(12, heading("Subtitle background"), choices, strip, note);
    }

    // --- Performance ---

    private VBox performancePage() {
        int max = ThumbnailService.MAX_THREADS;
        ComboBox<Integer> threads = new ComboBox<>();
        for (int i = 1; i <= max; i++) {
            threads.getItems().add(i);
        }
        threads.setCellFactory(_ -> new ListCell<>() {
            @Override
            protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : coresText(item, max));
            }
        });
        threads.setButtonCell(threads.getCellFactory().call(null));
        threads.setValue(Math.clamp(options.thumbnailThreads().get(), 1, max));
        threads.valueProperty().addListener((_, _, value) -> {
            if (value != null) {
                options.thumbnailThreads().set(value);
            }
        });
        Label threadsLabel = new Label("Making thumbnails uses");
        HBox threadsRow = new HBox(10, threadsLabel, threads);
        threadsRow.setAlignment(Pos.CENTER_LEFT);
        Label threadsNote = wrapping("settings-note", 0);
        threadsNote.setText("Fewer cores keep other programs quick while Prismo makes thumbnails (the first look"
                + " at a big library); more make them sooner. This computer has " + max + " cores.");

        diskSize.getStyleClass().add("settings-value");
        Label diskLabel = new Label("Thumbnails on disk:");
        clearThumbnails.setOnAction(_ -> {
            clearThumbnails.setDisable(true);
            diskSize.setText("Clearing…");
            options.thumbnailDisk().clear().whenComplete((freed, error) -> Platform.runLater(() -> {
                clearThumbnails.setDisable(false);
                if (error != null) {
                    diskSize.setText("Couldn't clear them all");
                } else {
                    updateDiskSize();
                }
            }));
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox diskRow = new HBox(8, diskLabel, diskSize, spacer, clearThumbnails);
        diskRow.setAlignment(Pos.CENTER_LEFT);
        Label diskNote = wrapping("settings-note", 0);
        diskNote.setText("Clearing frees that space; thumbnails are made again as you scroll. Your photos and"
                + " videos aren't touched.");
        return new VBox(12, heading("Thumbnails"), threadsRow, threadsNote, diskRow, diskNote);
    }

    /** "4 of 8 cores (the usual)". */
    static String coresText(int threads, int max) {
        String text = threads + " of " + max + (max == 1 ? " core" : " cores");
        return threads == ThumbnailService.DEFAULT_THREADS ? text + " (the usual)" : text;
    }

    private void updateDiskSize() {
        options.thumbnailDisk().size().whenComplete((bytes, error) -> Platform.runLater(() ->
                diskSize.setText(error != null ? "Unknown" : MediaDetails.shortSize(bytes))));
    }

    // --- About ---

    private VBox aboutPage() {
        ImageView logo = new ImageView();
        logo.getStyleClass().add("empty-state-logo"); // the right logo for the look comes from the stylesheet
        logo.setFitWidth(LOGO_WIDTH);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        Label version = new Label("Version " + options.about().version());
        version.getStyleClass().add("settings-value");
        Label line = wrapping("settings-note", 0);
        line.setText("A fast, offline-first photo and video library for your own folders. Nothing leaves your"
                + " computer. Open source, MIT License.");
        Node website;
        if (options.about().website().isPresent()) {
            URI address = options.about().website().get();
            Hyperlink link = new Hyperlink("Website", Icon.OPEN_EXTERNAL.node(14));
            link.getStyleClass().add("settings-link");
            link.setTooltip(new Tooltip(address.toString()));
            link.setOnAction(_ -> options.openWebsite().accept(address));
            website = link;
        } else {
            Label soon = new Label("Website (coming soon)", Icon.OPEN_EXTERNAL.node(14));
            soon.getStyleClass().add("settings-link-disabled");
            website = soon;
        }
        VBox page = new VBox(10, logo, version, line, website);
        page.setAlignment(Pos.TOP_LEFT);
        return page;
    }

    // --- Keyboard shortcuts ---

    /** Every key, by where it works: a heading per group, then rows of key caps and what they do. */
    private VBox shortcutsPage() {
        VBox list = new VBox(4);
        list.getStyleClass().add("shortcuts-list");
        for (Shortcuts.Section section : Shortcuts.SECTIONS) {
            Label title = new Label(section.title());
            title.getStyleClass().add("settings-subheading");
            VBox.setMargin(title, new Insets(list.getChildren().isEmpty() ? 0 : 10, 0, 2, 0));
            list.getChildren().add(title);
            for (Shortcuts.Shortcut shortcut : section.shortcuts()) {
                list.getChildren().add(shortcutRow(shortcut));
            }
        }
        shortcutsScroll.setContent(list);
        shortcutsScroll.setFitToWidth(true);
        shortcutsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        shortcutsScroll.getStyleClass().add("settings-scroll");
        return new VBox(12, shortcutsHeading, shortcutsScroll);
    }

    /** "Ctrl+1 / Ctrl+2   Timeline / Collections": each key in a cap, "/" between them, then the text. */
    private static HBox shortcutRow(Shortcuts.Shortcut shortcut) {
        HBox keys = new HBox(4);
        keys.setAlignment(Pos.CENTER_LEFT);
        keys.getStyleClass().add("shortcut-keys");
        String[] parts = shortcut.keys().split(" / ");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                Label or = new Label("/");
                or.getStyleClass().add("shortcut-or");
                keys.getChildren().add(or);
            }
            Label cap = new Label(parts[i]);
            cap.getStyleClass().add("shortcut-key");
            cap.setMinWidth(Region.USE_PREF_SIZE);
            keys.getChildren().add(cap);
        }
        Label does = new Label(shortcut.does());
        does.getStyleClass().add("shortcut-does");
        does.setWrapText(true);
        HBox.setHgrow(does, Priority.ALWAYS);
        HBox row = new HBox(12, keys, does);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("shortcut-row");
        return row;
    }

    // --- Parts ---

    private static Label heading(String text) {
        Label heading = new Label(text);
        heading.getStyleClass().add("settings-heading");
        return heading;
    }

    /** A plain card around a standard radio button, with a line about the choice under its name. */
    private VBox choiceCard(String label, String description, Object value, ToggleGroup group) {
        // A toggle button holding wrapping text was measured at almost no width (one word per line) and grew
        // taller than the screen, so: a box with a radio button and a label.
        RadioButton name = new RadioButton(label);
        name.getStyleClass().add("choice-name");
        name.setUserData(value);
        name.setToggleGroup(group);
        Label text = wrapping("choice-description", 58); // less the card's padding and the radio
        text.setText(description);
        VBox.setMargin(text, new Insets(0, 0, 0, 26)); // under the name, not under the radio circle
        VBox choice = new VBox(3, name, text);
        choice.getStyleClass().add("choice-card");
        choice.setOnMouseClicked(_ -> name.setSelected(true)); // the whole card picks it
        name.selectedProperty().subscribe(on -> choice.pseudoClassStateChanged(SELECTED, on));
        return choice;
    }

    /** True if {@code target} (a click's target) is {@code node} or inside it. */
    private static boolean inside(Object target, Node node) {
        for (Node at = target instanceof Node n ? n : null; at != null; at = at.getParent()) {
            if (at == node) {
                return true;
            }
        }
        return false;
    }

    /** A label that wraps at the content width less {@code inset}, and is always as tall as its lines. */
    private Label wrapping(String styleClass, double inset) {
        Label label = new Label();
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        label.prefWidthProperty().bind(contentWidth.subtract(inset));
        label.maxWidthProperty().bind(contentWidth.subtract(inset));
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    /** One look: a picture of it above its name. Arrow keys move between the cards, Space picks one. */
    private ToggleButton card(Theme theme) {
        ToggleButton card = new ToggleButton(theme.label(), preview(theme));
        card.setUserData(theme);
        card.setToggleGroup(themes);
        card.setContentDisplay(ContentDisplay.TOP);
        card.getStyleClass().add("theme-card");
        return card;
    }

    /**
     * A tiny window in the look's colours: a top bar with an accent dot and a grid of tiles. Follow system is
     * the light picture with the dark one over its right half. Colours are in the stylesheet ({@code .theme-preview}).
     */
    private static Node preview(Theme theme) {
        if (theme == Theme.SYSTEM) {
            StackPane darkHalf = new StackPane(miniWindow(Theme.DARK));
            darkHalf.setClip(new Rectangle(PREVIEW_WIDTH / 2, 0, PREVIEW_WIDTH / 2, PREVIEW_HEIGHT));
            return new StackPane(miniWindow(Theme.LIGHT), darkHalf);
        }
        return miniWindow(theme);
    }

    private static Node miniWindow(Theme theme) {
        Region bar = new Region();
        bar.getStyleClass().add("preview-bar");
        bar.setPrefHeight(14);
        Region accent = new Region();
        accent.getStyleClass().add("preview-accent");
        accent.setPrefSize(18, 6);
        accent.setMaxSize(18, 6);
        StackPane top = new StackPane(bar, accent);
        StackPane.setAlignment(accent, Pos.CENTER_LEFT);
        StackPane.setMargin(accent, new Insets(0, 0, 0, 8));
        HBox row1 = tiles();
        HBox row2 = tiles();
        VBox body = new VBox(5, row1, row2);
        body.setPadding(new Insets(7, 8, 7, 8));
        VBox window = new VBox(top, body);
        window.getStyleClass().addAll("theme-preview", "theme-preview-" + theme.settingValue());
        window.setPrefSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        window.setMinSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        window.setMaxSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        Rectangle rounded = new Rectangle(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        rounded.setArcWidth(12);
        rounded.setArcHeight(12);
        window.setClip(rounded);
        return window;
    }

    private static HBox tiles() {
        HBox row = new HBox(5);
        for (int i = 0; i < 4; i++) {
            Region tile = new Region();
            tile.getStyleClass().add("preview-tile");
            tile.setPrefSize(19, 19);
            row.getChildren().add(tile);
        }
        return row;
    }

    // --- For tests and tools ---

    Stage stage() {
        return stage;
    }

    ToggleGroup themes() {
        return themes;
    }

    ToggleGroup hiddenModes() {
        return hiddenModes;
    }

    ToggleGroup subtitleBackgrounds() {
        return subtitleBackgrounds;
    }

    CheckBox showOsHidden() {
        return showOsHidden;
    }

    /** Shows {@code page} as if its entry in the list were clicked (no effect for a page this system lacks). */
    void select(Page page) {
        pageList.getToggles().stream().filter(t -> t.getUserData() == page).findFirst()
                .ifPresent(pageList::selectToggle);
    }
}
