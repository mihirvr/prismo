package io.github.mihirvr.prismo.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

/**
 * What the user can do to one item: the same list in the viewer's top bar, the right-click menu and the keys
 * (F favorite, H hide, Delete to Trash; in Trash, Delete = delete permanently). Each changes only Prismo's own
 * database, except {@link #DELETE_PERMANENTLY}, the one action that removes the file (PROJECT.md "Trash"), and
 * the File Explorer pair, which set or clear the file's Hidden attribute (P6; Windows only).
 */
public enum ItemAction {
    FAVORITE("Add to Favorites", Icon.HEART, null, KeyCode.F),
    UNFAVORITE("Remove from Favorites", Icon.HEART_FILLED, null, KeyCode.F),
    HIDE("Hide", Icon.EYE_OFF, null, KeyCode.H),
    UNHIDE("Unhide", Icon.EYE, null, KeyCode.H),
    /** Sets the file's Hidden attribute (File Explorer then hides it too). */
    HIDE_IN_EXPLORER("Hide in File Explorer", Icon.EYE_OFF, null, null),
    /** Clears the file's Hidden attribute. */
    UNHIDE_IN_EXPLORER("Unhide in File Explorer", Icon.EYE, null, null),
    TRASH("Move to Trash", Icon.TRASH, null, KeyCode.DELETE),
    RESTORE("Restore from Trash", Icon.RESTORE, "Restore", null),
    DELETE_PERMANENTLY("Delete permanently…", Icon.TRASH, "Delete permanently…", KeyCode.DELETE);

    private final String menuText;
    private final Icon icon;
    private final String buttonText;
    private final KeyCode key;

    ItemAction(String menuText, Icon icon, String buttonText, KeyCode key) {
        this.menuText = menuText;
        this.icon = icon;
        this.buttonText = buttonText;
        this.key = key;
    }

    /** {@link #available(TimelineItem, boolean)} with the File Explorer switch off (the default). */
    public static List<ItemAction> available(TimelineItem item) {
        return available(item, false);
    }

    /**
     * What can be done to {@code item} now, in menu order (the viewer's buttons). The eye is Hide, or Unhide for
     * an item hidden in Prismo; an item that only has the File Explorer mark gets "Unhide in File Explorer"
     * there, since that is the only way to bring it back. With {@code osHiddenShown} (the Settings switch "Show
     * files hidden in File Explorer") such an item is like any other, so its eye is Hide.
     */
    public static List<ItemAction> available(TimelineItem item, boolean osHiddenShown) {
        if (item.trashed()) {
            return List.of(RESTORE, DELETE_PERMANENTLY);
        }
        return List.of(item.favorite() ? UNFAVORITE : FAVORITE, eye(item, osHiddenShown), TRASH);
    }

    private static ItemAction eye(TimelineItem item, boolean osHiddenShown) {
        return item.hidden() ? UNHIDE : item.osHidden() && !osHiddenShown ? UNHIDE_IN_EXPLORER : HIDE;
    }

    /** {@link #forMenu(TimelineItem, boolean, boolean)} with the File Explorer switch off (the default). */
    public static List<ItemAction> forMenu(TimelineItem item, boolean osMarkSupported) {
        return forMenu(item, osMarkSupported, false);
    }

    /**
     * The right-click menu's actions: {@link #available} plus, where the system has a Hidden mark
     * ({@code osMarkSupported}), "Hide in File Explorer" or "Unhide in File Explorer" if that isn't there already.
     */
    public static List<ItemAction> forMenu(TimelineItem item, boolean osMarkSupported, boolean osHiddenShown) {
        List<ItemAction> actions = new ArrayList<>(available(item, osHiddenShown));
        if (osMarkSupported && !item.trashed()) {
            ItemAction mark = item.osHidden() ? UNHIDE_IN_EXPLORER : HIDE_IN_EXPLORER;
            if (!actions.contains(mark)) {
                actions.add(actions.indexOf(TRASH), mark);
            }
        }
        return actions;
    }

    /** {@link #forKey(KeyCode, TimelineItem, boolean)} with the File Explorer switch off (the default). */
    public static ItemAction forKey(KeyCode code, TimelineItem item) {
        return forKey(code, item, false);
    }

    /** The action a key does to {@code item}, or null if that key does nothing to it. */
    public static ItemAction forKey(KeyCode code, TimelineItem item, boolean osHiddenShown) {
        return switch (code) {
            case F -> item.trashed() ? null : item.favorite() ? UNFAVORITE : FAVORITE;
            case H -> item.trashed() ? null : eye(item, osHiddenShown);
            case DELETE -> item.trashed() ? DELETE_PERMANENTLY : TRASH;
            default -> null;
        };
    }

    /**
     * The selection bar's actions for {@code items} (P7; all from one page, so all in Trash or none): Restore and
     * Delete permanently in Trash; otherwise Favorite (Remove when every item is a favorite already), the eye (Unhide
     * when every item is hidden, in Prismo or by a File Explorer mark that hides it) and Move to Trash.
     */
    public static List<ItemAction> forSelection(List<TimelineItem> items, boolean osHiddenShown) {
        if (items.isEmpty()) {
            return List.of();
        }
        if (items.stream().allMatch(TimelineItem::trashed)) {
            return List.of(RESTORE, DELETE_PERMANENTLY);
        }
        boolean allFavorite = items.stream().allMatch(TimelineItem::favorite);
        boolean allHidden = items.stream().allMatch(item -> eye(item, osHiddenShown) != HIDE);
        return List.of(allFavorite ? UNFAVORITE : FAVORITE, allHidden ? UNHIDE : HIDE, TRASH);
    }

