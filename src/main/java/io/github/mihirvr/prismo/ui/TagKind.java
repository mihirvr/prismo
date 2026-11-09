package io.github.mihirvr.prismo.ui;

/**
 * The two kinds of manual tags: People (1.2.0) and Categories (1.3.0: Nature, Documents, Portraits…). Both work the
 * same way: the user makes a tag and puts it on photos and videos by hand; Prismo never looks at the pictures and never
 * writes into the files. An item can have tags of both kinds, and any number of each.
 *
 * <p>Each kind has its own two tables (a tag table and a link table to {@code media}), so the code that reads and
 * writes tags ({@code collections.Tags}), the tick-box window, the Collections page, the filter in Filters + Sort and the
 * Info panel are shared and only take the kind. The table and column names below are fixed text, never user input.
 */
public enum TagKind {

    PEOPLE("person", "media_person", "person_id", "p=",
            "People", "person", "people", "Person", "Everyone", "No people", "Who is in",
            "Show only some people's photos and videos"),
    CATEGORIES("category", "media_category", "category_id", "c=",
            "Categories", "category", "categories", "Category", "Every category", "No category", "Categories for",
            "Show only some categories of photos and videos");

    /** The tag table ({@code id}, {@code name}, {@code created_at}). */
    public final String table;
    /** The link table ({@code media_id}, {@link #column}). */
    public final String linkTable;
    /** The link table's column holding the tag's id. */
    public final String column;
    /** Marks this kind's part of the saved Filters + Sort ({@link ViewOptions#toSetting}); never in a type. */
    public final String settingPrefix;
    /** "People" / "Categories": the page, the menu entry, the window's title. */
    public final String title;
    /** "person" / "category". */
    public final String one;
    /** "people" / "categories". */
    public final String many;
    /** "Person" / "Category". */
    public final String oneTitle;
    /** The filter button when nothing is filtered: "Everyone" / "Every category". */
    public final String everything;
    /** The filter's "nothing tagged" choice: "No people" / "No category". */
    public final String untagged;
    /** The window's question for a folder: "Who is in" / "Categories for" + the folder's name. */
    final String folderQuestion;
    /** The filter button's tooltip. */
    public final String filterTip;

    TagKind(String table, String linkTable, String column, String settingPrefix, String title, String one, String many,
            String oneTitle, String everything, String untagged, String folderQuestion, String filterTip) {
        this.table = table;
        this.linkTable = linkTable;
        this.column = column;
        this.settingPrefix = settingPrefix;
        this.title = title;
        this.one = one;
        this.many = many;
        this.oneTitle = oneTitle;
        this.everything = everything;
        this.untagged = untagged;
        this.folderQuestion = folderQuestion;
        this.filterTip = filterTip;
    }

    /** The window's question for one item ({@code what} = "photo" or "video"). */
    public String itemQuestion(String what) {
        return this == PEOPLE ? "Who is in this " + what + "?" : "Which categories fit this " + what + "?";
    }

    /** The window's question for {@code count} items. */
    public String itemsQuestion(int count) {
        return this == PEOPLE ? "Who is in these %,d items?".formatted(count)
                : "Which categories fit these %,d items?".formatted(count);
    }

    /** The window's question for a folder named {@code name} (already quoted). */
    public String folderQuestion(String name) {
        return folderQuestion + " " + name + "?";
    }

    /** The menu entry: "People…" / "Categories…". */
    public String menuText() {
        return title + "…";
    }

    /** The window's search box. */
    public String searchPrompt() {
        return "Find or add a " + one;
    }

    /** The window's empty list. */
    public String emptyList() {
        return this == PEOPLE ? "No people yet. Type a name above to add someone."
                : "No categories yet. Type a name above to add one, such as Nature or Documents.";
    }

    /** Under the list while the typed name is new. */
    public String addsNew(String quotedName) {
        return "Press Enter or Add to add " + quotedName + " as a new " + one + ".";
    }

    /** The Collections page with no tags yet. */
    public String emptyPage() {
        return this == PEOPLE ? "No people yet. Add someone with New person, or right-click a photo › People…"
                : "No categories yet. Add one with New category, or right-click a photo › Categories…";
    }

    /** The page's button: "New person" / "New category". */
    public String newButton() {
        return "New " + one;
    }

    /** The question when making one. */
    public String newQuestion() {
        return this == PEOPLE ? "Who is it?" : "What is the category called?";
    }

    /** The back button's tooltip on one tag's page. */
    public String backTip() {
        return this == PEOPLE ? "Back to everyone" : "Back to every category";
    }

    /** A rename that would clash. */
    public String nameTaken(String name) {
        return this == PEOPLE ? "Someone is already called " + name + "." : "A category is already called " + name + ".";
    }

    /** The filter button's text with {@code count} ticked: "People: 2" / "Categories: 2". */
    public String filterLabel(int count) {
        return title + ": " + count;
    }

    /** The menu entry's tip in the filter menu when no tags exist yet: "No people yet" / "No categories yet". */
    public String noneYet() {
        return "No " + many + " yet";
    }
}
