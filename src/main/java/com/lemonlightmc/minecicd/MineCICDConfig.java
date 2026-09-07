package com.lemonlightmc.minecicd;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import com.lemonlightmc.minecicd.git.CommitActions.ActionType;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Typed, immutable view of config.yml. The plugin loads/patches the raw YAML on
 * {@link #load()} (migrating old {@code webhooks:*} keys, backfilling missing
 * keys,
 * and bumping supported defaults) and then exposes records to the rest of the
 * system.
 */
public class MineCICDConfig {

    public record Git(String user, String pass, String repo, String email, String branch, String serverRoot) {
    }

    public record BossBar(boolean enabled, int durationTicks) {
    }

    public record Tls(boolean enabled, String keystore, String password) {
    }

    public record RateLimit(boolean enabled, boolean failuresOnly, int failureLimit, int maxEntries,
            long windowSeconds) {
    }

    public record Actions(EnumSet<ActionType> allowedActions, Set<String> allowedCommands, Set<String> allowedScripts) {

        public static Actions from(final ConfigurationSection section) {
            final EnumSet<ActionType> set = EnumSet.noneOf(ActionType.class);
            if (section.getBoolean("pull", true)) {
                set.add(ActionType.PULL);
            }
            if (section.getBoolean("push", true)) {
                set.add(ActionType.PUSH);
            }
            if (section.getBoolean("restart", true)) {
                set.add(ActionType.RESTART);
            }
            if (section.getBoolean("global-reload", false)) {
                set.add(ActionType.GLOBAL_RELOAD);
            }
            if (section.getBoolean("reload-plugins", true)) {
                set.add(ActionType.RELOAD_PLUGIN);
            }
            if (section.getBoolean("scripts.enabled", true)) {
                set.add(ActionType.SCRIPT);
            }
            if (section.getBoolean("commands.enabled", true)) {
                set.add(ActionType.COMMAND);
            }
            return new Actions(set, Set.copyOf(section.getStringList("commands.allow")),
                    Set.copyOf(section.getStringList("scripts.allow")));
        }
    }

    public record Control(String host, int port, String path, String secret, Tls tls,
            String pushMessage, List<String> branches, long maxBodyBytes,
            long replayWindowSeconds, Actions actions, RateLimit rateLimit) {
    }

    private final MineCICD plugin;
    private YamlConfiguration config;
    private Git git;
    private BossBar bossBar;
    private Control control;
    private boolean experimentalJarLoading;

    public MineCICDConfig(final MineCICD plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        final File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveResource("config.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in != null) {
                final YamlConfiguration defaults = YamlConfiguration
                        .loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                patchConfig(file, defaults);
                config = YamlConfiguration.loadConfiguration(file);
            }
        } catch (final Exception e) {
            plugin.getLogger().warning("Unable to load bundled config.yml: " + e.getMessage());
        }
        try {
            config.save(file);
            hardenPermissions(file.toPath());
        } catch (final Exception e) {
            plugin.getLogger().warning("Unable to save config.yml: " + e.getMessage());
        }
        readRecords();
        warnIfWorldReadable(file.toPath());
    }

    private void patchConfig(final File file, final YamlConfiguration defaults) {
        boolean changed = false;
        // migrate old webhooks:* keys to control:* (if control block is absent) then
        // drop webhooks
        if (config.contains("webhooks")) {
            final ConfigurationSection webhooks = config.getConfigurationSection("webhooks");
            if (webhooks != null) {
                if (!config.contains("control.port")) {
                    config.set("control.port", webhooks.getInt("port", 0));
                }
                if (!config.contains("control.path")) {
                    config.set("control.path", webhooks.getString("path", "minecicd"));
                }
            }
            config.set("webhooks", null);
            changed = true;
        }
        for (final String key : defaults.getKeys(true)) {
            if (!config.contains(key, true)) {
                config.set(key, defaults.get(key));
                changed = true;
            }
        }
        changed |= ensureActionList("control.actions.commands");
        changed |= ensureActionList("control.actions.scripts");
        if (changed) {
            try {
                config.save(file);
                hardenPermissions(file.toPath());
            } catch (final Exception e) {
                plugin.getLogger().warning("Unable to save config.yml: " + e.getMessage());
            }
        }
    }

    private boolean ensureActionList(final String base) {
        if (config.contains(base + ".allow")) {
            return false;
        }
        config.set(base + ".allow", new ArrayList<>(List.of()));
        return true;
    }

    private static void hardenPermissions(final java.nio.file.Path file) {
        try {
            final Set<PosixFilePermission> set = EnumSet.noneOf(PosixFilePermission.class);
            set.add(PosixFilePermission.OWNER_READ);
            set.add(PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, set);
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            try {
                final java.io.File f = file.toFile();
                f.setReadable(false, false);
                f.setWritable(false, false);
                f.setExecutable(false, false);
                f.setReadable(true, true);
                f.setWritable(true, true);
            } catch (final Exception ignored2) {
            }
        }
    }

    private void warnIfWorldReadable(final java.nio.file.Path file) {
        try {
            final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.GROUP_READ)) {
                plugin.getLogger().warning(file.getFileName() + " is world-readable; run chmod 600 " + file);
            }
        } catch (final Exception ignored) {
        }
    }

    private void readRecords() {
        final ConfigurationSection gitSection = config.getConfigurationSection("git");
        this.git = new Git(
                gitSection.getString("user", ""),
                gitSection.getString("pass", ""),
                gitSection.getString("repo", ""),
                gitSection.getString("email", "minecicd@minecicd.local"),
                gitSection.getString("branch", "master"),
                gitSection.getString("server-root", ""));

        final ConfigurationSection barSection = config.getConfigurationSection("bossbar");
        this.bossBar = new BossBar(barSection.getBoolean("enabled", true), barSection.getInt("duration", 100));
        this.experimentalJarLoading = config.getBoolean("experimental-jar-loading", false);

        final ConfigurationSection controlSection = config.getConfigurationSection("control");
        final ConfigurationSection tls = controlSection.getConfigurationSection("tls");
        this.control = new Control(
                controlSection.getString("host", "127.0.0.1"),
                controlSection.getInt("port", 0),
                controlSection.getString("path", "minecicd"),
                controlSection.getString("secret", ""),
                new Tls(tls.getBoolean("enabled", false), tls.getString("keystore", ""), tls.getString("password", "")),
                controlSection.getString("push-message", "Auto-commit by MineCICD"),
                new ArrayList<>(controlSection.getStringList("branches")),
                controlSection.getLong("max-body-bytes", 65536),
                controlSection.getLong("replay-window-seconds", 300),
                Actions.from(controlSection.getConfigurationSection("actions")),
                new RateLimit(
                        controlSection.getBoolean("rate-limit.enabled", true),
                        controlSection.getBoolean("rate-limit.failures-only", true),
                        controlSection.getInt("rate-limit.failure-limit", 5),
                        controlSection.getInt("rate-limit.max-entries", 1000),
                        controlSection.getLong("rate-limit.window-seconds", 60000)));
    }

    public Git git() {
        return git;
    }

    public BossBar bossBar() {
        return bossBar;
    }

    public Control control() {
        return control;
    }

    public boolean experimentalJarLoading() {
        return experimentalJarLoading;
    }
}