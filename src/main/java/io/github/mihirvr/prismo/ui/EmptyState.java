package io.github.mihirvr.prismo.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;

/**
 * What a page shows when it has nothing to show: optionally the Prismo logo, a title, a line of text and one
 * button. Used as a list's placeholder; the parts that are switched off take no space.
 */
public final class EmptyState extends VBox {

    private static final double LOGO_WIDTH = 180;

    private final ImageView logo = new ImageView();
    private final Label title = new Label();
    private final Label text = new Label();
    private final Button action = new Button();

    public EmptyState() {
        getStyleClass().add("empty-state");
        setAlignment(Pos.CENTER);
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE);
        logo.setFitWidth(LOGO_WIDTH);
        logo.setPreserveRatio(true);
        logo.setSmooth(true);
        title.getStyleClass().add("empty-state-title");
        text.getStyleClass().add("empty-state-text");
        text.setWrapText(true);
        text.setMaxWidth(440);
        action.getStyleClass().add("accent-button");
        getChildren().addAll(logo, title, text, action);
        show(null, "", "", null, null);
    }

    /**
     * @param logoImage  shown above the title, or null for none
     * @param actionText the button's text, or null for no button
     * @param onAction   what the button does
     */
    public void show(Image logoImage, String titleText, String bodyText, String actionText, Runnable onAction) {
        logo.setImage(logoImage);
        showIf(logo, logoImage != null);
        title.setText(titleText);
        showIf(title, !titleText.isEmpty());
        text.setText(bodyText);
        showIf(text, !bodyText.isEmpty());
        action.setText(actionText);
        action.setOnAction(onAction == null ? null : _ -> onAction.run());
        showIf(action, actionText != null);
    }

    private static void showIf(Node node, boolean on) {
        node.setVisible(on);
        node.setManaged(on);
    }

    /** For tests and tools. */
    Button action() {
        return action;
    }

    Label title() {
        return title;
    }
}
