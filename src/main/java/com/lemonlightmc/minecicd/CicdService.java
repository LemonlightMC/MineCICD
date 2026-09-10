package com.lemonlightmc.minecicd;

import com.lemonlightmc.minecicd.analytics.Analytics;
import com.lemonlightmc.minecicd.audit.AuditLogger;
import com.lemonlightmc.minecicd.errors.ErrorCatalog;
import com.lemonlightmc.minecicd.events.DeploymentEvents;
import com.lemonlightmc.minecicd.events.DeploymentEvents.Type;
import com.lemonlightmc.minecicd.exceptions.GitException;
import com.lemonlightmc.minecicd.exceptions.ScriptException;
import com.lemonlightmc.minecicd.git.CommitActions;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;
import com.lemonlightmc.minecicd.git.Results;
import com.lemonlightmc.minecicd.health.HealthCheck;
import com.lemonlightmc.minecicd.http.ControlServer;
import com.lemonlightmc.minecicd.http.ControlSecurity;
import com.lemonlightmc.minecicd.http.ControlStatus;
import com.lemonlightmc.minecicd.http.ProgressStream;
import com.lemonlightmc.minecicd.messaging.Messages;
import com.lemonlightmc.minecicd.pending.PendingRequest;
import com.lemonlightmc.minecicd.pending.PendingRequest.Status;
import com.lemonlightmc.minecicd.schedule.AutoPullScheduler;
import com.lemonlightmc.minecicd.util.Threads;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates git operations, feedback, and the control API request queue. All
 * repo/disk/network work runs on a single worker thread; Bukkit API touches are
 * marshaled to the server main thread. Deploy lifecycle events are emitted to
 * the plugin's {@link DeploymentEvents} bus (audit log, analytics, Discord).
 */
public class CicdService implements ControlServer.Delegate {

    private final MineCICD plugin;
    private final ControlStatus controlStatus = new ControlStatus();
    private final Map<String, ProgressStream> streams = new ConcurrentHashMap<>();
    private final ExecutorService worker;
    private volatile boolean serverStarted = false;
    private final Object resumeLock = new Object();
    private final AtomicReference<String> inFlight = new AtomicReference<>(null);

    public CicdService(final MineCICD plugin) {
        this.plugin = plugin;
        this.worker = Threads.singleDaemonWorker("minecicd-worker");
        plugin.approvalStore().setRunFactory(
                (source, actor, branch, force) -> () -> doPull(null, force, source, actor));
    }

