package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.platform.WindowsTitleBar;
import java.util.List;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Scene;
import javafx.scene.control.Dialog;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Puts the chosen {@link Theme} on the main window and keeps it there: when the choice changes, and (for Follow
 * system) when the operating system switches between light and dark while Prismo runs.
 *
 * <p>A theme is the scene's list of stylesheets. Menus, drop-down lists and tooltips are separate small windows,
 * but JavaFX styles them with their owner scene's stylesheets, so they follow by themselves. Dialogs are scenes of
 * their own: {@link #matchOwner} makes them copy their owner. Each window's title bar is drawn by the system: the
 * scene's colour-scheme preference (JavaFX 25) says light or dark, and {@link WindowsTitleBar} passes that on to
 * Windows, which JavaFX itself doesn't do for a normal window.
 *
 * <p>All methods run on the JavaFX Application Thread.
 */
public final class Appearance {

    private final Scene scene;
    private final Platform.Preferences system = Platform.getPreferences();
    private final ObjectProperty<Theme> choice;
    private final ReadOnlyObjectWrapper<Theme> shown = new ReadOnlyObjectWrapper<>();

    /** Applies {@code initial} to {@code scene} at once. */
    public Appearance(Scene scene, Theme initial) {
        this.scene = scene;
        choice = new SimpleObjectProperty<>(initial);
        choice.addListener((_, _, _) -> apply());
        system.colorSchemeProperty().addListener((_, _, _) -> apply()); // matters only for Follow system
        apply();
        followTitleBar(scene);
    }

    /** The user's choice (may be {@link Theme#SYSTEM}). Setting it restyles every window at once. */
    public ObjectProperty<Theme> choiceProperty() {
        return choice;
    }

    /** The look on screen now: the choice, with Follow system resolved to Light or Dark. */
    public ReadOnlyObjectProperty<Theme> shownProperty() {
        return shown.getReadOnlyProperty();
    }

    /** The operating system's light / dark setting, live. */
    public ReadOnlyObjectProperty<ColorScheme> systemSchemeProperty() {
        return system.colorSchemeProperty();
    }

    private void apply() {
        Theme resolved = choice.get().resolve(system.getColorScheme() == ColorScheme.DARK);
        shown.set(resolved);
        // The title bar first: dialogs copy it when they see the stylesheets change.
        scene.getPreferences().setColorScheme(resolved.isDark() ? ColorScheme.DARK : ColorScheme.LIGHT);
        List<String> urls = resolved.stylesheets().stream().map(Appearance::url).toList();
        if (!scene.getStylesheets().equals(urls)) {
            scene.getStylesheets().setAll(urls);
        }
    }

    /** A stylesheet resource's URL, as JavaFX wants it. */
    static String url(String resource) {
        return Appearance.class.getResource(resource).toExternalForm();
    }

    /**
     * Gives a dialog's scene its owner's look (stylesheets, light or dark title bar, the app icon), and keeps it in
     * step while it is open, so a theme change shows in an open dialog too. For dialogs shown once (Prismo makes a
     * new one each time).
     */
    public static void matchOwner(Scene dialog, Window owner) {
        Scene ownerScene = owner == null ? null : owner.getScene();
        if (ownerScene == null) {
            return;
        }
        followTitleBar(dialog);
        Runnable copy = () -> {
            dialog.getPreferences().setColorScheme(ownerScene.getPreferences().getColorScheme());
            dialog.getStylesheets().setAll(ownerScene.getStylesheets());
        };
        copy.run();
        InvalidationListener follow = _ -> copy.run();
        ownerScene.getStylesheets().addListener(follow);
        dialog.windowProperty().subscribe(window -> {
            if (window == null) {
                return;
            }
            // Without its own icon a dialog would show Java's default one in its title bar.
            if (window instanceof Stage stage && owner instanceof Stage ownerStage && stage.getIcons().isEmpty()) {
                stage.getIcons().setAll(ownerStage.getIcons());
            }
            window.addEventHandler(WindowEvent.WINDOW_HIDDEN, _ -> ownerScene.getStylesheets().removeListener(follow));
        });
    }

    /**
     * Keeps the title bar of {@code scene}'s window light or dark like the scene: when the window opens (it has no
     * native window before) and whenever the scene's colour scheme changes.
     */
    private static void followTitleBar(Scene scene) {
        // Run one pulse later: when WINDOW_SHOWN fires, Windows is still setting the new window up and ignores the
        // request (measured: the dialog's title bar stayed white; the same call a moment later worked).
        Runnable paint = () -> Platform.runLater(() -> {
            if (scene.getWindow() instanceof Stage stage && stage.isShowing()) {
                WindowsTitleBar.setDark(stage.getTitle(), scene.getPreferences().getColorScheme() == ColorScheme.DARK);
            }
        });
        scene.getPreferences().colorSchemeProperty().subscribe(_ -> paint.run());
        scene.windowProperty().subscribe(window -> {
            if (window != null) {
                window.addEventHandler(WindowEvent.WINDOW_SHOWN, _ -> paint.run());
            }
        });
    }

    /** {@link #matchOwner(Scene, Window)} for a JavaFX dialog (an {@code Alert}); call after {@code initOwner}. */
    public static void matchOwner(Dialog<?> dialog) {
        dialog.getDialogPane().sceneProperty().subscribe(dialogScene -> {
            if (dialogScene != null) {
                matchOwner(dialogScene, dialog.getOwner());
            }
        });
    }
}
