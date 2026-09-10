package com.lemonlightmc.minecicd.approval;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.util.Threads;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Change-preview approvals. A pending pull stores its diff summary here; an
 * operator confirms or cancels it with {@code /minecicd confirm|cancel}. Pending
 * approvals survive a restart - metadata is persisted under
 * {@code plugins/MineCICD/approvals/} and the deferred pull is rebuilt through
 * the registered {@link RunFactory}.
 */
public class ApprovalStore {

    /** Rebuilds the deferred pull for a persisted approval after a restart. */
    public interface RunFactory {
        Runnable create(String source, String actor, String branch, boolean force);
    }

    public record Approval(String id, long createdAt, String source, String actor, String branch,
            boolean force, int timeoutSeconds, List<String> changes) {
    }

    /** Metadata that can be serialized; the runnable is rebuilt separately. */
    private record Meta(String id, long createdAt, String source, String actor, String branch, boolean force,
            int timeoutSeconds,
            List<String> changes) {
        JSONObject toJson() {
            return new JSONObject()
                    .put("id", id)
                    .put("createdAt", createdAt)
                    .put("source", source)
                    .put("actor", actor)
                    .put("branch", branch)
                    .put("force", force)
                    .put("timeoutSeconds", timeoutSeconds)
                    .put("changes", changes);
        }
    }

    private final MineCICD plugin;
    private final Path dir;
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final Map<String, Runnable> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    private final java.util.concurrent.atomic.AtomicBoolean sweeping = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile RunFactory runFactory;

    public ApprovalStore(final MineCICD plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("approvals");
        this.sweeper = Threads.scheduledThreadExecutor("minecicd-approval-sweep");
        reloadRuns();
    }

    public void setRunFactory(final RunFactory factory) {
        this.runFactory = factory;
        reloadRuns();
    }

    private void reloadRuns() {
        approvals.clear();
        runs.clear();
        try {
            if (!Files.isDirectory(dir)) {
                return;
            }
            try (var stream = Files.list(dir)) {
                for (final Path file : stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                    try {
                        final Meta meta = fromJson(Files.readString(file, StandardCharsets.UTF_8));
                        final Approval approval = new Approval(meta.id(), meta.createdAt(), meta.source(),
                                meta.actor(), meta.branch(), meta.force(), meta.timeoutSeconds(), meta.changes());
                        approvals.put(meta.id(), approval);
                        if (runFactory != null) {
                            runs.put(meta.id(), runFactory.create(approval.source(), approval.actor(),
                                    approval.branch(), approval.force()));
                        }
                    } catch (final Exception ignored) {
                    }
                }
            }
        } catch (final IOException ignored) {
        }
        startSweeper();
    }

    private void startSweeper() {
        if (sweeping.compareAndSet(false, true)) {
            sweeper.scheduleWithFixedDelay(this::sweepExpired, 30, 30, TimeUnit.SECONDS);
        }
    }

    public String create(final String source, final String actor, final String branch, final List<String> changes) {
        return create(source, actor, branch, false, changes);
    }

    public String create(final String source, final String actor, final String branch, final boolean force,
            final List<String> changes) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        while (approvals.containsKey(id)) {
            id = UUID.randomUUID().toString().substring(0, 8);
        }
        final int timeout = plugin.config().approval().timeoutSeconds();
        final Approval approval = new Approval(id, System.currentTimeMillis(), source, actor, branch, force, timeout,
                changes);
        approvals.put(id, approval);
        if (runFactory != null) {
            runs.put(id, runFactory.create(source, actor, branch, force));
        }
        persist(approval);
        return id;
    }

    public Approval get(final String id) {
        return id == null ? null : approvals.get(id);
    }

    public int pendingCount() {
        return approvals.size();
    }

    /**
     * Confirms and executes a pending approval.
     *
     * @return {@code true} if the approval existed (it now runs)
     */
    public boolean confirm(final String id) {
        if (id == null || !approvals.containsKey(id)) {
            return false;
        }
        final Runnable run = runs.get(id);
        remove(id);
        if (run != null) {
            run.run();
        }
        return true;
    }

    public boolean cancel(final String id) {
        if (id == null || !approvals.containsKey(id)) {
            return false;
        }
        remove(id);
        return true;
    }

    private void remove(final String id) {
        approvals.remove(id);
        runs.remove(id);
        try {
            Files.deleteIfExists(dir.resolve(id + ".json"));
        } catch (final IOException ignored) {
        }
    }

    private void persist(final Approval approval) {
        final Meta meta = new Meta(approval.id(), approval.createdAt(), approval.source(), approval.actor(),
                approval.branch(), approval.force(), approval.timeoutSeconds(), approval.changes());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(approval.id() + ".json"), meta.toJson().toString(2), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to persist approval " + approval.id() + ": " + e.getMessage());
        }
    }

    private void sweepExpired() {
        final long now = System.currentTimeMillis();
        for (final Approval approval : approvals.values()) {
            if (now - approval.createdAt() > approval.timeoutSeconds() * 1000L) {
                plugin.getLogger().info("Approval " + approval.id() + " expired; cancelling pending pull.");
                remove(approval.id());
            }
        }
    }

    private static Meta fromJson(final String json) {
        final JSONObject j = new JSONObject(json);
        return new Meta(
                j.getString("id"),
                j.optLong("createdAt"),
                j.optString("source", "manual"),
                j.optString("actor", ""),
                j.optString("branch", ""),
                j.optBoolean("force", false),
                j.optInt("timeoutSeconds", 120),
                List.copyOf(j.optJSONArray("changes") == null ? List.<String>of()
                        : j.getJSONArray("changes").toList().stream().map(String::valueOf).toList()));
    }
}