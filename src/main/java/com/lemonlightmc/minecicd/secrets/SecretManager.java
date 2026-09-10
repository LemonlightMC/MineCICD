package com.lemonlightmc.minecicd.secrets;

import com.lemonlightmc.minecicd.MineCICD;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.eclipse.jgit.attributes.FilterCommandRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SecretManager {

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
            this(file.replace('\\', '/'), GitConfigWriter.filterNameFor(file.replace('\\', '/')));

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
        // JGit runs the transformation in-process via FilterCommandRegistry;
        GitConfigWriter.writeAttributes(plugin, files);
        GitConfigWriter.writeGitConfig(plugin, files);
        // Register the JGit builtin filters used by GitService. Note: registration
        // applies to JGit only; the git CLI skips the transformation on other machines
        // via required=false.
        registerFilters();
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

}