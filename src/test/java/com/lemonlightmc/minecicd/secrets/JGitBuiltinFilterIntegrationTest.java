package com.lemonlightmc.minecicd.secrets;

import com.lemonlightmc.minecicd.secrets.SecretManager.SecretFileEntry;
import com.lemonlightmc.minecicd.secrets.SecretManager.SecretMapping;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.attributes.FilterCommandRegistry;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test through real JGit plumbing: {@code git add} must run the
 * clean direction in-process via FilterCommandRegistry (no external process),
 * and re-checking out committed placeholder content must run smudge to restore
 * real values — while a file that has no secrets stays byte-for-byte untouched
 * across a value rotation (S-07).
 * <p>
 * Note: JGit records the raw file's length/mtime in the index even when the
 * staged blob is the placeholder, and a file whose clean output equals the
 * staged blob is considered in sync — so a HARD reset of an existing file does
 * not rewrite it. Smudge is observable on a fresh checkout (file missing),
 * which is exactly the real production case (first clone / new files on the
 * server).
 */
class JGitBuiltinFilterIntegrationTest {

    private Path workDir;
    private Git git;
    private Repository repo;
    private final List<String> registeredKeys = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        workDir = Files.createTempDirectory("minecicd-filter-it");
        git = Git.init().setDirectory(workDir.toFile()).call();
        repo = git.getRepository();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (String key : registeredKeys) {
            FilterCommandRegistry.unregister(key);
        }
        try {
            git.close();
        } finally {
            try (var stream = Files.walk(workDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    /** Registers per-file builtin clean+smudge and declares the config section. */
    private void registerFilter(String file, List<SecretMapping> mapping) throws Exception {
        SecretFileEntry entry = new SecretFileEntry(file);
        String driver = entry.driverName();
        FilterCommandRegistry.register(entry.cleanKeyFor(),
                new MineCicdFilterFactory(() -> mapping, entry.file(), MineCicdFilterCommand.Direction.CLEAN));
        FilterCommandRegistry.register(entry.smudgeKeyFor(),
                new MineCicdFilterFactory(() -> mapping, entry.file(), MineCicdFilterCommand.Direction.SMUDGE));
        registeredKeys.add(entry.cleanKeyFor());
        registeredKeys.add(entry.smudgeKeyFor());

        repo.getConfig().setString("filter", driver, "clean", entry.cleanKeyFor());
        repo.getConfig().setString("filter", driver, "smudge", entry.smudgeKeyFor());
        repo.getConfig().save();
    }

    private void writeAttribute(String file) throws Exception {
        Path attributes = workDir.resolve(".gitattributes");
        String line = file + " filter=" + new SecretFileEntry(file).driverName() + "\n";
        Files.writeString(attributes, Files.exists(attributes)
                ? Files.readString(attributes) + line
                : line, StandardCharsets.UTF_8);
    }

    private void writeFile(String file, String content) throws Exception {
        Files.writeString(workDir.resolve(file), content, StandardCharsets.UTF_8);
    }

    private void deleteFile(String file) throws Exception {
        Files.deleteIfExists(workDir.resolve(file));
    }

    /** Reads a working-tree file with CRLF normalized, so machines with a
     *  global {@code core.autocrlf} setting do not break content assertions. */
    private String readFile(String file) throws Exception {
        return Files.readString(workDir.resolve(file), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    private String staged(String path) throws Exception {
        DirCache cache = repo.readDirCache();
        DirCacheEntry entry = cache.getEntry(path);
        assertNotNull(entry, "index entry for " + path);
        try (ObjectReader reader = repo.newObjectReader()) {
            ObjectLoader loader = reader.open(entry.getObjectId(), Constants.OBJ_BLOB);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        }
    }

    private void commit(String message) throws Exception {
        PersonIdent identity = new PersonIdent("Test", "test@example.com");
        git.commit().setMessage(message).setAuthor(identity).setCommitter(identity).call();
    }

    private static List<SecretMapping> listOf(String file, String key, String value) {
        return List.of(new SecretMapping(file, key, value));
    }

    @Test
    void addRunsCleanInProcessAndFreshCheckoutRunsSmudge() throws Exception {
        registerFilter("config.yml", listOf("config.yml", "password", "s3cr3t!"));
        writeAttribute("config.yml");
        writeFile("config.yml", "password: s3cr3t!\n");

        // git add runs the clean builtin: index gains the placeholder, working tree stays real.
        git.add().addFilepattern(".").call();
        String staged = staged("config.yml");
        assertTrue(staged.contains("__MCICD_"), "index must hold the placeholder, got: " + staged);
        assertTrue(!staged.contains("s3cr3t!"), "index must not hold the real secret");
        assertEquals("password: s3cr3t!\n", Files.readString(workDir.resolve("config.yml")),
                "clean must not touch the working tree");
        commit("first");

        // Fresh checkout (working file missing): the committed placeholder blob
        // is written back through smudge, restoring the real value.
        deleteFile("config.yml");
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
        assertEquals("password: s3cr3t!\n", readFile("config.yml"),
                "smudge must restore the real value on checkout");
    }

    @Test
    void scopingAndValueRotationLeaveSecretlessFilesUntouched() throws Exception {
        // config.yml holds a secret whose literal value also appears in other.yml.
        // other.yml has no secret entries, so it must never be transformed.
        registerFilter("config.yml", listOf("config.yml", "password", "1234"));
        registerFilter("other.yml", List.of());
        writeAttribute("config.yml");
        writeAttribute("other.yml");
        writeFile("config.yml", "password: 1234\n");
        writeFile("other.yml", "port: 1234\n");

        git.add().addFilepattern(".").call();
        assertTrue(staged("config.yml").contains("__MCICD_"));
        assertEquals("port: 1234\n", staged("other.yml"),
                "file without secrets must be staged byte-for-byte");
        commit("first");

        // Rotate the secret: 1234 -> 9999, then re-checkout from the committed
        // placeholder blob. The literal "1234" in other.yml must survive unchanged,
        // and config.yml must receive the new value via smudge.
        registerFilter("config.yml", listOf("config.yml", "password", "9999"));
        deleteFile("config.yml");
        deleteFile("other.yml");
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call();
        assertEquals("password: 9999\n", readFile("config.yml"),
                "rotated value must land in the file that owns the secret");
        assertEquals("port: 1234\n", readFile("other.yml"),
                "unrelated file's literal must not be corrupted by rotation");
    }
}