    public <T> CompletableFuture<T> enqueue(final java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, worker);
    }

    // ------------------------------------------------------------------ commands

    public CompletableFuture<Boolean> init(final CommandSender sender) {
        return enqueue(() -> {
            try {
                final boolean created = plugin.gitService().init();
                if (created) {
                    plugin.messages().send(sender, "init-success");
                } else {
                    plugin.messages().send(sender, "init-already-initialized");
                }
                plugin.bossBars().show("init", Map.of());
                plugin.auditLogger().log(actorOf(sender), "command", "init", true, null);
                return Boolean.TRUE;
            } catch (final Exception e) {
                plugin.auditLogger().log(actorOf(sender), "command", "init", false, safeMessage(e));
                fail(sender, "init-failed", e);
                plugin.bossBars().show("init-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> deinit(final CommandSender sender) {
        return enqueue(() -> {
            try {
                final boolean removed = plugin.gitService().deinit();
                if (removed) {
                    plugin.messages().send(sender, "deinit-success");
                } else {
                    plugin.messages().send(sender, "deinit-not-initialized");
                }
                plugin.bossBars().show("deinit", Map.of());
                plugin.auditLogger().log(actorOf(sender), "command", "deinit", true, null);
                return Boolean.TRUE;
            } catch (final Exception e) {
                plugin.auditLogger().log(actorOf(sender), "command", "deinit", false, safeMessage(e));
                fail(sender, "deinit-failed", e);
                plugin.bossBars().show("deinit-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> pull(final CommandSender sender, final boolean force) {
        return pull(sender, force, "manual", actorOf(sender));
    }

    /**
     * Pull with an explicit trigger source ("manual"/"scheduler"/"webhook") and
     * actor. Honors the change-approval gate when enabled for that source.
     */
    public CompletableFuture<Boolean> pull(final CommandSender sender, final boolean force,
            final String source, final String actor) {
        return enqueue(() -> {
            final var cfg = plugin.config().approval();
            if (cfg != null && cfg.enabled() && cfg.requireOn().contains(source)) {
                return gatePull(sender, force, source, actor);
            }
            return doPull(sender, force, source, actor);
        });
    }

    /** Scheduled auto-pull entry (actor/source "scheduler"). */
    public CompletableFuture<Boolean> pullScheduled() {
        return pull(Bukkit.getConsoleSender(), false, "scheduler", "scheduler");
    }

    private boolean gatePull(final CommandSender sender, final boolean force, final String source,
            final String actor) {
        final var cfg = plugin.config().approval();
        final List<String> changes;
        try {
            changes = plugin.gitService().pullPreview();
        } catch (final Exception e) {
            plugin.events().emit(Type.DEPLOY_FAILED, actor, source, safeMessage(e));
            fail(sender, "pull-failed", e);
            return false;
        }
        if (cfg != null && cfg.skipIfNoChanges() && changes.isEmpty()) {
            return doPull(sender, force, source, actor);
        }
        final String id = plugin.approvalStore().create(source, actor, plugin.config().git().branch(), force, changes);
        plugin.events().emit(Type.DEPLOY_STARTED, actor, source,
                "Pull pending approval " + id + " (" + changes.size() + " change(s))");
        if (sender != null) {
            if (changes.isEmpty()) {
                plugin.messages().send(sender, "approval-created-no-changes",
                        Map.of("id", id, "timeout", String.valueOf(cfg == null ? 120 : cfg.timeoutSeconds())));
            } else {
                plugin.messages().send(sender, "approval-created",
                        Map.of("id", id, "count", String.valueOf(changes.size()),
                                "timeout", String.valueOf(cfg == null ? 120 : cfg.timeoutSeconds())));
                for (final String change : changes) {
                    plugin.messages().sendRaw(sender,
                            plugin.messages().get("approval-change", Map.of("change", Messages.escape(change))));
                }
                plugin.messages().send(sender, "approval-confirm-hint", Map.of("id", id));
            }
        }
        plugin.auditLogger().log(actor, source, "approval-created", true,
                changes.size() + " change(s) pending approval");
        return true;
    }

    private boolean doPull(final CommandSender sender, final boolean force, final String source,
            final String actor) {
        final long start = System.currentTimeMillis();
        try {
            plugin.bossBars().show("pulling", Map.of());
            plugin.events().emit(Type.DEPLOY_STARTED, actor, source, source + " pull started",
                    null, plugin.config().git().branch(), 0L);
            final Results.PullResult result = plugin.gitService().pull(force);
            final boolean changed = result.initialized() || !result.commits().isEmpty();
            if (changed) {
                processCommitActions(result.commits());
            }
            if (result.initialized()) {
                plugin.messages().send(sender, "pull-success");
                plugin.bossBars().show("pulled-changes", Map.of());
            } else if (changed) {
                plugin.messages().send(sender, "pull-success");
                plugin.bossBars().show("pulled-changes", Map.of());
            } else {
                plugin.bossBars().show("pulled-no-changes", Map.of());
                plugin.messages().send(sender, "pull-no-changes");
                plugin.auditLogger().log(actor, source, "pull", true, "no changes to pull");
                return true;
            }

            final long duration = System.currentTimeMillis() - start;
            plugin.events().emit(Type.DEPLOY_COMPLETED, actor, source,
                    "pull applied " + result.commits().size() + " commit(s)", null,
                    plugin.config().git().branch(), duration);
            plugin.auditLogger().log(actor, source, "pull", true,
                    "applied " + result.commits().size() + " commit(s)");

            if (plugin.healthCheck().enabled() && plugin.config().healthCheck().runInsideActions()) {
                final HealthCheck.Result hc = plugin.healthCheck()
                        .check(plugin.config().healthCheck().timeoutSeconds());
                if (!hc.ok()) {
                    final long hcDuration = System.currentTimeMillis() - start;
                    plugin.events().emit(Type.DEPLOY_FAILED, actor, "health", hc.message(), null,
                            plugin.config().git().branch(), hcDuration);
                    rollbackDeploy(sender, actor, hc.message(), source);
                    plugin.messages().send(sender, "health-check-failed", Map.of("error", Messages.escape(hc.message())));
                    return false;
                }
            }
            return true;
        } catch (final GitException.PullAborted e) {
            plugin.messages().send(sender, "pull-aborted");
            plugin.bossBars().show("pull-aborted-changes", Map.of());
            plugin.auditLogger().log(actor, source, "pull", false, "aborted (unpushed local changes)");
            return false;
        } catch (final Exception e) {
            final long duration = System.currentTimeMillis() - start;
            plugin.events().emit(Type.DEPLOY_FAILED, actor, source, safeMessage(e), null,
                    plugin.config().git().branch(), duration);
            fail(sender, "pull-failed", e);
            plugin.bossBars().show("pull-failed", Map.of());
            return false;
        }
    }

    private void rollbackDeploy(final CommandSender sender, final String actor, final String reason,
            final String source) {
        try {
            final boolean ok = plugin.gitService().rollbackDeploy();
            if (ok) {
                plugin.events().emit(Type.ROLLBACK_EXECUTED, actor, source, "Auto-rollback after: " + reason);
                plugin.auditLogger().log(actor, source, "auto-rollback", true, reason);
                if (sender != null) {
                    plugin.messages().send(sender, "auto-rolled-back");
                }
                if (plugin.config().healthCheck().autoRollback().restartAfter()) {
                    scheduleRestart(null);
                }
            } else {
                plugin.getLogger().severe(
                        "Health check failed but rollback impossible (no parent commit): " + reason);
            }
        } catch (final Exception e) {
            plugin.getLogger().severe("Auto-rollback failed: " + safeMessage(e));
        }
    }

    private void processCommitActions(final List<org.eclipse.jgit.revwalk.RevCommit> commits) {
        if (commits == null || commits.isEmpty()) {
            return;
        }
        for (final org.eclipse.jgit.revwalk.RevCommit commit : commits) {
            for (final Action action : CommitActions.parseCommitMessage(commit)) {
                if (action.type() == ActionType.PULL) {
                    continue;
                }
                // gate commit actions through same policy as HTTP control API
                try {
                    if (plugin.security() != null) {
                        plugin.security().validateActions(List.of(action));
                    }
                } catch (final ControlSecurity.RejectException e) {
                    plugin.getLogger().warning(
                            "Skipping commit action disallowed by policy: " + action + " (" + e.getMessage() + ")");
                    continue;
                }
                plugin.getLogger().info("Running commit action: " + action);
                executeAction(action, plugin.config().git().branch(), null, "commit-action");
            }
        }
    }

    public CompletableFuture<Boolean> push(final CommandSender sender, final String message) {
        return enqueue(() -> {
            try {
                plugin.bossBars().show("pushing", Map.of());
                final Results.PushResult result = plugin.gitService().push(message);
                plugin.auditLogger().log(actorOf(sender), "command", "push", true,
                        message + (result.hadChanges() ? "" : " (no changes)"));
                if (result.hadChanges()) {
                    plugin.messages().send(sender, "push-success");
                    plugin.bossBars().show("pushed", Map.of());
                } else {
                    plugin.bossBars().show("push-no-changes", Map.of());
                    plugin.messages().send(sender, "push-no-changes");
                }
                return Boolean.TRUE;
            } catch (final Exception e) {
                plugin.auditLogger().log(actorOf(sender), "command", "push", false, safeMessage(e));
                fail(sender, "push-failed", e);
                plugin.bossBars().show("push-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> add(final CommandSender sender, final String path) {
        return enqueue(() -> {
            try {
                final int amount = plugin.gitService().addToTracking(path);
                plugin.messages().send(sender, "add-success", Map.of("amount", String.valueOf(amount)));
                plugin.bossBars().show("added", Map.of("amount", String.valueOf(amount)));
                plugin.auditLogger().log(actorOf(sender), "command", "add", true, path);
                return Boolean.TRUE;
            } catch (final Exception e) {
                plugin.auditLogger().log(actorOf(sender), "command", "add", false, path + ": " + safeMessage(e));
                fail(sender, "add-failed", e);
                plugin.bossBars().show("adding-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> remove(final CommandSender sender, final String path) {
        return enqueue(() -> {
            try {
                final int amount = plugin.gitService().removeFromTracking(path);
                plugin.messages().send(sender, "remove-success", Map.of("amount", String.valueOf(amount)));
                plugin.bossBars().show("removed", Map.of("amount", String.valueOf(amount)));
                plugin.auditLogger().log(actorOf(sender), "command", "remove", true, path);
                return Boolean.TRUE;
            } catch (final Exception e) {
                plugin.auditLogger().log(actorOf(sender), "command", "remove", false, path + ": " + safeMessage(e));
                fail(sender, "remove-failed", e);
                plugin.bossBars().show("removing-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> reset(final CommandSender sender, final String commit) {
        return enqueue(() -> {
            try {
                plugin.gitService().reset(commit);
                plugin.messages().send(sender, "reset-success");
                plugin.bossBars().show("reset", Map.of());
                plugin.auditLogger().log(actorOf(sender), "command", "reset", true, commit);
                return Boolean.TRUE;
            } catch (final Exception e) {
                plugin.auditLogger().log(actorOf(sender), "command", "reset", false, commit + ": " + safeMessage(e));
                fail(sender, "reset-failed", e);
                plugin.bossBars().show("reset-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> revert(final CommandSender sender, final String commit) {
        return enqueue(() -> {
            try {
                plugin.gitService().revert(commit);
                plugin.messages().send(sender, "revert-success");
                plugin.bossBars().show("reverted", Map.of());
                plugin.auditLogger().log(actorOf(sender), "command", "revert", true, commit);
                return Boolean.TRUE;
            } catch (final Exception e) {
                plugin.auditLogger().log(actorOf(sender), "command", "revert", false, commit + ": " + safeMessage(e));
                fail(sender, "revert-failed", e);
                plugin.bossBars().show("revert-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> rollback(final CommandSender sender, final String date) {
        return enqueue(() -> {
            try {
                plugin.gitService().rollback(date);
                plugin.messages().send(sender, "rollback-success");
                plugin.bossBars().show("reset", Map.of());
                plugin.auditLogger().log(actorOf(sender), "command", "rollback", true, date);
                return Boolean.TRUE;
            } catch (final Exception e) {
                final String message = safeMessage(e);
                plugin.auditLogger().log(actorOf(sender), "command", "rollback", false, date + ": " + message);
                fail(sender, "rollback-failed", e);
                plugin.bossBars().show("reset-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Results.LogPage> log(final CommandSender sender, final int page) {
        return enqueue(() -> plugin.gitService().log(page));
    }

    public CompletableFuture<Results.LogEntry> commit(final CommandSender sender, final String ref) {
        return enqueue(() -> plugin.gitService().getCommit(ref));
    }

    public CompletableFuture<Results.StatusInfo> status(final CommandSender sender) {
        return enqueue(() -> plugin.gitService().status());
    }

    public CompletableFuture<List<String>> diff(final CommandSender sender, final boolean remote) {
        return enqueue(() -> remote ? plugin.gitService().diffRemote() : plugin.gitService().diffLocal());
    }

    public CompletableFuture<Boolean> script(final CommandSender sender, final String name) {
        return enqueue(() -> {
            try {
                plugin.bossBars().show("script", Map.of());
                plugin.scriptManager().run(name, sender, line -> {
                });
                plugin.messages().send(sender, "script-success");
                plugin.bossBars().show("script-success", Map.of());
                plugin.auditLogger().log(actorOf(sender), "command", "script", true, name);
                return Boolean.TRUE;
            } catch (final ScriptException e) {
                plugin.auditLogger().log(actorOf(sender), "command", "script", false,
                        name + ": " + e.getMessage());
                fail(sender, "script-failed", e);
                plugin.bossBars().show("script-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> resolve(final CommandSender sender, final String mode) {
        return enqueue(() -> {
            try {
                switch (mode) {
                    case "merge-abort" -> plugin.gitService().resolveMergeAbort();
                    case "repo-reset" -> plugin.gitService().resolveRepoReset();
                    case "reset-local-changes" -> plugin.gitService().resolveResetLocalChanges();
                    default -> {
                        plugin.messages().send(sender, "resolve-usage");
                        return Boolean.FALSE;
                    }
                }
                plugin.messages().send(sender, "resolve-success-" + mode);
                plugin.auditLogger().log(actorOf(sender), "command", "resolve", true, mode);
                return Boolean.TRUE;
            } catch (final Exception e) {
                plugin.auditLogger().log(actorOf(sender), "command", "resolve", false, mode + ": " + safeMessage(e));
                final String key = "resolve-failed-" + mode;
                final Map<String, String> placeholders = Map.of("error", safeMessage(e));
                plugin.messages().send(sender, key, new HashMap<>(placeholders));
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> reload() {
        return enqueue(() -> {
            plugin.reloadPlugin();
            plugin.bossBars().show("reloaded", Map.of());
            return Boolean.TRUE;
        });
    }

    // --------------------------------------------------------- observability

    public CompletableFuture<List<AuditLogger.Entry>> audit(final CommandSender sender, final int page) {
        return enqueue(() -> plugin.auditLogger().read(page, 15));
    }

    public CompletableFuture<Analytics.Summary> analytics(final CommandSender sender) {
        return enqueue(plugin.analytics()::summary);
    }

    public CompletableFuture<Boolean> analyticsReset(final CommandSender sender) {
        return enqueue(() -> {
            plugin.analytics().reset();
            plugin.auditLogger().log(actorOf(sender), "command", "analytics-reset", true, null);
            return Boolean.TRUE;
        });
    }

    public CompletableFuture<Boolean> confirm(final CommandSender sender, final String id) {
        return enqueue(() -> {
            final boolean ok = plugin.approvalStore().confirm(id);
            plugin.auditLogger().log(actorOf(sender), "manual", "approval-confirm", ok, id);
            return ok;
        });
    }

    public CompletableFuture<Boolean> cancel(final CommandSender sender, final String id) {
        return enqueue(() -> {
            final boolean ok = plugin.approvalStore().cancel(id);
            plugin.auditLogger().log(actorOf(sender), "manual", "approval-cancel", ok, id);
            return ok;
        });
    }

    public AutoPullScheduler.State autoPullState() {
        return plugin.autoPullScheduler().state();
    }

    public int pendingApprovals() {
        return plugin.approvalStore().pendingCount();
    }

    // ----------------------------------------------------------------- control API

    @Override
    public boolean tryAcquireInFlight(final String requestId) {
        final String cur = inFlight.get();
        if (cur == null) {
            return inFlight.compareAndSet(null, requestId);
        }
        // idempotent retry for same id while in-flight
        return cur.equals(requestId);
    }

    @Override
    public void releaseInFlight(final String requestId) {
        inFlight.compareAndSet(requestId, null);
    }

    public boolean isBusy() {
        return inFlight.get() != null;
    }

    @Override
    public void acceptRequest(final String requestId, final List<Action> actions, final String branch,
            final String source) {
        final PendingRequest existing = plugin.pendingStore().load(requestId).orElse(null);
        if (existing != null) {
            if (existing.status() != Status.RUNNING) {
                // idempotent retry: report the stored terminal status and release inFlight
                controlStatus.update(requestId, existing.status(), existing.error(),
                        existing.index(), existing.total());
                releaseInFlight(requestId);
                return;
            }
            // existing RUNNING -> do not overwrite, resume from stored progress
            controlStatus.update(requestId, existing.status(), existing.error(),
                    existing.index(), existing.total());
            return;
        }
        final PendingRequest request = new PendingRequest(requestId, actions, branch, source);
        plugin.pendingStore().save(request);
        controlStatus.update(requestId, Status.RUNNING, null, request.index(), request.total());
        runRequestAsync(request);
    }

    @Override
    public ProgressStream progressStream(final String requestId) {
        return streams.computeIfAbsent(requestId, k -> new ProgressStream());
    }

    @Override
    public ControlStatus controlStatus() {
        return controlStatus;
    }

    @Override
    public void removeRequest(final String requestId) {
        streams.remove(requestId);
        controlStatus.clear(requestId);
        releaseInFlight(requestId);
    }

    private void runRequestAsync(final PendingRequest request) {
        CompletableFuture.supplyAsync(() -> {
            runActions(request);
            return null;
        }, worker);
    }

    private void runActions(final PendingRequest request) {
        final String requestId = request.requestId();
        final String source = request.source();
        final String branch = request.branch();
        final ProgressStream stream = streams.computeIfAbsent(requestId, k -> new ProgressStream());
        final long start = System.currentTimeMillis();
        plugin.events().emit(Type.DEPLOY_STARTED, source, source, "deploy request accepted",
                requestId, branch, 0L);
        try {
            while (request.hasRemaining()) {
                final Action action = request.current();
                // M-06: escape action before broadcast to SSE
                stream.broadcast("action:" + Messages.escape(String.valueOf(action)));
                final boolean ok = executeAction(action, branch, requestId, source);
                controlStatus.bump(requestId);
                if (ok) {
                    request.advance();
                    plugin.pendingStore().save(request);
                    controlStatus.update(requestId, Status.RUNNING, null,
                            request.index(), request.total());
                } else {
                    final String message = "action '" + Messages.escape(String.valueOf(action)) + "' failed";
                    request.failed(message);
                    plugin.pendingStore().save(request);
                    controlStatus.update(requestId, Status.FAILED, message,
                            request.index(), request.total());
                    plugin.events().emit(Type.DEPLOY_FAILED, source, source, message, requestId, branch,
                            System.currentTimeMillis() - start);
                    stream.broadcast("failed:" + Messages.escape(message));
                    stream.close();
                    removeRequest(requestId);
                    return;
                }
            }
            request.completed();
            plugin.pendingStore().save(request);
            controlStatus.update(requestId, Status.COMPLETED, null, request.total(), request.total());
            plugin.events().emit(Type.DEPLOY_COMPLETED, source, source,
                    "deploy request completed (" + request.total() + " action(s))", requestId, branch,
                    System.currentTimeMillis() - start);
            stream.broadcast("completed");
            stream.close();
            removeRequest(requestId);
        } catch (final Exception e) {
            final String message = Messages.escape(safeMessage(e));
            request.failed(message);
            plugin.pendingStore().save(request);
            controlStatus.update(requestId, Status.FAILED, message,
                    request.index(), request.total());
            plugin.events().emit(Type.DEPLOY_FAILED, source, source, safeMessage(e), requestId, branch,
                    System.currentTimeMillis() - start);
            stream.broadcast("failed:" + message);
            stream.close();
            removeRequest(requestId);
        }
    }

    private boolean executeAction(final Action action, final String branch, final String requestId,
            final String source) {
        switch (action.type()) {
            case PULL -> {
                final long start = System.currentTimeMillis();
                try {
                    plugin.gitService().pull(false);
                    if (plugin.healthCheck().enabled() && plugin.config().healthCheck().runInsideActions()) {
                        final HealthCheck.Result hc = plugin.healthCheck()
                                .check(plugin.config().healthCheck().timeoutSeconds());
                        if (!hc.ok()) {
                            plugin.events().emit(Type.DEPLOY_FAILED, source, "health", hc.message(), requestId,
                                    branch, System.currentTimeMillis() - start);
                            rollbackDeploy(null, source, hc.message(), source);
                            return false;
                        }
                    }
                    return true;
                } catch (final Exception e) {
                    return false;
                }
            }
            case PUSH -> {
                try {
                    final String message = action.argument() != null ? action.argument()
                            : plugin.config().control().pushMessage();
                    plugin.gitService().push(message);
                    return true;
                } catch (final Exception e) {
                    return false;
                }
            }
            case RESTART -> {
                scheduleRestart(requestId);
                return true;
            }
            case GLOBAL_RELOAD -> {
                reloadAllPluginsIncludingPlugin();
                return true;
            }
            case RELOAD_PLUGIN -> {
                reloadPluginByName(action.argument());
                return true;
            }
            case COMMAND -> {
                dispatchConsole(action.argument());
                return true;
            }
            case SCRIPT -> {
                try {
                    plugin.scriptManager().run(action.argument(), null, line -> {
                    });
                    return true;
                } catch (final Exception e) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
    }

    @SuppressWarnings("removal")
    private void scheduleRestart(final String requestId) {
        final Runnable restart = () -> {
            try {
                plugin.bossBars().show("control-trigger", Map.of());
            } finally {
                Bukkit.spigot().restart();
            }
        };
        Threads.marshaled(plugin, restart);
    }

    private void reloadAllPluginsIncludingPlugin() {
        Threads.marshaled(plugin, () -> plugin.getServer().reload());
    }

    private void reloadPluginByName(final String name) {
        final Runnable reload = () -> {
            final org.bukkit.plugin.Plugin target = Bukkit.getPluginManager().getPlugin(name);
            if (target != null) {
                Bukkit.getPluginManager().disablePlugin(target);
                Bukkit.getPluginManager().enablePlugin(target);
            }
        };
        Threads.marshaled(plugin, reload);
    }

    private void dispatchConsole(final String command) {
        Threads.marshaled(plugin, () -> plugin.getServer().dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    // ----------------------------------------------------------------- resume

    public void onServerStarted() {
        synchronized (resumeLock) {
            if (serverStarted) {
                return;
            }
            serverStarted = true;
        }
        worker.execute(this::resumePending);
    }

    private void resumePending() {
        for (final PendingRequest request : plugin.pendingStore().loadAll()) {
            if (request.status() != Status.RUNNING || !request.hasRemaining()) {
                continue;
            }
            plugin.getLogger().info("Resuming pending control request " + request.requestId());
            controlStatus.update(request.requestId(), Status.RUNNING, null,
                    request.index(), request.total());
            // post-restart health check: verify the server came back healthy
            if (plugin.healthCheck().enabled() && plugin.config().healthCheck().runsAfterRestart()) {
                final HealthCheck.Result hc = plugin.healthCheck()
                        .check(plugin.config().healthCheck().timeoutSeconds());
                if (!hc.ok()) {
                    plugin.getLogger().severe("Health check failed after server restart: " + hc.message());
                    request.failed("health check failed after restart: " + hc.message());
                    plugin.pendingStore().save(request);
                    controlStatus.update(request.requestId(), Status.FAILED, request.error(),
                            request.index(), request.total());
                    plugin.events().emit(Type.DEPLOY_FAILED, request.source(), "health", hc.message(),
                            request.requestId(), request.branch(), 0L);
                    rollbackDeploy(null, request.source(), hc.message(), request.source());
                    continue;
                }
            }
            runRequestAsync(request);
        }
    }

    public void shutdown() {
        worker.shutdownNow();
        plugin.gitService().close();
    }

    public boolean repoInitialized() {
        return plugin.gitService().isInitialized();
    }

    public Set<String> scriptNames() {
        return plugin.scriptManager().listScripts();
    }

    public boolean controlActive() {
        return plugin.isControlActive();
    }

    public String controlAddress() {
        return plugin.getControlAddress();
    }

    private String actorOf(final CommandSender sender) {
        return sender == null || sender.getName() == null ? "console" : sender.getName();
    }

    private void fail(final CommandSender sender, final String key, final Throwable e) {
        final String suggestion = ErrorCatalog.suggest(e);
        final String error = safeMessage(e);
        if (suggestion.isEmpty()) {
            plugin.messages().send(sender, key, Map.of("error", error));
        } else {
            plugin.messages().send(sender, key + "-suggestion",
                    Map.of("error", error, "suggestion", suggestion));
        }
    }

    private String safeMessage(final Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}