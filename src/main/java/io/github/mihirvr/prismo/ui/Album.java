package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;

/**
 * One album: a folder that directly holds media (PROJECT.md "Albums": auto-generated, no manual albums in v1); or one
 * tag's card: a person on the People page (1.2.0) or a category on the Categories page (1.3.0), the same cards.
 *
 * @param folder  the folder's path as stored in {@code media.folder}; null for a tag
 * @param name    the folder's own name ("Camera", "Screenshots"), or the tag's name
 * @param count   media directly in it (or with the tag), for the shared filter
 * @param cover   its newest photo or GIF (newest by date taken), or null if it has none (only videos, say)
 * @param tagKind for a tag's card: People or Categories; null for a folder album
 * @param tagId   the tag's id ({@code person.id}, {@code category.id}); 0 for a folder album
 */
public record Album(String folder, String name, int count, MediaRef cover, TagKind tagKind, long tagId) {

    public Album {
        if ((tagKind == null) != (tagId == 0)) {
            throw new IllegalArgumentException("a tag's card needs its kind and id, and only it");
        }
    }

    /** A folder album. */
    public Album(String folder, String name, int count, MediaRef cover) {
        this(folder, name, count, cover, null, 0);
    }

    /** A tag's card: a person on the People page (1.2.0), a category on the Categories page (1.3.0). */
    public static Album tag(TagKind kind, long id, String name, int count, MediaRef cover) {
        return new Album(null, name, count, cover, kind, id);
    }

    public boolean isTag() {
        return tagKind != null;
    }
}
