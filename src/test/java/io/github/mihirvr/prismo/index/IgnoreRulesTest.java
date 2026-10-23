package io.github.mihirvr.prismo.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.db.Database;
import io.github.mihirvr.prismo.testing.TestMedia;
import io.github.mihirvr.prismo.testing.TestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IgnoreRulesTest {

    private static final Path ROOT = TestPaths.of("Photos");

    @TempDir
    Path tmp;

    @Test
    void aFolderNameMatchesAnywhereInsideTheLibraryButNotInTheLibraryFoldersOwnPath() {
        IgnoreRules rules = new IgnoreRules(List.of("node_modules"));

        assertTrue(rules.ignores(ROOT, in("site", "node_modules")));
        assertTrue(rules.ignores(ROOT, in("site", "Node_Modules", "pic.png")), "any case, and what's inside");
        assertFalse(rules.ignores(ROOT, in("site", "node_modules_old")), "a whole name, not a part");
        assertFalse(rules.ignores(ROOT, ROOT), "never the library folder itself");
        Path rootNamedLikeARule = TestPaths.of("node_modules");
        assertFalse(rules.ignores(rootNamedLikeARule, rootNamedLikeARule.resolve("a.png")),
                "only folders inside the library count");
        Path old = TestPaths.of("Photos", "Old");
        assertFalse(new IgnoreRules(List.of(old.toString())).ignores(old, old),
                "a whole-folder rule never hides a library folder itself (turn it off instead)");
    }

    @Test
    void aShortPathMatchesThoseFoldersOneInsideTheOther() {
        IgnoreRules rules = new IgnoreRules(List.of("Android\\data"));

        assertTrue(rules.ignores(ROOT, in("Phone backup", "Android", "data", "com.app", "x.jpg")));
        assertFalse(rules.ignores(ROOT, in("Phone backup", "Android", "media", "x.jpg")));
        assertFalse(rules.ignores(ROOT, in("data", "Android", "x.jpg")), "in that order only");
    }

    @Test
    void aFullFolderMatchesItselfAndWhatsInsideOnly() {
        IgnoreRules rules = new IgnoreRules(List.of(ROOT.resolve("Private") + java.io.File.separator));

        assertTrue(rules.ignores(ROOT, in("Private")));
        assertTrue(rules.ignores(ROOT, in("private", "2024", "a.jpg")));
        assertFalse(rules.ignores(ROOT, in("Private stuff", "a.jpg")), "not a folder that starts the same");
        assertFalse(rules.ignores(ROOT, in("Trip", "Private", "a.jpg")), "a full folder isn't a name");
        assertTrue(IgnoreRules.isFullFolder("\\\\server\\share\\x"));
        assertTrue(IgnoreRules.isFullFolder("/home/me/x"));
        assertFalse(IgnoreRules.isFullFolder("Android\\data"));
    }

    @Test
    void savedAsLinesWithDefaultsWhenNeverSaved() {
        assertEquals(IgnoreRules.DEFAULTS, IgnoreRules.fromSetting(null).rules());
        assertEquals(List.of(), IgnoreRules.fromSetting("").rules(), "all removed stays empty");

        IgnoreRules rules = IgnoreRules.fromSetting(" .git \r\nnode_modules\n\nNODE_MODULES\nD:\\Photos\\Old\\");

        assertEquals(List.of(".git", "node_modules", "D:\\Photos\\Old"), rules.rules(), "trimmed, no blanks, no repeats");
        assertEquals(rules, IgnoreRules.fromSetting(rules.toSetting()));
        assertEquals(List.of(".git", "D:\\Photos\\Old"), rules.without("Node_Modules").rules());
        assertEquals(rules, rules.with(".GIT"), "adding one that's there changes nothing");
    }

    @Test
    void theIndexerSkipsIgnoredFoldersAndForgetsWhatWasIndexedInThem() throws Exception {
        Path library = Files.createDirectories(tmp.resolve("Pictures")).toRealPath();
        Path kept = TestMedia.png(library.resolve("a.png"), 10, 10);
        Path build = TestMedia.png(Files.createDirectories(library.resolve(Path.of("site", "node_modules", "pkg"))).resolve("logo.png"),
                10, 10);
        AtomicReference<IgnoreRules> rules = new AtomicReference<>(IgnoreRules.NONE);
        try (Database database = Database.open(tmp.resolve("prismo.db"))) {
            Indexer indexer = new Indexer(database, new MetadataReader(ZoneOffset.UTC), rules::get);
            indexer.index(List.of(library), _ -> { });
            assertEquals(2, count(database, "SELECT count(*) FROM media"));

            rules.set(new IgnoreRules(List.of("node_modules")));
            IndexProgress result = indexer.index(List.of(library), _ -> { });

            assertEquals(1, result.removed(), "the file in the now-ignored folder left the library");
            assertEquals(1, count(database, "SELECT count(*) FROM media WHERE path = '" + kept + "'"));
            // The watcher reports a new file in there: it stays out.
            Path added = TestMedia.png(build.resolveSibling("icon.png"), 10, 10);
            indexer.update(Map.of(library, Set.of(added, build.getParent())), _ -> { });
            assertEquals(1, count(database, "SELECT count(*) FROM media"));
        }
    }

    private static int count(Database database, String sql) throws Exception {
        return database.read(c -> {
            try (Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
                return rows.getInt(1);
            }
        });
    }

    /** A path inside the made-up library folder, built from parts (works on every OS). */
    private static Path in(String first, String... more) {
        return ROOT.resolve(Path.of(first, more));
    }
}
