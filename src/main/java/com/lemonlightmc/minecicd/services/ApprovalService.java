package com.lemonlightmc.minecicd.services;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.Action;
import com.lemonlightmc.minecicd.data.Actor;
import com.lemonlightmc.minecicd.util.Threads;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

/**
 * Change-preview approvals. A pending pull stores its diff summary here; an
 * operator confirms or cancels it with {@code /minecicd confirm|cancel}.
 * Pending
 * approvals survive a restart - metadata is persisted in a single JSON store
 * under {@code plugins/MineCICD/approvals.json} and the deferred pull is
 * rebuilt
 * through the registered {@link RunFactory}.
 */
public class ApprovalService {

    /** Rebuilds the deferred pull for a persisted approval after a restart. */
    public interface RunFactory {
        Runnable create(Actor actor, String branch, boolean force);
    }

    public record Approval(String id, long createdAt, Actor actor, String branch, List<String> changes, boolean force,
            int timeoutSeconds) {
        JSONObject toJson() {
            return new JSONObject()
                    .put("id", id)
                    .put("createdAt", createdAt)
                    .put("actor", actor)
                    .put("branch", branch)
                    .put("force", force)
                    .put("timeoutSeconds", timeoutSeconds)
                    .put("changes", changes);
        }
    }

    private final Path file;
    private final Object lock = new Object();
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();
    private final Map<String, Runnable> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    private final AtomicBoolean sweeping = new AtomicBoolean();
    private volatile RunFactory runFactory;

    public ApprovalService() {
        this.file = MineCICDApi.dataFolder().resolve("approvals.json");
        this.sweeper = Threads.scheduledThreadExecutor("minecicd-approval-sweep");
    }

    public void setRunFactory(final RunFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("RunFactory cannot be null");
        }
        this.runFactory = factory;
        reloadRuns();
    }

    private void reloadRuns() {
        approvals.clear();
        runs.clear();
        synchronized (lock) {
            try {
                if (!Files.isRegularFile(file)) {
                    return;
                }
                final JSONArray arr = new JSONArray(Files.readString(file, StandardCharsets.UTF_8));
                for (int i = 0; i < arr.length(); i++) {
                    try {
                        final Approval approval = fromJson(arr.getJSONObject(i));
                        approvals.put(approval.id(), approval);
                        runs.put(approval.id(),
                                runFactory.create(approval.actor(), approval.branch(), approval.force()));
                    } catch (final Exception ignored) {
                    }
                }
            } catch (final Exception ignored) {
            }
        }
        startSweeper();
    }

    private void startSweeper() {
        if (sweeping.compareAndSet(false, true)) {
            sweeper.scheduleWithFixedDelay(this::sweepExpired, 30, 30, TimeUnit.SECONDS);
        }
    }

    public String create(final Actor actor, final String branch, final List<String> changes) {
        return create(actor, branch, changes, false);
    }

    public String create(final Actor actor, final String branch, final List<String> changes, final boolean force) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        while (approvals.containsKey(id)) {
            id = UUID.randomUUID().toString().substring(0, 8);
        }
        final Approval approval = new Approval(id, System.currentTimeMillis(), actor, branch, changes, force,
                MineCICDApi.config().approval().timeoutSeconds());
        approvals.put(id, approval);
        if (runFactory != null) {
            runs.put(id, runFactory.create(actor, branch, force));
        }
        save();
        MineCICDApi.auditService().log(actor,
                Action.APPROVAL_CREATED, true,
                changes.size() + " change(s) pending approval");
        return id;
    }

    public Approval get(final String id) {
        return id == null || id.isEmpty() ? null : approvals.get(id);
    }

    public int pendingCount() {
        return approvals.size();
    }

    /**
     * Confirms and executes a pending approval.
     *
     * @return {@code true} if the approval existed (it now runs)
     */
    public boolean confirm(final Actor actor, final String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        final Runnable run = runs.get(id);
        remove(id);
        MineCICDApi.auditService().log(actor, Action.APPROVAL_CONFIRM, true, id);
        if (run != null) {
            run.run();
        }
        return true;
    }

    public boolean cancel(final Actor actor, final String id) {
        if (id == null || !approvals.containsKey(id)) {
            return false;
        }
        remove(id);
        MineCICDApi.auditService().log(actor, Action.APPROVAL_CANCEL, true, id);
        return true;
    }

    private void remove(final String id) {
        approvals.remove(id);
        runs.remove(id);
        save();
    }

    private void save() {
        synchronized (lock) {
            final JSONArray arr = new JSONArray();
            for (final Approval approval : approvals.values()) {
                arr.put(approval.toJson());
            }
            try {
                Files.createDirectories(file.getParent());
                final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, arr.toString(2), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (final IOException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (final IOException e) {
                MineCICDApi.logger().warning("Unable to persist approvals: " + e.getMessage());
            }
        }
    }

    private void sweepExpired() {
        final long now = System.currentTimeMillis();
        boolean changed = false;
        for (final Approval approval : approvals.values()) {
            if (now - approval.createdAt() > approval.timeoutSeconds() * 1000L) {
                MineCICDApi.logger().info("Approval " + approval.id() + " expired; cancelling pending pull.");
                approvals.remove(approval.id());
                runs.remove(approval.id());
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private static Approval fromJson(final JSONObject j) {
        return new Approval(
                j.getString("id"),
                j.optLong("createdAt"),
                Actor.fromString(j.optString("actor", "")),
                j.optString("branch", ""),
                List.copyOf(j.optJSONArray("changes") == null ? List.<String>of()
                        : j.getJSONArray("changes").toList().stream().map(String::valueOf)
                                .toList()),
                j.optBoolean("force", false),
                j.optInt("timeoutSeconds", 120));
    }
}