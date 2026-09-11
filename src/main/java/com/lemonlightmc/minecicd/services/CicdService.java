package com.lemonlightmc.minecicd.services;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.MineCICDConfig;
import com.lemonlightmc.minecicd.MineCICDConfig.Approval;
import com.lemonlightmc.minecicd.MineCICDConfig.HealthCheck;
import com.lemonlightmc.minecicd.api.ICicdService;
import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.Action;
import com.lemonlightmc.minecicd.data.Actor;
import com.lemonlightmc.minecicd.data.PendingRequest;
import com.lemonlightmc.minecicd.data.Results;
import com.lemonlightmc.minecicd.data.PendingRequest.Status;
import com.lemonlightmc.minecicd.exceptions.ErrorCatalog;
import com.lemonlightmc.minecicd.exceptions.GitException;
import com.lemonlightmc.minecicd.exceptions.ScriptException;
import com.lemonlightmc.minecicd.git.CommitActions;
import com.lemonlightmc.minecicd.git.CommitActions.CommitAction;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;
import com.lemonlightmc.minecicd.http.ControlServer;
import com.lemonlightmc.minecicd.http.ControlSecurity;
import com.lemonlightmc.minecicd.http.ControlStatus;
import com.lemonlightmc.minecicd.http.ProgressStream;
import com.lemonlightmc.minecicd.util.Threads;
import com.lemonlightmc.minecicd.util.Utils;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.nio.file.Path;
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
 * marshaled to the server main thread.
 */
public class CicdService implements ICicdService {

    // ------------------------------------------------------------------ commands

