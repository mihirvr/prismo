package io.github.mihirvr.prismo.viewer;

import io.github.mihirvr.prismo.video.TrackChoice;

/**
 * What the user picked in videos (P8), for {@link VideoPane}: per video ({@code db.TrackChoices}) and the languages
 * picked last (two settings). The app gives the real one; {@link #NONE} remembers nothing.
 */
public interface TrackMemory {

    /** Remembers nothing (tools, tests). */
    TrackMemory NONE = new TrackMemory() {
        @Override
        public TrackChoice.Picked load(long mediaId) {
            return TrackChoice.Picked.NOTHING;
        }

        @Override
        public void save(long mediaId, TrackChoice.Picked picked) {
        }

        @Override
        public String audioLanguage() {
            return null;
        }

        @Override
        public void setAudioLanguage(String language) {
        }

        @Override
        public String subtitleLanguage() {
            return null;
        }

        @Override
        public void setSubtitleLanguage(String language) {
        }
    };

    /** What was picked for video {@code mediaId}. Called on the UI thread: must be quick (one row by its key). */
    TrackChoice.Picked load(long mediaId);

    /** Remembers what was picked for video {@code mediaId}. Must not block. */
    void save(long mediaId, TrackChoice.Picked picked);

    /** The sound language picked last ("English"), or null. */
    String audioLanguage();

    void setAudioLanguage(String language);

    /** The subtitle language picked last ("English"), {@link TrackChoice#OFF}, or null. */
    String subtitleLanguage();

    void setSubtitleLanguage(String language);
}
