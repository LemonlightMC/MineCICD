package com.lemonlightmc.minecicd.secrets;

import com.lemonlightmc.minecicd.MineCICD;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.eclipse.jgit.attributes.FilterCommandRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SecretManager {

    private static final String FILTER_NAME = "minecicd";
    private static final String ATTR_FILE = ".gitattributes";
    private static final String CONFIG_FILE = "config";
    private static final String GIT_DIR = ".git";
    private static final String LINE_SEP = System.lineSeparator();
    private static final String FILTERS_BEGIN = "# MineCICD FILTERS BEGIN";
    private static final String FILTERS_END = "# MineCICD FILTERS END";

    private final MineCICD plugin;
    private final Path dataDir;
    private final List<SecretFileEntry> files = new ArrayList<>();
    private final List<SecretMapping> mappings = new ArrayList<>();

    private final Set<String> registeredFilterKeys = new HashSet<>();

    public static record SecretMapping(String file, String key, String value) {
        public String placeholder() {
            return "__MCICD_" + Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8))
                    + "__";
        }

        public boolean isPresent() {
            return value != null && !value.isEmpty();
        }

        public String normalizedFile() {
            return file.replace('\\', '/');
        }
    }

    public static record SecretFileEntry(String file, String driverName) {

        public SecretFileEntry(final String file) {
            // Hash the normalized path so the driver name is stable regardless
            // of whether secrets.yml uses '/' or '\' separators.
            this(file.replace('\\', '/'), SecretManager.driverNameFor(file.replace('\\', '/')));

        }

        /** Registry key for the JGit builtin clean filter of a file. */
        public String cleanKeyFor() {
            return driverName + "-clean";
        }

        /** Registry key for the JGit builtin smudge filter of a file. */
        public String smudgeKeyFor() {
            return driverName + "-smudge";
        }
    }

    public SecretManager(final MineCICD plugin) {
        this.plugin = plugin;
        this.dataDir = plugin.getDataFolder().toPath();
    }

    public boolean hasSecrets() {
        return !mappings.isEmpty();
    }

    public List<SecretMapping> mapping() {
        return mappings;
    }

    public List<SecretFileEntry> files() {
        return files;
    }

    public void load() {
        mappings.clear();
        files.clear();
        final File secretsFile = dataDir.resolve("secrets.yml").toFile();
        if (secretsFile.isFile()) {
            final YamlConfiguration secrets = YamlConfiguration.loadConfiguration(secretsFile);
            for (final String path : secrets.getKeys(false)) {
                if (!secrets.isConfigurationSection(path)) {
                    continue;
                }
                final ConfigurationSection section = secrets.getConfigurationSection(path);
                final String file = section.getString("file");
                if (file == null || file.isBlank()) {
                    continue;
                }
                files.add(new SecretFileEntry(file));
                for (final String secretKey : section.getKeys(false)) {
                    if ("file".equals(secretKey)) {
                        continue;
                    }
                    final String secretValue = section.getString(secretKey);
                    if (secretValue == null || secretValue.isEmpty()) {
                        continue;
                    }
                    mappings.add(new SecretMapping(file, secretKey, secretValue));

                }
            }
            // Drop duplicate file entries, keeping the first occurrence. Each
            // file gets exactly one filter driver section.
            final Set<String> seen = new HashSet<>();
            files.removeIf(entry -> !seen.add(entry.file()));
        }

        // Write .gitattributes routing each configured file to its own per-file
        // filter driver (minecicd-<hex>) and, when a .git directory exists,
        // write the matching [filter "minecicd-<hex>"] sections into .git/config.
        // JGit runs the transformation in-process via FilterCommandRegistry; the
        // git CLI on other machines skips it (required=false) and checks out the
        // placeholder content, so real secrets only ever exist in this server's
        // working tree.
        writeAttributes();
        writeGitConfig();
        // Register the JGit builtin filters used by GitService (in-process, no
        // external java -jar spawn). Note: registration applies to JGit only; the
        // git CLI skips the transformation on other machines via required=false.
        registerFilters();
    }

    private void writeAttributes() {
        final Path attributes = plugin.serverRoot().resolve(ATTR_FILE);
        try {
            final StringBuilder out = new StringBuilder();
            // Remove any MineCICD block already written (and any lone marker
            // lines an interrupted legacy write left behind) so the block is
            // replaced rather than duplicated on every reload.
            if (Files.exists(attributes)) {
                out.append(stripStaleAttributesBlock(Files.readString(attributes, StandardCharsets.UTF_8)));
            }
            // Check Line Seperator
            if (out.length() > LINE_SEP.length() && out.lastIndexOf(LINE_SEP) < out.length() - LINE_SEP.length()) {
                out.append(LINE_SEP);
            }

            // append filters
            out.append(buildAttributesBlock(new StringBuilder(), files));
            Files.write(attributes, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to write .gitattributes: " + e.getMessage());
        }
    }

    /**
     * Builds the MineCICD-managed portion of .gitattributes: one
     * {@code <escaped-file> filter=minecicd-<hex>} line per secrets.yml file,
     * wrapped in the markers that {@code writeAttributes} uses to replace the
     * block on the next write.
     */
    static String buildAttributesBlock(final StringBuilder builder, final List<SecretFileEntry> entries) {
        builder.append(FILTERS_BEGIN).append(LINE_SEP);
        for (final SecretFileEntry entry : entries) {
            if (entry == null || entry.file() == null || entry.file().isBlank()) {
                continue;
            }
            builder.append(escapeAttr(entry.file())).append(" filter=").append(entry.driverName())
                    .append(LINE_SEP);
        }
        builder.append(FILTERS_END).append(LINE_SEP);
        return builder.toString();
    }

    /**
     * Removes every MineCICD-managed region from .gitattributes: each
     * {@code # MineCICD FILTERS BEGIN/END} block as well as any lone marker
     * line left behind by an interrupted legacy write. Other attribute rules
     * are kept line-by-line (trailing {@code \r} dropped, then re-appended
     * with {@code LINE_SEP}), so the managed block can never accumulate.
     */
    static String stripStaleAttributesBlock(final String content) {
        final String[] lines = content.split("\\r?\\n", -1);
        // split(..., -1) yields one trailing empty element for a final newline;
        // that element must not become an extra (growing) empty line on each pass.
        int last = lines.length;
        if (last > 0 && lines[last - 1].isEmpty()) {
            last--;
        }
        final StringBuilder out = new StringBuilder();
        boolean inBlock = false;
        for (int i = 0; i < last; i++) {
            final String line = lines[i];
            final String trimmed = line.trim();
            if (inBlock) {
                if (trimmed.equals(FILTERS_END)) {
                    inBlock = false;
                }
                continue;
            }
            if (trimmed.equals(FILTERS_BEGIN) || trimmed.equals(FILTERS_END)) {
                if (trimmed.equals(FILTERS_BEGIN)) {
                    inBlock = true;
                }
                continue;
            }
            out.append(stripTrailingCr(line)).append(LINE_SEP);
        }
        return out.toString();
    }

    private void writeGitConfig() {
        final Path gitDir = plugin.serverRoot().resolve(GIT_DIR);
        if (!Files.isDirectory(gitDir)) {
            return;
        }
        final Path config = gitDir.resolve(CONFIG_FILE);

        try {
            final StringBuilder out = new StringBuilder();
            if (Files.exists(config)) {
                out.append(stripStaleSections(Files.readString(config, StandardCharsets.UTF_8)));
            }
            // Check Line Seperator
            if (out.length() > LINE_SEP.length() && out.lastIndexOf(LINE_SEP) < out.length() - LINE_SEP.length()) {
                out.append(LINE_SEP);
            }

            // append per-file filter sections. Each driver is named minecicd-<hex>
            // and refers to a JGit builtin registered in FilterCommandRegistry; the
            // git CLI (on other machines) treats these names as commands that do
            // not exist, and because required=false it skips the transformation
            // with a warning instead of aborting.
            out.append(buildFilterConfigBlock(new StringBuilder(), files));
            Files.write(config, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to write .git/config filters: " + e.getMessage());
        }
    }

    /**
     * Builds the MineCICD-managed portion of .git/config: one
     * {@code [filter "minecicd-<hex>"]} section per secrets.yml file whose
     * {@code clean}/{@code smudge} values are the exact JGit builtin registry
     * keys, with {@code required = false} so the git CLI skips (rather than
     * aborts on) the unknown command names.
     */
    static String buildFilterConfigBlock(final StringBuilder builder, final List<SecretFileEntry> entries) {
        builder.append(FILTERS_BEGIN).append(LINE_SEP);
        for (final SecretFileEntry entry : entries) {
            if (entry == null || entry.file() == null || entry.file().isBlank()) {
                continue;
            }
            builder.append("[filter \"").append(entry.driverName()).append("\"]").append(LINE_SEP);
            builder.append("\tclean = ").append(entry.cleanKeyFor()).append(LINE_SEP);
            builder.append("\tsmudge = ").append(entry.smudgeKeyFor()).append(LINE_SEP);
            builder.append("\trequired = false").append(LINE_SEP);
            builder.append(LINE_SEP);
        }
        builder.append(FILTERS_END).append(LINE_SEP);
        return builder.toString();
    }

    /**
     * Removes everything MineCICD previously wrote into .git/config: the
     * {@code # MineCICD FILTERS BEGIN/END} marker block, the legacy
     * {@code [filter "minecicd"]} java -jar section, and any stale
     * {@code [filter "minecicd-*"]} builtin sections. Other sections are kept
     * byte-for-byte (line endings normalized by the caller).
     */
    static String stripStaleSections(final String config) {
        final String[] lines = config.split("\\r?\\n", -1);
        // split(..., -1) yields one trailing empty element for a final newline;
        // that element must not become an extra (growing) empty line on each pass.
        int last = lines.length;
        if (last > 0 && lines[last - 1].isEmpty()) {
            last--;
        }
final StringBuilder out = new StringBuilder();
            boolean skipToEndMarker = false;
            boolean skipToNextSection = false;
            for (int i = 0; i < last; i++) {
                final String line = lines[i];
                final String trimmed = line.trim();
                if (skipToEndMarker) {
                    if (trimmed.equals(FILTERS_END)) {
                        skipToEndMarker = false;
                    }
                    continue;
                }
                if (skipToNextSection) {
                    if (trimmed.startsWith("[")) {
                        skipToNextSection = false;
                    } else {
                        continue;
                    }
                }
                // Drop stray marker lines outside a marker block (leftovers of an
                // interrupted legacy write) so they cannot confuse later stripping.
                if (trimmed.equals(FILTERS_BEGIN)) {
                    skipToEndMarker = true;
                    continue;
                }
                if (trimmed.equals(FILTERS_END)) {
                    continue;
                }
                if (isMineCicdSectionHeader(trimmed)) {
                    skipToNextSection = true;
                    continue;
                }
                out.append(stripTrailingCr(line)).append(LINE_SEP);
            }
            return out.toString();
        }

    private static boolean isMineCicdSectionHeader(final String trimmed) {
        return trimmed.startsWith("[filter \"" + FILTER_NAME + "\"]")
                || trimmed.startsWith("[filter \"" + FILTER_NAME + "-");
    }

    /**
     * Drops a single trailing {@code \r} from a line read out of a CRLF file,
     * so re-appending {@code LINE_SEP} never produces doubled {@code \r\r\n}.
     * LF-only files are untouched.
     */
    private static String stripTrailingCr(final String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /**
     * Registers one clean and one smudge JGit builtin filter per secrets.yml
     * file. Re-registration is safe: stale keys are unregistered first.
     */
    public void registerFilters() {
        unregisterFilters();
        for (final SecretFileEntry entry : files) {
            registerKey(entry.cleanKeyFor(),
                    new MineCicdFilterFactory(this::mapping, entry.file(), MineCicdFilterCommand.Direction.CLEAN));
            registerKey(entry.smudgeKeyFor(),
                    new MineCicdFilterFactory(this::mapping, entry.file(), MineCicdFilterCommand.Direction.SMUDGE));
        }
        if (!files.isEmpty()) {
            plugin.getLogger().info("Registered " + registeredFilterKeys.size() + " builtin replace filters.");
        }
    }

    /**
     * Unregisters all MineCicD JGit builtin filters. Idempotent; safe to call on
     * every reload and on plugin disable.
     */
    public void unregisterFilters() {
        for (final String key : registeredFilterKeys) {
            FilterCommandRegistry.unregister(key);
        }
        registeredFilterKeys.clear();
    }

    private void registerKey(final String key, final MineCicdFilterFactory factory) {
        FilterCommandRegistry.register(key, factory);
        registeredFilterKeys.add(key);
    }

    /**
     * Deterministic, per-file filter driver name ({@code minecicd-<hex>} from
     * SHA-256 of the normalized path). The names are stable across restarts and
     * use only {@code [0-9a-f]}, which is safe in gitattributes attribute values.
     */
    private static String driverNameFor(final String file) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(file.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return FILTER_NAME + "-" + hex;
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA specification.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String escapeAttr(final String file) {
        return file.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}