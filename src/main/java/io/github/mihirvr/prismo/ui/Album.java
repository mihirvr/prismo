package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;

/**
 * One album: a folder that directly holds media (PROJECT.md "Albums": auto-generated, no manual albums in v1); or,
 * since 1.2.0, one person on the People page (the same cards).
 *
 * @param folder   the folder's path as stored in {@code media.folder}; null for a person
 * @param name     the folder's own name ("Camera", "Screenshots"), or the person's name
 * @param count    media directly in it (or tagged with the person), for the shared filter
 * @param cover    its newest photo or GIF (newest by date taken), or null if it has none (only videos, say)
 * @param personId the person's id ({@code person.id}); 0 for a folder album
 */
public record Album(String folder, String name, int count, MediaRef cover, long personId) {

    /** A folder album. */
    public Album(String folder, String name, int count, MediaRef cover) {
        this(folder, name, count, cover, 0);
    }

    /** A person's card on the People page (1.2.0). */
    public static Album person(long id, String name, int count, MediaRef cover) {
        return new Album(null, name, count, cover, id);
    }

    public boolean isPerson() {
        return personId != 0;
    }
}
