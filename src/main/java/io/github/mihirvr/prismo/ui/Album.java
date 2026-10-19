package io.github.mihirvr.prismo.ui;

import io.github.mihirvr.prismo.thumbs.MediaRef;

/**
 * One album: a folder that directly holds media (PROJECT.md "Albums": auto-generated, no manual albums in v1).
 *
 * @param folder the folder's path as stored in {@code media.folder}
 * @param name   the folder's own name ("Camera", "Screenshots")
 * @param count  media directly in it, for the shared filter
 * @param cover  its newest photo or GIF (newest by date taken), or null if it has none (only videos, say)
 */
public record Album(String folder, String name, int count, MediaRef cover) {
}
