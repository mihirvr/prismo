package io.github.mihirvr.prismo.ui;

import java.util.List;
import javafx.scene.input.KeyCode;

/**
 * What the user can do to one item: the same list in the viewer's top bar, the right-click menu and the keys
 * (F favorite, H hide, Delete to Trash; in Trash, Delete = delete permanently). Each changes only Prismo's own
 * database, except {@link #DELETE_PERMANENTLY}, the one action that removes the file (PROJECT.md "Trash").
 */
public enum ItemAction {
    FAVORITE("Add to Favorites", "♡", "F"),
    UNFAVORITE("Remove from Favorites", "♥", "F"),
    HIDE("Hide", "Hide", "H"),
    UNHIDE("Unhide", "Unhide", "H"),
    TRASH("Move to Trash", "Trash", "Delete"),
    RESTORE("Restore from Trash", "Restore", null),
    DELETE_PERMANENTLY("Delete permanently…", "Delete permanently…", "Delete");

    private final String menuText;
    private final String buttonText;
    private final String key;

    ItemAction(String menuText, String buttonText, String key) {
        this.menuText = menuText;
        this.buttonText = buttonText;
        this.key = key;
    }

    /** What can be done to {@code item} now, in menu order. */
    public static List<ItemAction> available(TimelineItem item) {
        if (item.trashed()) {
            return List.of(RESTORE, DELETE_PERMANENTLY);
        }
        return List.of(item.favorite() ? UNFAVORITE : FAVORITE, item.hidden() ? UNHIDE : HIDE, TRASH);
    }

    /** The action a key does to {@code item}, or null if that key does nothing to it. */
    public static ItemAction forKey(KeyCode code, TimelineItem item) {
        return switch (code) {
            case F -> item.trashed() ? null : item.favorite() ? UNFAVORITE : FAVORITE;
            case H -> item.trashed() ? null : item.hidden() ? UNHIDE : HIDE;
            case DELETE -> item.trashed() ? DELETE_PERMANENTLY : TRASH;
            default -> null;
        };
    }

    /**
     * True if the item still belongs to the view it was done in. Only favoriting does; hiding, unhiding,
     * trashing, restoring and deleting move it to another page (or out of Prismo).
     */
    public boolean keepsItemInView() {
        return this == FAVORITE || this == UNFAVORITE;
    }

    /** The item after a {@link #keepsItemInView()} action. */
    public TimelineItem applyTo(TimelineItem item) {
        return switch (this) {
            case FAVORITE -> item.withFavorite(true);
            case UNFAVORITE -> item.withFavorite(false);
            default -> item;
        };
    }

    public String menuText() {
        return menuText;
    }

    public String buttonText() {
        return buttonText;
    }

    /** "Add to Favorites (F)". */
    public String tooltip() {
        return key == null ? menuText : menuText + " (" + key + ")";
    }

    /** Does an action to an item, for a grid or the viewer. Runs on the JavaFX Application Thread. */
    @FunctionalInterface
    public interface Handler {

        /**
         * Starts {@code action} on {@code item} (the database write runs in the background).
         *
         * @return false if nothing was done (the user cancelled a confirmation)
         */
        boolean perform(ItemAction action, TimelineItem item);
    }
}
