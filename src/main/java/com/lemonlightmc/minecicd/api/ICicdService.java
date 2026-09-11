package com.lemonlightmc.minecicd.api;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.lemonlightmc.minecicd.data.Actor;
import com.lemonlightmc.minecicd.data.Results;

public interface ICicdService {

    CompletableFuture<Boolean> init(Actor actor);

    CompletableFuture<Boolean> deinit(Actor actor);

    default CompletableFuture<Boolean> pull(final Actor actor) {
        return pull(actor, false);
    }

    CompletableFuture<Boolean> pull(Actor actor, boolean force);

    CompletableFuture<Boolean> push(Actor actor, String message);

    CompletableFuture<Boolean> add(Actor actor, List<Path> path);

    CompletableFuture<Boolean> add(Actor actor, String path);

    CompletableFuture<Boolean> remove(Actor actor, List<Path> path);

    CompletableFuture<Boolean> remove(Actor actor, String path);

    CompletableFuture<Boolean> reset(Actor actor, String commit);

    CompletableFuture<Boolean> revert(Actor actor, String commit);

    CompletableFuture<Boolean> rollback(Actor actor, String date);

    CompletableFuture<Results.LogPage> log(Actor actor, int page);

    CompletableFuture<Results.LogEntry> commit(Actor actor, String ref);

    CompletableFuture<Results.StatusInfo> status(Actor actor);

    CompletableFuture<List<String>> diff(Actor actor, boolean remote);

    CompletableFuture<Boolean> resolve(Actor actor, String mode);

    CompletableFuture<Boolean> reload();

    boolean repoInitialized();

}