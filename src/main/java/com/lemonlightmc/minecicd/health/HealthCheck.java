package com.lemonlightmc.minecicd.health;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.exceptions.ScriptException;
import com.lemonlightmc.minecicd.util.Threads;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    private final ExecutorService executor;

    public HealthCheck(final MineCICD plugin) {
        this.plugin = plugin;
        this.executor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "minecicd-health-check");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean enabled() {
        return plugin.config().healthCheck().enabled();
    }

    /**
     * Runs all configured checks.
     *
     * @param timeoutSeconds overall timeout; a check that exceeds it fails
     * @return the first failing reason, or a pass result
     */
    public Result check(final long timeoutSeconds) {
        final Future<Result> future = executor.submit(this::runChecks);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (final TimeoutException e) {
            future.cancel(true);
            return Result.fail("health check timed out after " + timeoutSeconds + "s");
        } catch (final ExecutionException | InterruptedException e) {
            return Result.fail("health check errored: " + rootMessage(e));
        }
    }

    private Result runChecks() {
        final var cfg = plugin.config().healthCheck();

        if (cfg.script() != null && !cfg.script().isBlank()) {
            try {
                plugin.scriptManager().run(cfg.script(), null, line -> {
                });
            } catch (final ScriptException e) {
                return Result.fail("health script '" + cfg.script() + "' failed: " + rootMessage(e));
            } catch (final Exception e) {
                return Result.fail("health script '" + cfg.script() + "' errored: " + rootMessage(e));
            }
        }

        if (cfg.command() != null && !cfg.command().isBlank()) {
            final String command = cfg.command().startsWith("/") ? cfg.command().substring(1) : cfg.command();
            Threads.marshaled(plugin, () -> plugin.getServer().dispatchCommand(
                    org.bukkit.Bukkit.getConsoleSender(), command));
        }

        for (final String name : cfg.requirePlugins()) {
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

    public void shutdown() {
        executor.shutdownNow();
    }

    private static String rootMessage(final Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}