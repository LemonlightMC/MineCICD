package com.lemonlightmc.minecicd;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import com.lemonlightmc.minecicd.api.ICicdService2;
import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.Actor;
import com.lemonlightmc.minecicd.data.PendingRequest;
import com.lemonlightmc.minecicd.data.PendingRequest.Status;
import com.lemonlightmc.minecicd.git.CommitActions.CommitAction;
import com.lemonlightmc.minecicd.http.ControlStatus;
import com.lemonlightmc.minecicd.http.ProgressStream;
import com.lemonlightmc.minecicd.services.HealthCheckService;
import com.lemonlightmc.minecicd.util.Threads;
import com.lemonlightmc.minecicd.util.Utils;
import com.lemonlightmc.minecicd.http.ControlServer;

public class MineCICDHandler implements ControlServer.Delegate {

  private final ControlStatus controlStatus = new ControlStatus();
  private final Map<String, ProgressStream> streams = new ConcurrentHashMap<>();
  private final ExecutorService worker;
  private volatile boolean serverStarted = false;
  private final Object resumeLock = new Object();
  private final AtomicReference<String> inFlight = new AtomicReference<>(null);

  public MineCICDHandler() {
    this.worker = Threads.singleDaemonWorker("minecicd-worker");
    MineCICDApi.approvalService().setRunFactory(
        (actor, branch, force) -> () -> MineCICDApi.cicdService().doPull(actor, force));
  }

  public <T> CompletableFuture<T> enqueue(final java.util.function.Supplier<T> task) {
    return CompletableFuture.supplyAsync(task, worker);
  }

  public void onServerStarted() {
    synchronized (resumeLock) {
      if (serverStarted) {
        return;
      }
      serverStarted = true;
    }
    worker.execute(this::resumePending);
  }

  public void shutdown() {
    worker.shutdownNow();
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
  public void acceptRequest(final String requestId, final List<CommitAction> actions, final String branch) {
    final PendingRequest existing = MineCICDApi.pendingService().load(requestId).orElse(null);
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
    final PendingRequest request = new PendingRequest(requestId, actions, branch);
    MineCICDApi.pendingService().save(request);
    controlStatus.update(requestId, Status.RUNNING, null, request.index(), request.total());
    runRequestAsync(request);
  }

  private void resumePending() {
    for (final PendingRequest request : MineCICDApi.pendingService().loadAll()) {
      if (request.status() != Status.RUNNING || !request.hasRemaining()) {
        continue;
      }
      MineCICDApi.logger().info("Resuming pending control request " + request.requestId());
      controlStatus.update(request.requestId(), Status.RUNNING, null,
          request.index(), request.total());
      // post-restart health check: verify the server came back healthy
      if (MineCICDApi.healthCheckService().runAfterRestart()) {
        final HealthCheckService.Result hc = MineCICDApi.healthCheckService().check();
        if (!hc.ok()) {
          MineCICDApi.logger().severe("Health check failed after server restart: " + hc.message());
          request.failed("health check failed after restart: " + hc.message());
          MineCICDApi.pendingService().save(request);
          controlStatus.update(request.requestId(), Status.FAILED, request.error(),
              request.index(), request.total());
          // plugin.events().emit(Type.DEPLOY_FAILED, "health", hc.message(),
          // request.requestId(), request.branch(), 0L);
          MineCICDApi.cicdService().rollbackDeploy(Actor.fromConsole(), hc.message());
          continue;
        }
      }
      runRequestAsync(request);
    }
  }

  public void runActions(final PendingRequest request) {
    final String requestId = request.requestId();
    final String branch = request.branch();
    final ProgressStream stream = streams.computeIfAbsent(requestId, k -> new ProgressStream());
    final long start = System.currentTimeMillis();
    plugin.events().emit(Type.DEPLOY_STARTED,
        "actions", "deploy request accepted",
        requestId, branch, 0L);
    try {
      while (request.hasRemaining()) {
        final CommitAction action = request.current();
        // escape action before broadcast to SSE
        stream.broadcast("action:" + Utils.escape(String.valueOf(action)));
        final boolean ok = MineCICDApi.cicdService().executeAction(null, action, branch, requestId);
        controlStatus.bump(requestId);
        if (ok) {
          request.advance();
          MineCICDApi.pendingService().save(request);
          controlStatus.update(requestId, Status.RUNNING, null,
              request.index(), request.total());
        } else {
          final String message = "action '" + Utils.escape(String.valueOf(action)) + "' failed";
          request.failed(message);
          MineCICDApi.pendingService().save(request);
          controlStatus.update(requestId, Status.FAILED, message,
              request.index(), request.total());
          plugin.events().emit(Type.DEPLOY_FAILED, "actions", message, requestId, branch,
              System.currentTimeMillis() - start);
          stream.broadcast("failed:" + Utils.escape(message));
          stream.close();
          removeRequest(requestId);
          return;
        }
      }
      request.completed();
      MineCICDApi.pendingService().save(request);
      controlStatus.update(requestId, Status.COMPLETED, null, request.total(), request.total());
      plugin.events().emit(Type.DEPLOY_COMPLETED,
          "actions",
          "deploy request completed (" + request.total() + " action(s))", requestId, branch,
          System.currentTimeMillis() - start);
      stream.broadcast("completed");
      stream.close();
      removeRequest(requestId);
    } catch (final Exception e) {
      final String message = Utils.escape(Utils.rootMessage(e));
      request.failed(message);
      MineCICDApi.pendingService().save(request);
      controlStatus.update(requestId, Status.FAILED, message,
          request.index(), request.total());
      plugin.events().emit(Type.DEPLOY_FAILED,
          "actions", Utils.rootMessage(e), requestId, branch,
          System.currentTimeMillis() - start);
      stream.broadcast("failed:" + message);
      stream.close();
      removeRequest(requestId);
    }
  }
}