    public CompletableFuture<Boolean> init(final Actor actor) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                final boolean created = MineCICDApi.gitService().init();
                if (created) {
                    MineCICDApi.messages().send(actor, "init-success");
                } else {
                    MineCICDApi.messages().send(actor, "init-already-initialized");
                }
                MineCICDApi.plugin().bossBars().show("init", Map.of());
                MineCICDApi.auditService().log(actor, Action.INIT, true, null);
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor, Action.INIT, false,
                        Utils.rootMessage(e));
                fail(actor, "init-failed", e);
                MineCICDApi.plugin().bossBars().show("init-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> deinit(final Actor actor) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                final boolean removed = MineCICDApi.gitService().deinit();
                if (removed) {
                    MineCICDApi.messages().send(actor, "deinit-success");
                } else {
                    MineCICDApi.messages().send(actor, "deinit-not-initialized");
                }
                MineCICDApi.plugin().bossBars().show("deinit", Map.of());
                MineCICDApi.auditService().log(actor, Action.DEINIT, true, null);
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor, Action.DEINIT, false,
                        Utils.rootMessage(e));
                fail(actor, "deinit-failed", e);
                MineCICDApi.plugin().bossBars().show("deinit-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> pull(final Actor actor) {
        return pull(actor, false);
    }

    /**
     * Pull with an explicit trigger {@link Source} and actor. Honors the
     * change-approval gate when enabled for that source.
     */
    public CompletableFuture<Boolean> pull(final Actor actor, final boolean force) {
        return MineCICDApi.handler().enqueue(() -> {
            final var cfg = MineCICDApi.config().approval();
            if (cfg != null && cfg.enabled() && cfg.requireOn().contains(actor)) {
                return gatePull(actor, force);
            }
            return doPull(actor, force);
        });
    }

    public boolean gatePull(final Actor actor, final boolean force) {
        final var cfg = MineCICDApi.config().approval();
        final List<String> changes;
        try {
            changes = MineCICDApi.gitService().pullPreview();
        } catch (final Exception e) {
            plugin.events().emit(Type.DEPLOY_FAILED, actor, Utils.rootMessage(e));
            fail(actor, "pull-failed", e);
            return false;
        }
        if (cfg != null && cfg.skipIfNoChanges() && changes.isEmpty()) {
            return doPull(actor, force);
        }
        final String id = MineCICDApi.approvalService().create(actor, MineCICDApi.config().git().branch(), changes,
                force);
        plugin.events().emit(Type.DEPLOY_STARTED, actor,
                "Pull pending approval " + id + " (" + changes.size() + " change(s))");
        if (actor != null) {
            if (changes.isEmpty()) {
                MineCICDApi.messages().send(actor, "approval-created-no-changes",
                        Map.of("id", id, "timeout", String.valueOf(cfg == null ? 120 : cfg.timeoutSeconds())));
            } else {
                MineCICDApi.messages().send(actor, "approval-created",
                        Map.of("id", id, "count", String.valueOf(changes.size()),
                                "timeout", String.valueOf(cfg == null ? 120 : cfg.timeoutSeconds())));
                for (final String change : changes) {
                    MineCICDApi.messages().sendRaw(actor,
                            MineCICDApi.messages().get("approval-change", Map.of("change", Utils.escape(change))));
                }
                MineCICDApi.messages().send(actor, "approval-confirm-hint", Map.of("id", id));
            }
        }
        return true;
    }

    public boolean doPull(final Actor actor, final boolean force) {
        final long start = System.currentTimeMillis();
        try {
            MineCICDApi.plugin().bossBars().show("pulling", Map.of());
            plugin.events().emit(Type.DEPLOY_STARTED, actor, actor.getName() + " pull started",
                    null, MineCICDApi.config().git().branch(), 0L);
            final Results.PullResult result = MineCICDApi.gitService().pull(force);
            final boolean changed = result.initialized() || !result.commits().isEmpty();
            if (changed) {
                processCommitActions(actor, result.commits());
            }
            if (result.initialized()) {
                MineCICDApi.messages().send(actor, "pull-success");
                MineCICDApi.plugin().bossBars().show("pulled-changes", Map.of());
            } else if (changed) {
                MineCICDApi.messages().send(actor, "pull-success");
                MineCICDApi.plugin().bossBars().show("pulled-changes", Map.of());
            } else {
                MineCICDApi.plugin().bossBars().show("pulled-no-changes", Map.of());
                MineCICDApi.messages().send(actor, "pull-no-changes");
                MineCICDApi.auditService().log(actor, Action.PULL, true, "no changes to pull");
                return true;
            }

            final long duration = System.currentTimeMillis() - start;
            plugin.events().emit(Type.DEPLOY_COMPLETED, actor,
                    "pull applied " + result.commits().size() + " commit(s)", null,
                    MineCICDApi.config().git().branch(), duration);
            MineCICDApi.auditService().log(actor,
                    Action.PULL, true,
                    "applied " + result.commits().size() + " commit(s)");

            if (MineCICDApi.healthCheckService().runAfterAction()) {
                final HealthCheckService.Result hc = MineCICDApi.healthCheckService().check();
                if (!hc.ok()) {
                    final long hcDuration = System.currentTimeMillis() - start;
                    plugin.events().emit(Type.DEPLOY_FAILED, actor, hc.message(), null,
                            MineCICDApi.config().git().branch(), hcDuration);
                    rollbackDeploy(actor, hc.message());
                    MineCICDApi.messages().send(actor, "health-check-failed",
                            Map.of("error", Utils.escape(hc.message())));
                    return false;
                }
            }
            return true;
        } catch (final GitException.PullAborted e) {
            MineCICDApi.messages().send(actor, "pull-aborted");
            MineCICDApi.plugin().bossBars().show("pull-aborted-changes", Map.of());
            MineCICDApi.auditService().log(actor, Action.PULL, false, "aborted (unpushed local changes)");
            return false;
        } catch (final Exception e) {
            final long duration = System.currentTimeMillis() - start;
            plugin.events().emit(Type.DEPLOY_FAILED, actor, Utils.rootMessage(e), null,
                    MineCICDApi.config().git().branch(), duration);
            fail(actor, "pull-failed", e);
            MineCICDApi.plugin().bossBars().show("pull-failed", Map.of());
            return false;
        }
    }

    public void rollbackDeploy(final Actor actor, final String reason) {
        try {
            final boolean ok = MineCICDApi.gitService().rollbackDeploy();
            if (ok) {
                plugin.events().emit(Type.ROLLBACK_EXECUTED, actor, "Auto-rollback after: " + reason);
                MineCICDApi.auditService().log(actor, Action.AUTO_ROLLBACK, true, reason);
                if (actor != null) {
                    MineCICDApi.messages().send(actor, "auto-rolled-back");
                }
                if (MineCICDApi.config().healthCheck().autoRollback().restartAfter()) {
                    scheduleRestart(null);
                }
            } else {
                MineCICDApi.logger().severe(
                        "Health check failed but rollback impossible (no parent commit): " + reason);
            }
        } catch (final Exception e) {
            MineCICDApi.logger().severe("Auto-rollback failed: " + Utils.rootMessage(e));
        }
    }

    private void processCommitActions(final Actor actor, final List<org.eclipse.jgit.revwalk.RevCommit> commits) {
        if (commits == null || commits.isEmpty()) {
            return;
        }
        for (final org.eclipse.jgit.revwalk.RevCommit commit : commits) {
            for (final CommitAction action : CommitActions.parseCommitMessage(commit)) {
                if (action.type() == ActionType.PULL) {
                    continue;
                }
                // gate commit actions through same policy as HTTP control API
                try {
                    if (plugin.security() != null) {
                        plugin.security().validateActions(List.of(action));
                    }
                } catch (final ControlSecurity.RejectException e) {
                    MineCICDApi.logger().warning(
                            "Skipping commit action disallowed by policy: " + action + " (" + e.getMessage() + ")");
                    continue;
                }
                MineCICDApi.logger().info("Running commit action: " + action);
                executeAction(actor, action, MineCICDApi.config().git().branch(), null);
            }
        }
    }

    public CompletableFuture<Boolean> push(final Actor actor, final String message) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                MineCICDApi.plugin().bossBars().show("pushing", Map.of());
                final Results.PushResult result = MineCICDApi.gitService().push(message);
                MineCICDApi.auditService().log(actor,
                        Action.PUSH, true,
                        message + (result.hadChanges() ? "" : " (no changes)"));
                if (result.hadChanges()) {
                    MineCICDApi.messages().send(actor, "push-success");
                    MineCICDApi.plugin().bossBars().show("pushed", Map.of());
                } else {
                    MineCICDApi.plugin().bossBars().show("push-no-changes", Map.of());
                    MineCICDApi.messages().send(actor, "push-no-changes");
                }
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor, Action.PUSH, e);
                fail(actor, "push-failed", e);
                MineCICDApi.plugin().bossBars().show("push-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> add(final Actor actor, final String path) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                final int amount = MineCICDApi.gitService().addToTracking(path);
                MineCICDApi.messages().send(actor, "add-success", Map.of("amount", String.valueOf(amount)));
                MineCICDApi.plugin().bossBars().show("added", Map.of("amount", String.valueOf(amount)));
                MineCICDApi.auditService().log(actor, Action.ADD, true, path);
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor,
                        Action.ADD, false, path + ": " + Utils.rootMessage(e));
                fail(actor, "add-failed", e);
                MineCICDApi.plugin().bossBars().show("adding-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> remove(final Actor actor, final String path) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                final int amount = MineCICDApi.gitService().removeFromTracking(path);
                MineCICDApi.messages().send(actor, "remove-success", Map.of("amount", String.valueOf(amount)));
                MineCICDApi.plugin().bossBars().show("removed", Map.of("amount", String.valueOf(amount)));
                MineCICDApi.auditService().log(actor, Action.REMOVE, true, path);
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor,
                        Action.REMOVE, false, path + ": " + Utils.rootMessage(e));
                fail(actor, "remove-failed", e);
                MineCICDApi.plugin().bossBars().show("removing-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> reset(final Actor actor, final String commit) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                MineCICDApi.gitService().reset(commit);
                MineCICDApi.messages().send(actor, "reset-success");
                MineCICDApi.plugin().bossBars().show("reset", Map.of());
                MineCICDApi.auditService().log(actor, Action.RESET, true, commit);
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor,
                        Action.RESET, false, commit + ": " + Utils.rootMessage(e));
                fail(actor, "reset-failed", e);
                MineCICDApi.plugin().bossBars().show("reset-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> revert(final Actor actor, final String commit) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                MineCICDApi.gitService().revert(commit);
                MineCICDApi.messages().send(actor, "revert-success");
                MineCICDApi.plugin().bossBars().show("reverted", Map.of());
                MineCICDApi.auditService().log(actor, Action.REVERT, true, commit);
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor,
                        Action.REVERT, false,
                        commit + ": " + Utils.rootMessage(e));
                fail(actor, "revert-failed", e);
                MineCICDApi.plugin().bossBars().show("revert-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> rollback(final Actor actor, final String date) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                MineCICDApi.gitService().rollback(date);
                MineCICDApi.messages().send(actor, "rollback-success");
                MineCICDApi.plugin().bossBars().show("reset", Map.of());
                MineCICDApi.auditService().log(actor, Action.ROLLBACK, true, date);
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor,
                        Action.ROLLBACK, false, date + ": " + Utils.rootMessage(e));
                fail(actor, "rollback-failed", e);
                MineCICDApi.plugin().bossBars().show("reset-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Results.LogPage> log(final Actor actor, final int page) {
        return MineCICDApi.handler().enqueue(() -> MineCICDApi.gitService().log(page));
    }

    public CompletableFuture<Results.LogEntry> commit(final Actor actor, final String ref) {
        return MineCICDApi.handler().enqueue(() -> MineCICDApi.gitService().getCommit(ref));
    }

    public CompletableFuture<Results.StatusInfo> status(final Actor actor) {
        return MineCICDApi.handler().enqueue(() -> MineCICDApi.gitService().status());
    }

    public CompletableFuture<List<String>> diff(final Actor actor, final boolean remote) {
        return MineCICDApi.handler()
                .enqueue(() -> remote ? MineCICDApi.gitService().diffRemote() : MineCICDApi.gitService().diffLocal());
    }

    public CompletableFuture<Boolean> script(final Actor actor, final String name) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                MineCICDApi.plugin().bossBars().show("script", Map.of());
                MineCICDApi.scriptService().run(name, actor, line -> {
                });
                MineCICDApi.messages().send(actor, "script-success");
                MineCICDApi.plugin().bossBars().show("script-success", Map.of());
                MineCICDApi.auditService().log(actor, Action.SCRIPT, true, name);
                return Boolean.TRUE;
            } catch (final ScriptException e) {
                MineCICDApi.auditService().log(actor,
                        Action.SCRIPT, false,
                        name + ": " + e.getMessage());
                fail(actor, "script-failed", e);
                MineCICDApi.plugin().bossBars().show("script-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> resolve(final Actor actor, final String mode) {
        return MineCICDApi.handler().enqueue(() -> {
            try {
                switch (mode) {
                    case "merge-abort" -> MineCICDApi.gitService().resolveMergeAbort();
                    case "repo-reset" -> MineCICDApi.gitService().resolveRepoReset();
                    case "reset-local-changes" -> MineCICDApi.gitService().resolveResetLocalChanges();
                    default -> {
                        MineCICDApi.messages().send(actor, "resolve-usage");
                        return Boolean.FALSE;
                    }
                }
                MineCICDApi.messages().send(actor, "resolve-success-" + mode);
                MineCICDApi.auditService().log(actor, Action.RESOLVE, true, mode);
                return Boolean.TRUE;
            } catch (final Exception e) {
                MineCICDApi.auditService().log(actor,
                        Action.RESOLVE, false, mode + ": " + Utils.rootMessage(e));
                final String key = "resolve-failed-" + mode;
                final Map<String, String> placeholders = Map.of("error", Utils.rootMessage(e));
                MineCICDApi.messages().send(actor, key, new HashMap<>(placeholders));
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> reload() {
        return MineCICDApi.handler().enqueue(() -> {
            MineCICDApi.plugin().reloadPlugin();
            MineCICDApi.plugin().bossBars().show("reloaded", Map.of());
            return Boolean.TRUE;
        });
    }

    // ----------------------------------------------------------------- control API

    public boolean executeAction(final Actor actor, final CommitAction action, final String branch,
            final String requestId) {
        switch (action.type()) {
            case PULL -> {
                final long start = System.currentTimeMillis();
                try {
                    MineCICDApi.gitService().pull(false);
                    if (MineCICDApi.healthCheckService().runAfterAction()) {
                        final HealthCheckService.Result hc = MineCICDApi.healthCheckService().check();
                        if (!hc.ok()) {
                            plugin.events().emit(Type.DEPLOY_FAILED,
                                    actor, hc.message(), requestId,
                                    branch, System.currentTimeMillis() - start);
                            rollbackDeploy(actor, hc.message());
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
                            : MineCICDApi.config().control().pushMessage();
                    MineCICDApi.gitService().push(message);
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
                    MineCICDApi.scriptService().run(action.argument(), null, line -> {
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
        MineCICDApi.auditService().markPluginRestart();
        final Runnable restart = () -> {
            try {
                MineCICDApi.plugin().bossBars().show("control-trigger", Map.of());
            } finally {
                Bukkit.spigot().restart();
            }
        };
        Threads.marshaled(restart);
    }

    private void reloadAllPluginsIncludingPlugin() {
        Threads.marshaled(() -> MineCICDApi.plugin().getServer().reload());
    }

    private void reloadPluginByName(final String name) {
        final Runnable reload = () -> {
            final org.bukkit.plugin.Plugin target = Bukkit.getPluginManager().getPlugin(name);
            if (target != null) {
                Bukkit.getPluginManager().disablePlugin(target);
                Bukkit.getPluginManager().enablePlugin(target);
            }
        };
        Threads.marshaled(reload);
    }

    private void dispatchConsole(final String command) {
        Threads.marshaled(() -> MineCICDApi.plugin().getServer().dispatchCommand(Bukkit.getConsoleSender(), command));
    }

    // ----------------------------------------------------------------- resume

    public boolean repoInitialized() {
        return MineCICDApi.gitService().isInitialized();
    }

    private void fail(final Actor actor, final String key, final Throwable e) {
        final String suggestion = ErrorCatalog.suggest(e);
        final String error = Utils.rootMessage(e);
        if (suggestion.isEmpty()) {
            MineCICDApi.messages().send(actor, key, Map.of("error", error));
        } else {
            MineCICDApi.messages().send(actor, key + "-suggestion",
                    Map.of("error", error, "suggestion", suggestion));
        }
    }

    @Override
    public CompletableFuture<Boolean> add(final Actor actor, final List<Path> path) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'add'");
    }

    @Override
    public CompletableFuture<Boolean> remove(final Actor actor, final List<Path> path) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'remove'");
    }
}