package io.github.mihirvr.prismo.collections;

/**
 * The People window's tick box for one person (1.2.0), without JavaFX: what the box shows and what a click does.
 *
 * <p>Two things are kept apart: how many of the chosen files have the person now ({@link #state}: all, some, none),
 * and what the user decided ({@link Decision}: leave as it is, add to every file, remove from every file). The box
 * shows the decision, or the current state while nothing was decided. So the window only ever ADDS unless the user
 * unticks someone: leaving a person alone changes nothing, whatever the files had. Keeping them apart also lets the
 * window count the files again (the subfolders picked changed) without losing what the user ticked.
 *
 * <p>A click steps through what makes sense for the state: some → ticked (add) → empty (remove) → dash (as it is);
 * all → empty (remove) → ticked (as it is); none → ticked (add) → empty (as it is).
 */
public final class PeopleChoice {

    /** How many of the chosen files have the person now. */
    public enum State { ALL, SOME, NONE }

    /** What the user decided. */
    public enum Decision { KEEP, ADD, REMOVE }

    /** What the box shows. */
    public enum Box { TICKED, DASH, EMPTY }

    private PeopleChoice() {
    }

    /** The state from a count: {@code have} of {@code total} files have the person. No files: none. */
    public static State state(int have, int total) {
        if (total <= 0 || have <= 0) {
            return State.NONE;
        }
        return have >= total ? State.ALL : State.SOME;
    }

    public static Box box(State state, Decision decision) {
        return switch (decision) {
            case ADD -> Box.TICKED;
            case REMOVE -> Box.EMPTY;
            case KEEP -> switch (state) {
                case ALL -> Box.TICKED;
                case SOME -> Box.DASH;
                case NONE -> Box.EMPTY;
            };
        };
    }

    /** The decision after one click. */
    public static Decision click(State state, Decision decision) {
        return switch (state) {
            case SOME -> switch (decision) {
                case KEEP -> Decision.ADD;
                case ADD -> Decision.REMOVE;
                case REMOVE -> Decision.KEEP;
            };
            // A decision that shows the same as the state (ADD on all, REMOVE on none: the files picked changed after
            // it was made) counts as KEEP, so every click visibly changes the box.
            case ALL -> decision == Decision.REMOVE ? Decision.KEEP : Decision.REMOVE;
            case NONE -> decision == Decision.ADD ? Decision.KEEP : Decision.ADD;
        };
    }

    /**
     * How many files the decision changes: for ADD the files without the person, for REMOVE the files with them, else
     * none. ({@code have} of {@code total} have the person now.)
     */
    public static int changes(Decision decision, int have, int total) {
        return switch (decision) {
            case ADD -> Math.max(0, total - have);
            case REMOVE -> Math.max(0, have);
            case KEEP -> 0;
        };
    }
}
