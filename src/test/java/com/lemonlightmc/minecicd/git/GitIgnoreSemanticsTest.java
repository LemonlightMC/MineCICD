package com.lemonlightmc.minecicd.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feedback loop: the default MineCICD .gitignore template ignores every file
 * (a bare {@code *} plus {@code !}dir re-inclusions), so {@code add} must write
 * a whitelist negation to actually make a path trackable. Verifies both the git
 * mechanics and the GitIgnoreEditor contract that backs the add/remove commands.
 */
class GitIgnoreSemanticsTest {

    private static final String TEMPLATE = """
            *
            !*/

            # MineCICD GITIGNORE PART BEGIN MARKER
            # MineCICD GITIGNORE PART END MARKER
            """;

    @TempDir
    Path dir;

    private Path serverRoot() {
        return dir.resolve("server");
    }

    private void writeIgnore() throws Exception {
        Files.createDirectories(serverRoot());
        Files.writeString(serverRoot().resolve(".gitignore"), TEMPLATE);
    }

    private Git initRepo() throws Exception {
        writeIgnore();
        return Git.init().setDirectory(serverRoot().toFile()).call();
    }

    private GitIgnoreEditor editor() {
        return new GitIgnoreEditor(serverRoot());
    }

    @Test
    void gitAddDotStagesNothingByDefault() throws Exception {
        try (Git git = initRepo()) {
            Path foo = serverRoot().resolve("plugins/foo");
            Files.createDirectories(foo);
            Files.writeString(foo.resolve("config.yml"), "a=1");

            git.add().addFilepattern(".").call();
            Status status = git.status().call();

            assertTrue(status.getUncommittedChanges().isEmpty(),
                    "expected nothing staged/added under default template, got: " + status.getUncommittedChanges());
        }
    }

    @Test
    void negateTreeTracksFilesInside() throws Exception {
        try (Git git = initRepo()) {
            Path foo = serverRoot().resolve("plugins/foo");
            Files.createDirectories(foo);
            Files.writeString(foo.resolve("config.yml"), "a=1");

            assertTrue(editor().track("plugins/foo/"));
            git.add().addFilepattern(".").call();
            Status status = git.status().call();

            assertTrue(status.getUncommittedChanges().contains("plugins/foo/config.yml"),
                    "expected config.yml tracked after add, got: " + status.getUncommittedChanges());
        }
    }

    @Test
    void addTracksSingleFile() throws Exception {
        try (Git git = initRepo()) {
            Files.writeString(serverRoot().resolve("server.properties"), "motd=hi\n");

            assertTrue(editor().track("server.properties"));
            List<String> entries = editor().readEntries();
            assertTrue(entries.contains("!server.properties"), "managed entries: " + entries);

            git.add().addFilepattern(".").call();
            Status status = git.status().call();
            assertTrue(status.getUncommittedChanges().contains("server.properties"),
                    "expected server.properties tracked, got: " + status.getUncommittedChanges());
        }
    }

    @Test
    void addDotTracksAllFiles() throws Exception {
        try (Git git = initRepo()) {
            Path foo = serverRoot().resolve("plugins/foo");
            Files.createDirectories(foo);
            Files.writeString(foo.resolve("config.yml"), "a=1");
            Files.writeString(serverRoot().resolve("server.properties"), "motd=hi\n");

            assertTrue(editor().track(""));
            assertTrue(editor().readEntries().contains("!*"));

            git.add().addFilepattern(".").call();
            Status status = git.status().call();
            assertTrue(status.getUncommittedChanges().contains("plugins/foo/config.yml"),
                    "expected config.yml tracked after add all, got: " + status.getUncommittedChanges());
            assertTrue(status.getUncommittedChanges().contains("server.properties"),
                    "expected server.properties tracked after add all, got: " + status.getUncommittedChanges());
        }
    }

    @Test
    void addTwiceReportsNoChange() throws Exception {
        writeIgnore();
        GitIgnoreEditor editor = editor();
        assertTrue(editor.track("plugins/foo/"));
        assertFalse(editor.track("plugins/foo/"), "second add must not report a change");
        assertFalse(editor.track("./plugins/foo/"), "path-form variant must not report a change");
        assertFalse(editor.track("plugins/foo"), "slash-less variant must not report a change");
        assertEquals(1, editor.readEntries().size());
    }

    @Test
    void untrackRemovesWhitelistAndReportsChange() throws Exception {
        writeIgnore();
        GitIgnoreEditor editor = editor();
        assertTrue(editor.track("plugins/foo/"));
        assertTrue(editor.untrack("plugins/foo"));
        assertFalse(editor.isTracked("plugins/foo"));
        assertFalse(editor.untrack("plugins/foo"), "second untrack must not report a change");
    }

    @Test
    void addRemovesConflictingPlainExclusion() throws Exception {
        Files.createDirectories(serverRoot());
        Files.writeString(serverRoot().resolve(".gitignore"), """
                *
                !*/

                # MineCICD GITIGNORE PART BEGIN MARKER
                plugins/foo/
                # MineCICD GITIGNORE PART END MARKER
                """);
        GitIgnoreEditor editor = editor();
        assertTrue(editor.track("plugins/foo/"));
        assertFalse(editor.readEntries().contains("plugins/foo/"),
                "conflicting plain exclusion must be removed, entries: " + editor.readEntries());
        assertTrue(editor.readEntries().contains("!plugins/foo/**"),
                "whitelist must be added, entries: " + editor.readEntries());
    }

    @Test
    void untrackAllRevertsTrackAll() throws Exception {
        writeIgnore();
        GitIgnoreEditor editor = editor();
        assertTrue(editor.trackAll());
        assertTrue(editor.untrackAll());
        assertFalse(editor.isTracked(""));
        assertTrue(editor.readEntries().isEmpty(), "managed entries: " + editor.readEntries());
    }

    @Test
    void pathFormsAreCanonicalized() throws Exception {
        writeIgnore();
        GitIgnoreEditor editor = editor();
        assertTrue(editor.track("plugins/foo/"));
        assertTrue(editor.isTracked("./plugins/foo"));
        assertTrue(editor.isTracked("/plugins/foo/"));
        assertTrue(editor.isTracked("!plugins/foo/**"));
        assertTrue(editor.isTracked("plugins/foo/**"));
    }
}