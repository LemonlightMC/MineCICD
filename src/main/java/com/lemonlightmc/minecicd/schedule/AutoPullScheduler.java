package com.lemonlightmc.minecicd.schedule;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.events.DeploymentEvents;
import com.lemonlightmc.minecicd.events.DeploymentEvents.Type;
import com.lemonlightmc.minecicd.util.Threads;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Simple-interval auto pull. Runs {@link com.lemonlightmc.minecicd.CicdService}
 * through the same worker queue, respects quiet hours, skips when the control
 * API is busy, and suspends itself after {@code max-consecutive-failures}
 * failures (failures are persisted so a restart does not silently re-arm a
 * broken schedule).
 */
public class AutoPullScheduler {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final MineCICD plugin;
    private final ScheduledExecutorService scheduler;
    private final Path stateFile;
    private ScheduledFuture<?> future;
    private volatile int consecutiveFailures;
    private volatile boolean suspended;
    private volatile long nextRunAt = -1;
    private volatile String lastRunMessage = "";

    public AutoPullScheduler(final MineCICD plugin) {
        this.plugin = plugin;
        this.scheduler = Threads.scheduledThreadExecutor("minecicd-auto-pull");
        this.stateFile = plugin.getDataFolder().toPath().resolve("auto-pull-state.json");
        loadState();
    }

    public void start() {
        // an explicit start (enable/reload) clears a suspension so the admin can re-arm
        suspended = false;
        persistState();
        reschedule();
    }

    public void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }

    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    private void reschedule() {
        stop();
        if (!plugin.config().autoPull().enabled()) {
            nextRunAt = -1;
            return;
        }
        final long intervalMillis = plugin.config().autoPull().intervalMinutes() * 60_000L;
        nextRunAt = System.currentTimeMillis() + intervalMillis;
        future = scheduler.scheduleWithFixedDelay(this::tick, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        plugin.getLogger().info("Auto-pull scheduled every " + plugin.config().autoPull().intervalMinutes()
                + " minutes (quiet hours enabled: "
                + plugin.config().autoPull().quietHours().enabled() + ")");
    }

    private void tick() {
        try {
            if (suspended || !plugin.config().autoPull().enabled()) {
                return;
            }
            if (InQuietHours.check(plugin.config().autoPull().quietHours(), LocalTime.now())) {
                nextRunAt = nextDelayMillis();
                return;
            }
            if (plugin.cicdService().isBusy()) {
                lastRunMessage = "skipped (another operation in flight)";
                return;
            }
            final long start = System.currentTimeMillis();
            final boolean ok = plugin.cicdService().pullScheduled().join();
            nextRunAt = start + nextDelayMillis();
            if (ok) {
                consecutiveFailures = 0;
                lastRunMessage = "ok";
            } else {
                consecutiveFailures++;
                lastRunMessage = "failed (" + consecutiveFailures + " consecutive)";
            }
            if (consecutiveFailures > 0 && consecutiveFailures >= plugin.config().autoPull().maxConsecutiveFailures()) {
                suspended = true;
                plugin.getLogger().severe("Auto-pull suspended after " + consecutiveFailures
                        + " consecutive failures. Run /minecicd reload to re-arm.");
                plugin.events().emit(Type.DEPLOY_FAILED, "scheduler", "scheduler",
                        "Auto-pull suspended after " + consecutiveFailures + " consecutive failures", null, null, 0L);
            }
            persistState();
        } catch (final Exception e) {
            consecutiveFailures++;
            lastRunMessage = "failed (" + consecutiveFailures + " consecutive): " + e.getMessage();
            try {
                plugin.getLogger().warning("Auto-pull tick failed: " + e.getMessage());
                persistState();
            } catch (final Exception ignored) {
            }
        }
    }

    private long nextDelayMillis() {
        return plugin.config().autoPull().intervalMinutes() * 60_000L;
    }

    public State state() {
        return new State(plugin.config().autoPull().enabled(), suspended, consecutiveFailures, nextRunAt, lastRunMessage);
    }

    public record State(boolean enabled, boolean suspended, int consecutiveFailures, long nextRunAt,
            String lastRunMessage) {
        public String nextRunText() {
            if (suspended) {
                return "suspended";
            }
            if (!enabled) {
                return "disabled";
            }
            if (nextRunAt < 0) {
                return "pending";
            }
            return TIME.format(LocalTime.ofInstant(java.time.Instant.ofEpochMilli(nextRunAt),
                    java.time.ZoneId.systemDefault()));
        }
    }

    private void loadState() {
        try {
            if (!Files.isRegularFile(stateFile)) {
                return;
            }
            final JSONObject j = new JSONObject(Files.readString(stateFile, StandardCharsets.UTF_8));
            consecutiveFailures = j.optInt("consecutiveFailures", 0);
            suspended = j.optBoolean("suspended", false);
            if (consecutiveFailures == 0) {
                suspended = false;
            }
        } catch (final Exception ignored) {
        }
    }

    private void persistState() {
        try {
            Files.createDirectories(stateFile.getParent());
            Files.writeString(stateFile, new JSONObject()
                    .put("consecutiveFailures", consecutiveFailures)
                    .put("suspended", suspended).toString(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to persist auto-pull state: " + e.getMessage());
        }
    }

    /** Quiet-hours window check (24h "HH:mm"). A from >= to wraps past midnight. */
    public static final class InQuietHours {

        private InQuietHours() {
        }

        public static boolean check(final com.lemonlightmc.minecicd.MineCICDConfig.QuietHours quiet,
                final LocalTime now) {
            if (quiet == null || !quiet.enabled()) {
                return false;
            }
            final LocalTime from = parse(quiet.from());
            final LocalTime to = parse(quiet.to());
            if (from == null || to == null) {
                return false;
            }
            if (from.isBefore(to)) {
                return !now.isBefore(from) && now.isBefore(to);
            }
            // wraps midnight: e.g. 22:00 -> 02:00
            return !now.isBefore(from) || now.isBefore(to);
        }

        private static LocalTime parse(final String value) {
            try {
                return LocalTime.parse(value == null ? "" : value.trim(), TIME);
            } catch (final DateTimeParseException e) {
                return null;
            }
        }
    }
}