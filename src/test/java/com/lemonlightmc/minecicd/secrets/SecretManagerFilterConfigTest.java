package com.lemonlightmc.minecicd.secrets;

import com.lemonlightmc.minecicd.secrets.SecretManager.SecretFileEntry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretManagerFilterConfigTest {

    private static final String FILE = "plugins/example/config.yml";
    private static final String LINE_SEP = System.lineSeparator();

    private static SecretFileEntry entryFor(String file) {
        return new SecretFileEntry(file);
    }

    @Test
    void driverNameIsDeterministic() {
        assertEquals(entryFor(FILE).driverName(), entryFor(FILE).driverName());
    }

    @Test
    void driverNameIsDistinctPerFile() {
        assertNotEquals(
                entryFor("plugins/a/config.yml").driverName(),
                entryFor("plugins/b/config.yml").driverName());
    }

    @Test
    void driverNameUsesOnlyAllowedCharset() {
        assertTrue(entryFor(FILE).driverName().matches("minecicd-[0-9a-f]{16}"));
    }

    @Test
    void driverNameNormalizesSeparators() {
        assertEquals(
                entryFor("plugins/a/config.yml").driverName(),
                entryFor("plugins\\a\\config.yml").driverName());
    }

    @Test
    void fileComponentIsNormalized() {
        assertEquals("plugins/a/config.yml", entryFor("plugins\\a\\config.yml").file());
    }

    @Test
    void registryKeysCarryDirectionSuffixes() {
        SecretFileEntry entry = entryFor(FILE);
        assertEquals(entry.driverName() + "-clean", entry.cleanKeyFor());
        assertEquals(entry.driverName() + "-smudge", entry.smudgeKeyFor());
    }

    @Test
    void attributesBlockWrapsMarkersAndRoutesToPerFileDriver() {
        SecretFileEntry entry = entryFor(FILE);
        String block = SecretManager.buildAttributesBlock(new StringBuilder(), List.of(entry));
        assertTrue(block.startsWith("# MineCICD FILTERS BEGIN" + LINE_SEP));
        assertTrue(block.endsWith("# MineCICD FILTERS END" + LINE_SEP));
        assertTrue(block.contains(FILE + " filter=" + entry.driverName() + LINE_SEP));
    }

    @Test
    void attributesBlockSkipsBlankEntries() {
        SecretFileEntry fileEntry = entryFor(FILE);
        String block = SecretManager.buildAttributesBlock(new StringBuilder(),
                Arrays.asList(entryFor(""), fileEntry, null));
        assertFalse(block.contains(" filter=" + LINE_SEP));
        assertTrue(block.contains(FILE + " filter=" + fileEntry.driverName()));
    }

    @Test
    void configBlockHasPerFileSectionWithRequiredFalse() {
        SecretFileEntry entry = entryFor(FILE);
        String block = SecretManager.buildFilterConfigBlock(new StringBuilder(), List.of(entry));
        assertTrue(block.startsWith("# MineCICD FILTERS BEGIN" + LINE_SEP));
        assertTrue(block.endsWith("# MineCICD FILTERS END" + LINE_SEP));
        assertTrue(block.contains("[filter \"" + entry.driverName() + "\"]" + LINE_SEP));
        assertTrue(block.contains("\tclean = " + entry.cleanKeyFor() + LINE_SEP));
        assertTrue(block.contains("\tsmudge = " + entry.smudgeKeyFor() + LINE_SEP));
        assertTrue(block.contains("\trequired = false" + LINE_SEP));
    }

    @Test
    void noJavaProcessCommandOrPlaceholderTokenIsWritten() {
        SecretFileEntry entry = entryFor(FILE);
        String config = SecretManager.buildFilterConfigBlock(new StringBuilder(), List.of(entry));
        String attributes = SecretManager.buildAttributesBlock(new StringBuilder(), List.of(entry));
        assertFalse((config + attributes).contains("java"));
        assertFalse((config + attributes).contains("-jar"));
        assertFalse((config + attributes).contains("%f"));
    }

    @Test
    void stripRemovesLegacyJavaProcessSectionsButKeepsOthers() {
        String config = String.join("\n",
                "[core]",
                "\trepositoryformatversion = 0",
                "[filter \"minecicd\"]",
                "\tclean = \"java -jar plugin.jar %f\"",
                "\tsmudge = \"java -jar plugin.jar %f\"",
                "\trequired = true",
                "[remote \"origin\"]",
                "\turl = https://example.com/repo.git",
                "");
        String stripped = SecretManager.stripStaleSections(config);
        assertTrue(stripped.contains("[core]"));
        assertTrue(stripped.contains("[remote \"origin\"]"));
        assertFalse(stripped.contains("[filter \"minecicd\"]"));
        assertFalse(stripped.contains("java -jar"));
    }

    @Test
    void stripRemovesMarkerBlockAndBuiltinSections() {
        String config = String.join("\n",
                "[core]",
                "\trepositoryformatversion = 0",
                "# MineCICD FILTERS BEGIN",
                "[filter \"minecicd-3f9a1c2e4b5d6f70\"]",
                "\tclean = minecicd-3f9a1c2e4b5d6f70-clean",
                "\tsmudge = minecicd-3f9a1c2e4b5d6f70-smudge",
                "\trequired = false",
                "",
                "[filter \"minecicd-8a1b2c3d4e5f6071\"]",
                "\tclean = minecicd-8a1b2c3d4e5f6071-clean",
                "# MineCICD FILTERS END",
                "[filter \"lfs\"]",
                "\tclean = git-lfs clean -- %f",
                "",
                "");
        String stripped = SecretManager.stripStaleSections(config);
        assertTrue(stripped.contains("[core]"));
        assertTrue(stripped.contains("[filter \"lfs\"]"), "unrelated filter sections must survive");
        assertFalse(stripped.contains("MineCICD FILTERS"));
        assertFalse(stripped.contains("[filter \"minecicd-"));
    }

    @Test
    void stripIsIdempotent() {
        String once = SecretManager.stripStaleSections("[core]\n\ta = b\n# MineCICD FILTERS BEGIN\n[filter \"minecicd-1\"]\n# MineCICD FILTERS END\n");
        assertEquals(once, SecretManager.stripStaleSections(once));
    }
}