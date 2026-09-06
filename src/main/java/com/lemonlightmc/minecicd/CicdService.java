package com.lemonlightmc.minecicd;

import com.lemonlightmc.minecicd.exceptions.ScriptException;
import com.lemonlightmc.minecicd.git.CommitActions;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;
import com.lemonlightmc.minecicd.git.GitException;
import com.lemonlightmc.minecicd.git.Results;
import com.lemonlightmc.minecicd.http.ControlServer;
import com.lemonlightmc.minecicd.http.ControlSecurity;
import com.lemonlightmc.minecicd.http.ControlStatus;
import com.lemonlightmc.minecicd.http.ProgressStream;
import com.lemonlightmc.minecicd.messaging.Messages;
import com.lemonlightmc.minecicd.pending.PendingRequest;
import com.lemonlightmc.minecicd.pending.PendingRequest.Status;
import com.lemonlightmc.minecicd.util.Threads;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

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
public class CicdService implements ControlServer.Delegate {

    private final MineCICD plugin;
    private final ControlStatus controlStatus = new ControlStatus();
    private final Map<String, ProgressStream> streams = new ConcurrentHashMap<>();
    private final ExecutorService worker;
    private volatile boolean serverStarted = false;
    private final Object resumeLock = new Object();
    private final AtomicReference<String> inFlight = new AtomicReference<>(null);

    public CicdService(MineCICD plugin) {
        this.plugin = plugin;
        this.worker = Threads.singleDaemonWorker("minecicd-worker");
    }

