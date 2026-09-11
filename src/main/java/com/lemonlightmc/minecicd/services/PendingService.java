package com.lemonlightmc.minecicd.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.PendingRequest;

/**
 * On-disk store for control-API requests. An accepted request is written here
 * before any action runs; the runner advances a pointer as actions complete.
 * Non-terminated requests are resumed after the server is fully loaded on boot.
 * Retries by requestId are idempotent (no double-run).
 */
public class PendingService {

    private final Path dir;

    public PendingService() {
        this.dir = MineCICDApi.dataFolder().resolve("pending");
    }

    public Path dir() {
        return dir;
    }

    public void save(final PendingRequest request) {
        try {
            Files.createDirectories(dir);
            Files.write(
                    dir.resolve(request.requestId() + ".json"),
                    request.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to persist pending request " + request.requestId(), e);
        }
    }

    public void delete(final String requestId) {
        try {
            Files.deleteIfExists(dir.resolve(requestId + ".json"));
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to delete pending request " + requestId, e);
        }
    }

    public Optional<PendingRequest> load(final String requestId) {
        try {
            final Path file = dir.resolve(requestId + ".json");
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(PendingRequest.fromJson(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            System.err.println("[MineCICD] Warning: corrupted pending file " + requestId + ".json: " + e.getMessage());
            return Optional.empty();
        }
    }

    public List<PendingRequest> loadAll() {
        final List<PendingRequest> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                return out;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(p -> {
                    try {
                        out.add(PendingRequest.fromJson(Files.readString(p, StandardCharsets.UTF_8)));
                    } catch (final Exception e) {
                        System.err.println("[MineCICD] Warning: corrupted pending file " + p.getFileName() + ": "
                                + e.getMessage());
                    }
                });
            }
        } catch (final IOException e) {
            return out;
        }
        return out;
    }
}