    /** The key's action for a selection ({@link #forSelection}), or null: F, H and Delete. */
    public static ItemAction forSelectionKey(KeyCode code, List<TimelineItem> items, boolean osHiddenShown) {
        for (ItemAction action : forSelection(items, osHiddenShown)) {
            if (action.key == code) {
                return action;
            }
        }
        return null;
    }

    /**
     * What this action, chosen for a whole selection, does to one item of it, or null to leave the item as it is
     * (Add to Favorites skips a favorite; Unhide brings back a file only hidden in File Explorer the one way it can
     * be: {@link #UNHIDE_IN_EXPLORER}).
     */
    public ItemAction forItem(TimelineItem item, boolean osHiddenShown) {
        return switch (this) {
            case FAVORITE -> item.trashed() || item.favorite() ? null : FAVORITE;
            case UNFAVORITE -> item.trashed() || !item.favorite() ? null : UNFAVORITE;
            case HIDE -> item.trashed() || eye(item, osHiddenShown) != HIDE ? null : HIDE;
            case UNHIDE -> item.trashed() || eye(item, osHiddenShown) == HIDE ? null : eye(item, osHiddenShown);
            case HIDE_IN_EXPLORER -> item.trashed() || item.osHidden() ? null : HIDE_IN_EXPLORER;
            case UNHIDE_IN_EXPLORER -> item.trashed() || !item.osHidden() ? null : UNHIDE_IN_EXPLORER;
            case TRASH -> item.trashed() ? null : TRASH;
            case RESTORE, DELETE_PERMANENTLY -> item.trashed() ? this : null;
        };
    }

    /** {@link #keepsItemInView(TimelineItem, boolean)} with the File Explorer switch off (the default). */
    public boolean keepsItemInView(TimelineItem item) {
        return keepsItemInView(item, false);
    }

    /**
     * True if {@code item} still belongs to the view it was done in afterwards. Favoriting does. Setting or
     * clearing the File Explorer mark does when the item is hidden in Prismo anyway (it stays on Hidden), or when
     * that mark doesn't hide anything ({@code osHiddenShown}, the Settings switch). Hiding, unhiding, trashing,
     * restoring and deleting move it to another page (or out of Prismo).
     */
    public boolean keepsItemInView(TimelineItem item, boolean osHiddenShown) {
        return switch (this) {
            case FAVORITE, UNFAVORITE -> true;
            case HIDE_IN_EXPLORER, UNHIDE_IN_EXPLORER -> item.hidden() || osHiddenShown;
            default -> false;
        };
    }

    /** The item after a {@link #keepsItemInView} action. */
    public TimelineItem applyTo(TimelineItem item) {
        return switch (this) {
            case FAVORITE -> item.withFavorite(true);
            case UNFAVORITE -> item.withFavorite(false);
            case HIDE_IN_EXPLORER -> item.withOsHidden(true);
            case UNHIDE_IN_EXPLORER -> item.withOsHidden(false);
            default -> item;
        };
    }

    public String menuText() {
        return menuText;
    }

    public Icon icon() {
        return icon;
    }

    /**
     * The text next to the icon on the viewer's button, or null for an icon-only button (its tooltip names it).
     * Only the Trash page's actions have one: they are rarer, and deleting must never be a guess.
     */
    public String buttonText() {
        return buttonText;
    }

    /** The key shown next to it in menus (F, H, Delete), or null. */
    public KeyCombination shortcut() {
        return key == null ? null : new KeyCodeCombination(key);
    }

    /** "Add to Favorites (F)". */
    public String tooltip() {
        return key == null ? menuText : menuText + " (" + key.getName() + ")";
    }

    /**
     * The key shown next to it for {@code item}: its own ({@link #shortcut()}), or H when H does this to that item
     * (the eye of a file only hidden in File Explorer is "Unhide in File Explorer").
     */
    public KeyCombination shortcut(TimelineItem item, boolean osHiddenShown) {
        return key == null && forKey(KeyCode.H, item, osHiddenShown) == this ? new KeyCodeCombination(KeyCode.H)
                : shortcut();
    }

    /** {@link #tooltip()} with the key {@link #shortcut(TimelineItem, boolean)} shows. */
    public String tooltip(TimelineItem item, boolean osHiddenShown) {
        KeyCombination shown = shortcut(item, osHiddenShown);
        return shown == null ? menuText : menuText + " (" + shown.getDisplayText() + ")";
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

        /**
         * Starts {@code action} (from {@link #forSelection}) on every item of a selection, as one change: one
         * question before Delete permanently, one status message, one reload. Each item gets
         * {@link ItemAction#forItem}. This default just does them one by one.
         *
         * @return false if nothing was done (the user cancelled, or nothing in the selection needed it)
         */
        default boolean performAll(ItemAction action, List<TimelineItem> items) {
            boolean any = false;
            for (TimelineItem item : items) {
                ItemAction own = action.forItem(item, false);
                any |= own != null && perform(own, item);
            }
            return any;
        }
    }
}
