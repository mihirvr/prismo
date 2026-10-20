package io.github.mihirvr.prismo.viewer;

/** Where videos were left (watch progress): the app keeps it in the database ({@code db.PlaybackPositions}). */
public interface PlaybackMemory {

    /** Remembers nothing (tools, tests). */
    PlaybackMemory NONE = new PlaybackMemory() {
        @Override
        public double load(long mediaId) {
            return 0;
        }

        @Override
        public void save(long mediaId, double seconds, double durationSeconds) {
        }
    };

    /** The saved position of video {@code mediaId} in seconds, or 0. Called on the UI thread: must be quick. */
    double load(long mediaId);

    /** Remembers that video {@code mediaId} is at {@code seconds} of {@code durationSeconds}. Must not block. */
    void save(long mediaId, double seconds, double durationSeconds);
}
