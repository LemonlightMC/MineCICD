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
    private final List<SecretMapping> mappings = new ArrayList<>();
    private final Set<String> registeredFilterKeys = new HashSet<>();

    public record SecretMapping(String file, String key, String value) {

        public String placeholder() {
            return "__MCICD_" + Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8)) + "__";
        }

        public boolean isPresent() {
            return value != null && !value.isEmpty();
        }

        public String normalizedFile() {
            return file.replace('\\', '/');
        }
    }

    public SecretManager(MineCICD plugin) {
        this.plugin = plugin;
        this.dataDir = plugin.getDataFolder().toPath();
    }

    public boolean hasSecrets() {
        return !mappings.isEmpty();
    }

    public List<SecretMapping> mapping() {
        return mappings;
    }

    public List<String> files() {
        List<String> out = new ArrayList<>();
        for (SecretMapping mapping : mappings) {
            if (!out.contains(mapping.file())) {
                out.add(mapping.file());
            }
        }
        return out;
    }

    public void load() {
        mappings.clear();
        File secretsFile = dataDir.resolve("secrets.yml").toFile();
        if (secretsFile.isFile()) {
            YamlConfiguration secrets = YamlConfiguration.loadConfiguration(secretsFile);
            for (String path : secrets.getKeys(false)) {
                if (!secrets.isConfigurationSection(path)) {
                    continue;
                }
                ConfigurationSection section = secrets.getConfigurationSection(path);
                String file = section.getString("file");
                if (file == null || file.isBlank()) {
                    continue;
                }
                for (String secretKey : section.getKeys(false)) {
                    if ("file".equals(secretKey)) {
                        continue;
                    }
                    String secretValue = section.getString(secretKey);
                    if (secretValue == null || secretValue.isEmpty()) {
                        continue;
                    }
                    mappings.add(new SecretMapping(file, secretKey, secretValue));
                }
            }
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
        Path attributes = plugin.serverRoot().resolve(ATTR_FILE);
        try {
            StringBuilder out = new StringBuilder();
            // append existing file
            if (Files.exists(attributes)) {
                String existing = Files.readString(attributes, StandardCharsets.UTF_8);
                int begin = existing.indexOf(FILTERS_BEGIN);
                int end = existing.indexOf(FILTERS_END);
                if (begin >= 0 && end > begin) {
                    out.append(existing, 0, begin);
                    out.append(existing.substring(end));
                } else {
                    out.append(existing);
                }
            }
            // Check Line Seperator
            if (out.length() > LINE_SEP.length() && out.lastIndexOf(LINE_SEP) < out.length() - LINE_SEP.length()) {
                out.append(LINE_SEP);
            }

            // append filters
            out.append(buildAttributesBlock(files()));
            Files.write(attributes, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to write .gitattributes: " + e.getMessage());
        }
    }

    /**
     * Builds the MineCICD-managed portion of .gitattributes: one
     * {@code <escaped-file> filter=minecicd-<hex>} line per secrets.yml file,
     * wrapped in the markers that {@code writeAttributes} uses to replace the
     * block on the next write.
     */
    static String buildAttributesBlock(List<String> files) {
        StringBuilder out = new StringBuilder();
        out.append(FILTERS_BEGIN).append(LINE_SEP);
        for (String file : files) {
            if (file != null && !file.isBlank()) {
                out.append(escapeAttr(normalized(file))).append(" filter=").append(driverNameFor(file))
                        .append(LINE_SEP);
            }
        }
        out.append(FILTERS_END).append(LINE_SEP);
        return out.toString();
    }

    private void writeGitConfig() {
        Path gitDir = plugin.serverRoot().resolve(GIT_DIR);
        if (!Files.isDirectory(gitDir)) {
            return;
        }
        Path config = gitDir.resolve(CONFIG_FILE);

        try {
            StringBuilder out = new StringBuilder();
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
            out.append(buildFilterConfigBlock(files()));
            Files.write(config, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
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
    static String buildFilterConfigBlock(List<String> files) {
        StringBuilder out = new StringBuilder();
        out.append(FILTERS_BEGIN).append(LINE_SEP);
        for (String file : files) {
            if (file == null || file.isBlank()) {
                continue;
            }
            out.append("[filter \"").append(driverNameFor(file)).append("\"]").append(LINE_SEP);
            out.append("\tclean = ").append(cleanKeyFor(file)).append(LINE_SEP);
            out.append("\tsmudge = ").append(smudgeKeyFor(file)).append(LINE_SEP);
            out.append("\trequired = false").append(LINE_SEP);
            out.append(LINE_SEP);
        }
        out.append(FILTERS_END).append(LINE_SEP);
        return out.toString();
    }

    /**
     * Removes everything MineCICD previously wrote into .git/config: the
     * {@code # MineCICD FILTERS BEGIN/END} marker block, the legacy
     * {@code [filter "minecicd"]} java -jar section, and any stale
     * {@code [filter "minecicd-*"]} builtin sections. Other sections are kept
     * byte-for-byte (line endings normalized by the caller).
     */
    static String stripStaleSections(String config) {
        String[] lines = config.split("\\r?\\n", -1);
        // split(..., -1) yields one trailing empty element for a final newline;
        // that element must not become an extra (growing) empty line on each pass.
        int last = lines.length;
        if (last > 0 && lines[last - 1].isEmpty()) {
            last--;
        }
        StringBuilder out = new StringBuilder();
        boolean skipToEndMarker = false;
        boolean skipToNextSection = false;
        for (int i = 0; i < last; i++) {
            String line = lines[i];
            String trimmed = line.trim();
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
            if (trimmed.equals(FILTERS_BEGIN)) {
                skipToEndMarker = true;
                continue;
            }
            if (isMineCicdSectionHeader(trimmed)) {
                skipToNextSection = true;
                continue;
            }
            out.append(line).append(LINE_SEP);
        }
        return out.toString();
    }

    private static boolean isMineCicdSectionHeader(String trimmed) {
        return trimmed.startsWith("[filter \"" + FILTER_NAME + "\"]")
                || trimmed.startsWith("[filter \"" + FILTER_NAME + "-");
    }

    /**
     * Registers one clean and one smudge JGit builtin filter per secrets.yml
     * file. Re-registration is safe: stale keys are unregistered first.
     */
    public void registerFilters() {
        unregisterFilters();
        for (String file : files()) {
            registerKey(cleanKeyFor(file),
                    new MineCicdFilterFactory(this::mapping, normalized(file), MineCicdFilterCommand.Direction.CLEAN));
            registerKey(smudgeKeyFor(file),
                    new MineCicdFilterFactory(this::mapping, normalized(file), MineCicdFilterCommand.Direction.SMUDGE));
        }
        if (!files().isEmpty()) {
            plugin.getLogger().info("Registered " + registeredFilterKeys.size() + " builtin replace filters.");
        }
    }

    /**
     * Unregisters all MineCicD JGit builtin filters. Idempotent; safe to call on
     * every reload and on plugin disable.
     */
    public void unregisterFilters() {
        for (String key : registeredFilterKeys) {
            FilterCommandRegistry.unregister(key);
        }
        registeredFilterKeys.clear();
    }

    private void registerKey(String key, MineCicdFilterFactory factory) {
        FilterCommandRegistry.register(key, factory);
        registeredFilterKeys.add(key);
    }

    /**
     * Deterministic, per-file filter driver name ({@code minecicd-<hex>} from
     * SHA-256 of the normalized path). The names are stable across restarts and
     * use only {@code [0-9a-f]}, which is safe in gitattributes attribute values.
     */
    public static String driverNameFor(String file) {
        String normalized = normalized(file);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return FILTER_NAME + "-" + hex;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JCA specification.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Registry key for the JGit builtin clean filter of a file. */
    public static String cleanKeyFor(String file) {
        return driverNameFor(file) + "-clean";
    }

    /** Registry key for the JGit builtin smudge filter of a file. */
    public static String smudgeKeyFor(String file) {
        return driverNameFor(file) + "-smudge";
    }

    private static String normalized(String file) {
        return file.replace('\\', '/');
    }

    private static String escapeAttr(String file) {
        return file.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}