package io.github.mihirvr.prismo.collections;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The folder People window finds its folder in the unfiltered tree, at any depth. */
class PeopleUiFindTest {

    private final FolderTree.Node beach = new FolderTree.Node("lib/Trips/Goa/Beach", "Beach", 2, 2, List.of());
    private final FolderTree.Node goa = new FolderTree.Node("lib/Trips/Goa", "Goa", 5, 7, List.of(beach));
    private final FolderTree.Node trips = new FolderTree.Node("lib/Trips", "Trips", 0, 7, List.of(goa));
    private final FolderTree.Node lib = new FolderTree.Node("lib", "lib", 0, 7, List.of(trips));

    @Test
    void findsTopAndDeepFolders() {
        assertSame(lib, PeopleUi.find(List.of(lib), "lib"));
        assertSame(trips, PeopleUi.find(List.of(lib), "lib/Trips"));
        assertSame(beach, PeopleUi.find(List.of(lib), "lib/Trips/Goa/Beach"));
    }

    @Test
    void anUnknownFolderIsNull() {
        assertNull(PeopleUi.find(List.of(lib), "lib/Elsewhere"));
        assertNull(PeopleUi.find(List.of(), "lib"));
    }
}
