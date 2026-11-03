package io.github.mihirvr.prismo.ui;

/**
 * How the Folders view lays out its two blocks (1.1.0): the files that sit directly in the top folder, and the
 * folders. Shared by the Grid and Collections › Folders, and saved ({@link #toSetting}).
 *
 * <p>A sort direction never moves a block: only {@link #foldersFirst} does.
 *
 * @param foldersFirst true = the folders block comes before the top folder's own files
 * @param folders      the order of the folders (never {@link Order#SORT})
 * @param topFiles     the order of the top folder's own files ({@link Order#SORT} = what the Sort bar says)
 * @param folderFiles  the order of the files inside each folder ({@link Order#SORT} = what the Sort bar says)
 */
public record FolderArrange(boolean foldersFirst, Order folders, Order topFiles, Order folderFiles) {

    /** One order choice in the Arrange menu. */
    public enum Order {
        SORT("Same as Sort"), NAME_ASC("A to Z"), NAME_DESC("Z to A"), NEWEST("Newest first"),
        OLDEST("Oldest first");

        private final String label;

        Order(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final FolderArrange DEFAULT = new FolderArrange(false, Order.NAME_ASC, Order.SORT, Order.SORT);

    public FolderArrange {
        if (folders == null || folders == Order.SORT || topFiles == null || folderFiles == null) {
            throw new IllegalArgumentException("folders need their own order: " + folders);
        }
    }

    public FolderArrange withFoldersFirst(boolean value) {
        return new FolderArrange(value, folders, topFiles, folderFiles);
    }

    public FolderArrange withFolders(Order value) {
        return new FolderArrange(foldersFirst, value, topFiles, folderFiles);
    }

    public FolderArrange withTopFiles(Order value) {
        return new FolderArrange(foldersFirst, folders, value, folderFiles);
    }

    public FolderArrange withFolderFiles(Order value) {
        return new FolderArrange(foldersFirst, folders, topFiles, value);
    }

    /** {@code "folders,NAME_ASC,SORT,NEWEST"} ({@code files} instead of {@code folders} when files come first). */
    public String toSetting() {
        return (foldersFirst ? "folders" : "files") + "," + folders.name() + "," + topFiles.name() + ","
                + folderFiles.name();
    }

    /** The value saved by {@link #toSetting}; missing or unreadable: the default. */
    public static FolderArrange fromSetting(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String[] parts = value.split(",");
        if (parts.length != 4 || !(parts[0].equals("folders") || parts[0].equals("files"))) {
            return DEFAULT;
        }
        try {
            return new FolderArrange(parts[0].equals("folders"), Order.valueOf(parts[1]), Order.valueOf(parts[2]),
                    Order.valueOf(parts[3]));
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
