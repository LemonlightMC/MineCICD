package com.lemonlightmc.minecicd.services;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.git.GitConfigWriter;
import com.lemonlightmc.minecicd.secrets.MineCicdFilterCommand;
import com.lemonlightmc.minecicd.secrets.MineCicdFilterFactory;
import com.lemonlightmc.minecicd.util.Utils;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.eclipse.jgit.attributes.FilterCommandRegistry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SecretFilterService {

    public static final String FILTER_NAME = "minecicd";

    private final List<SecretFileEntry> files = new ArrayList<>();
    private final List<SecretMapping> mappings = new ArrayList<>();

    private final Set<String> registeredFilterKeys = new HashSet<>();
    private final Set<String> secrets = new HashSet<>();

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
            this(file.replace('\\', '/'), filterNameFor(file.replace('\\', '/')));

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

    /**
     * Deterministic, per-file filter driver name ({@code minecicd-<hex>} from
     * SHA-256 of the normalized path). The names are stable across restarts and
     * use only {@code [0-9a-f]}, which is safe in gitattributes attribute values.
     */
    private static String filterNameFor(final String file) {
        return FILTER_NAME + "-" + Utils.sha256Hex(file.getBytes(StandardCharsets.UTF_8));
    }

    public SecretFilterService() {
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

    public Set<String> secrets() {
        return secrets;
    }

    public String redact(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        if (secrets == null) {
            return message;
        }
        for (final String secret : secrets) {
            if (secret != null && secret.length() >= 4) {
                message = message.replace(secret, "***");
            }
        }
        return message;
    }

    public void collectSecrets() {
        secrets.clear();
        try {
            addSecret(MineCICDApi.config().git() == null ? null
                    : MineCICDApi.config().git().pass());
            addSecret(MineCICDApi.config().control() == null ? null
                    : MineCICDApi.config().control().secret());
            addSecret(MineCICDApi.config().control().githubWebhook() == null ? null
                    : MineCICDApi.config().control().githubWebhook().secret());
            addSecret(MineCICDApi.config().discord() == null ? null
                    : MineCICDApi.config().discord().url());
        } catch (final Exception ignored) {
        }
    }

    private void addSecret(final String value) {
        if (value != null && value.length() >= 4) {
            secrets.add(value);
        }
    }

    public void load() {
        mappings.clear();
        files.clear();
        final File secretsFile = MineCICDApi.dataFolder().resolve("secrets.yml").toFile();
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
        GitConfigWriter.writeAttributes(files);
        GitConfigWriter.writeGitConfig(files);
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
            MineCICDApi.logger().info("Registered " + registeredFilterKeys.size() + " builtin replace filters.");
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