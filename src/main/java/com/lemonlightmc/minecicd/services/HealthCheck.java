package com.lemonlightmc.minecicd.services;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.exceptions.ScriptException;
import com.lemonlightmc.minecicd.messaging.Messages;
import com.lemonlightmc.minecicd.util.Threads;

/**
 * Post-deploy health check. Supports a script (nonzero exit / error fails), an
 * optional console command, and internal plugin-state checks (required plugins
 * must be loaded and enabled). Runs off the worker queue on its own executor;
 * a timeout yields a failure.
 */
public class HealthCheck {

    public record Result(boolean ok, String message) {
        public static Result pass() {
            return new Result(true, "ok");
        }

        public static Result fail(final String message) {
            return new Result(false, message == null ? "health check failed" : message);
        }
    }

    private final MineCICD plugin;
    private final com.lemonlightmc.minecicd.MineCICDConfig.HealthCheck config;

    public HealthCheck(final MineCICD plugin) {
        this.plugin = plugin;
        this.config = plugin.config().healthCheck();

    }

    public boolean enabled() {
        return plugin.config().healthCheck().enabled();
    }

    public boolean runAfterRestart() {
        return plugin.config().healthCheck().enabled() && plugin.config().healthCheck().runsAfterRestart();
    }

    public boolean runAfterAction() {
        return plugin.config().healthCheck().enabled() && plugin.config().healthCheck().runInsideActions();
    }

    /**
     * Runs all configured checks.
     *
     * @param timeoutSeconds overall timeout; a check that exceeds it fails
     * @return the first failing reason, or a pass result
     */
    public Result check() {
        if (!config.enabled()) {
            return Result.pass();
        }
        if (config.script() != null) {
            try {
                plugin.scriptManager().run(config.script(), null, line -> {
                });
            } catch (final ScriptException e) {
                return Result.fail("health script '" + config.script() + "' failed: " + Messages.rootMessage(e));
            } catch (final Exception e) {
                return Result.fail("health script '" + config.script() + "' errored: " + Messages.rootMessage(e));
            }
        }

        if (config.command() != null) {
            Threads.marshaled(plugin, () -> plugin.getServer().dispatchCommand(
                    org.bukkit.Bukkit.getConsoleSender(), config.command()));
        }

        for (final String name : config.requirePlugins()) {
            if (name == null || name.isBlank()) {
                continue;
            }
            final org.bukkit.plugin.Plugin p = plugin.getServer().getPluginManager().getPlugin(name);
            if (p == null) {
                return Result.fail("required plugin not found: " + name);
            }
            if (!plugin.getServer().getPluginManager().isPluginEnabled(name)) {
                return Result.fail("required plugin not loaded: " + name);
            }
        }
        return Result.pass();
    }

}