    public <T> CompletableFuture<T> enqueue(java.util.function.Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, worker);
    }

    // ------------------------------------------------------------------ commands

    public CompletableFuture<Boolean> pull(CommandSender sender, boolean force) {
        return enqueue(() -> {
            try {
                plugin.bossBars().show("pulling", Map.of());
                Results.PullResult result = plugin.gitService().pull(force);
                processCommitActions(result.commits());
                if (result.initialized()) {
                    plugin.messages().send(sender, "pull-success");
                    plugin.bossBars().show("pulled-changes", Map.of());
                    return Boolean.TRUE;
                }
                if (result.changed()) {
                    plugin.messages().send(sender, "pull-success");
                    plugin.bossBars().show("pulled-changes", Map.of());
                } else {
                    plugin.messages().send(sender, "pull-no-changes");
                    plugin.bossBars().show("pulled-no-changes", Map.of());
                }
                return Boolean.TRUE;
            } catch (GitException.PullAborted e) {
                plugin.messages().send(sender, "pull-aborted");
                plugin.bossBars().show("pull-aborted-changes", Map.of());
                return Boolean.FALSE;
            } catch (Exception e) {
                plugin.messages().send(sender, "pull-failed", Map.of("error", safeMessage(e)));
                plugin.bossBars().show("pull-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    private void processCommitActions(List<org.eclipse.jgit.revwalk.RevCommit> commits) {
        if (commits == null || commits.isEmpty()) {
            return;
        }
        for (org.eclipse.jgit.revwalk.RevCommit commit : commits) {
            for (Action action : CommitActions.parseCommitMessage(commit)) {
                if (action.type() == ActionType.PULL) {
                    continue;
                }
                // C-02: gate commit actions through same policy as HTTP control API
                try {
                    plugin.security().validateActions(List.of(action));
                } catch (ControlSecurity.RejectException e) {
                    plugin.getLogger().warning(
                            "Skipping commit action disallowed by policy: " + action + " (" + e.getMessage() + ")");
                    continue;
                }
                plugin.getLogger().info("Running commit action: " + action);
                executeAction(action, null, null);
            }
        }
    }

    public CompletableFuture<Boolean> push(CommandSender sender, String message) {
        return enqueue(() -> {
            try {
                plugin.bossBars().show("pushing", Map.of());
                Results.PushResult result = plugin.gitService().push(message);
                if (result.hadChanges()) {
                    plugin.messages().send(sender, "push-success");
                    plugin.bossBars().show("pushed", Map.of());
                } else {
                    plugin.bossBars().show("push-no-changes", Map.of());
                    plugin.messages().send(sender, "push-no-changes");
                }
                return Boolean.TRUE;
            } catch (Exception e) {
                plugin.messages().send(sender, "push-failed", Map.of("error", safeMessage(e)));
                plugin.bossBars().show("push-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> add(CommandSender sender, String path) {
        return enqueue(() -> {
            try {
                int amount = plugin.gitService().addToTracking(path);
                plugin.messages().send(sender, "add-success", Map.of("amount", String.valueOf(amount)));
                plugin.bossBars().show("added", Map.of("amount", String.valueOf(amount)));
                return Boolean.TRUE;
            } catch (Exception e) {
                plugin.messages().send(sender, "add-failed", Map.of("error", safeMessage(e)));
                plugin.bossBars().show("adding-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> remove(CommandSender sender, String path) {
        return enqueue(() -> {
            try {
                int amount = plugin.gitService().removeFromTracking(path);
                plugin.messages().send(sender, "remove-success", Map.of("amount", String.valueOf(amount)));
                plugin.bossBars().show("removed", Map.of("amount", String.valueOf(amount)));
                return Boolean.TRUE;
            } catch (Exception e) {
                plugin.messages().send(sender, "remove-failed", Map.of("error", safeMessage(e)));
                plugin.bossBars().show("removing-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> reset(CommandSender sender, String commit) {
        return enqueue(() -> {
            try {
                plugin.gitService().reset(commit);
                plugin.messages().send(sender, "reset-success");
                plugin.bossBars().show("reset", Map.of());
                return Boolean.TRUE;
            } catch (Exception e) {
                plugin.messages().send(sender, "reset-failed", Map.of("error", safeMessage(e)));
                plugin.bossBars().show("reset-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> revert(CommandSender sender, String commit) {
        return enqueue(() -> {
            try {
                plugin.gitService().revert(commit);
                plugin.messages().send(sender, "revert-success");
                plugin.bossBars().show("reverted", Map.of());
                return Boolean.TRUE;
            } catch (Exception e) {
                plugin.messages().send(sender, "revert-failed", Map.of("error", safeMessage(e)));
                plugin.bossBars().show("revert-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> rollback(CommandSender sender, String date) {
        return enqueue(() -> {
            try {
                plugin.gitService().rollback(date);
                plugin.messages().send(sender, "rollback-success");
                plugin.bossBars().show("reset", Map.of());
                return Boolean.TRUE;
            } catch (Exception e) {
                String message = safeMessage(e);
                plugin.messages().send(sender, "rollback-failed", Map.of("error", message));
                plugin.bossBars().show("reset-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Results.LogPage> log(CommandSender sender, int page) {
        return enqueue(() -> plugin.gitService().log(page));
    }

    public CompletableFuture<Results.LogEntry> commit(CommandSender sender, String ref) {
        return enqueue(() -> plugin.gitService().getCommit(ref));
    }

    public CompletableFuture<Results.StatusInfo> status(CommandSender sender) {
        return enqueue(() -> plugin.gitService().status());
    }

    public CompletableFuture<List<String>> diff(CommandSender sender, boolean remote) {
        return enqueue(() -> remote ? plugin.gitService().diffRemote() : plugin.gitService().diffLocal());
    }

    public CompletableFuture<Boolean> script(CommandSender sender, String name) {
        return enqueue(() -> {
            try {
                plugin.bossBars().show("script", Map.of());
                plugin.scriptManager().run(name, sender, line -> {
                });
                plugin.messages().send(sender, "script-success");
                plugin.bossBars().show("script-success", Map.of());
                return Boolean.TRUE;
            } catch (ScriptException e) {
                plugin.messages().send(sender, "script-failed", Map.of("error", e.getMessage()));
                plugin.bossBars().show("script-failed", Map.of());
                return Boolean.FALSE;
            }
        });
    }

    public CompletableFuture<Boolean> resolve(CommandSender sender, String mode) {
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
                return Boolean.TRUE;
            } catch (Exception e) {
                plugin.messages().send(sender, "resolve-failed-" + mode, Map.of("error", safeMessage(e)));
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

    // ----------------------------------------------------------------- control API

    @Override
    public boolean tryAcquireInFlight(String requestId) {
        String cur = inFlight.get();
        if (cur == null) {
            return inFlight.compareAndSet(null, requestId);
        }
        // idempotent retry for same id while in-flight
        return cur.equals(requestId);
    }

    @Override
    public void releaseInFlight(String requestId) {
        inFlight.compareAndSet(requestId, null);
    }

    @Override
    public void acceptRequest(String requestId, List<Action> actions, String branch) {
        PendingRequest existing = plugin.pendingStore().load(requestId).orElse(null);
        if (existing != null) {
            if (existing.status() != Status.RUNNING) {
                // idempotent retry: report the stored terminal status and release inFlight
                // acquired in handlePost so a different requestId is not blocked forever
                controlStatus.update(requestId, existing.status(), existing.error(),
                        existing.index(), existing.total());
                releaseInFlight(requestId);
                return;
            }
            // H-03: existing RUNNING -> do not overwrite, resume from stored progress
            controlStatus.update(requestId, existing.status(), existing.error(),
                    existing.index(), existing.total());
            return;
        }
        PendingRequest request = new PendingRequest(requestId, actions, branch);
        plugin.pendingStore().save(request);
        controlStatus.update(requestId, Status.RUNNING, null, request.index(), request.total());
        runRequestAsync(request);
    }

    @Override
    public ProgressStream progressStream(String requestId) {
        return streams.computeIfAbsent(requestId, k -> new ProgressStream());
    }

    @Override
    public ControlStatus controlStatus() {
        return controlStatus;
    }

    @Override
    public void removeRequest(String requestId) {
        streams.remove(requestId);
        controlStatus.clear(requestId);
        releaseInFlight(requestId);
    }

    private void runRequestAsync(PendingRequest request) {
        CompletableFuture.supplyAsync(() -> {
            runActions(request);
            return null;
        }, worker);
    }

    private void runActions(PendingRequest request) {
        String requestId = request.requestId();
        ProgressStream stream = streams.computeIfAbsent(requestId, k -> new ProgressStream());
        try {
            while (request.hasRemaining()) {
                Action action = request.current();
                // M-06: escape action before broadcast to SSE
                stream.broadcast("action:" + Messages.escape(String.valueOf(action)));
                boolean ok = executeAction(action, request.branch(), requestId);
                controlStatus.bump(requestId);
                if (ok) {
                    request.advance();
                    plugin.pendingStore().save(request);
                    controlStatus.update(requestId, Status.RUNNING, null,
                            request.index(), request.total());
                } else {
                    String message = "action '" + Messages.escape(String.valueOf(action)) + "' failed";
                    request.failed(message);
                    plugin.pendingStore().save(request);
                    controlStatus.update(requestId, Status.FAILED, message,
                            request.index(), request.total());
                    stream.broadcast("failed:" + Messages.escape(message));
                    stream.close();
                    removeRequest(requestId);
                    return;
                }
            }
            request.completed();
            plugin.pendingStore().save(request);
            controlStatus.update(requestId, Status.COMPLETED, null, request.total(), request.total());
            stream.broadcast("completed");
            stream.close();
            removeRequest(requestId);
        } catch (Exception e) {
            String message = Messages.escape(safeMessage(e));
            request.failed(message);
            plugin.pendingStore().save(request);
            controlStatus.update(requestId, Status.FAILED, message,
                    request.index(), request.total());
            stream.broadcast("failed:" + message);
            stream.close();
            removeRequest(requestId);
        }
    }

    private boolean executeAction(Action action, String branch, String requestId) {
        switch (action.type()) {
            case PULL -> {
                try {
                    plugin.gitService().pull(false);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            case PUSH -> {
                try {
                    String message = action.argument() != null ? action.argument()
                            : plugin.config().control().pushMessage();
                    plugin.gitService().push(message);
                    return true;
                } catch (Exception e) {
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
                } catch (Exception e) {
                    return false;
                }
            }
            default -> {
                return false;
            }
        }
    }

    @SuppressWarnings("removal")
    private void scheduleRestart(String requestId) {
        Runnable restart = () -> {
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

    private void reloadPluginByName(String name) {
        Runnable reload = () -> {
            org.bukkit.plugin.Plugin target = Bukkit.getPluginManager().getPlugin(name);
            if (target != null) {
                Bukkit.getPluginManager().disablePlugin(target);
                Bukkit.getPluginManager().enablePlugin(target);
            }
        };
        Threads.marshaled(plugin, reload);
    }

    private void dispatchConsole(String command) {
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
        for (PendingRequest request : plugin.pendingStore().loadAll()) {
            if (request.status() != Status.RUNNING || !request.hasRemaining()) {
                continue;
            }
            plugin.getLogger().info("Resuming pending control request " + request.requestId());
            controlStatus.update(request.requestId(), Status.RUNNING, null,
                    request.index(), request.total());
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

    private String safeMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}