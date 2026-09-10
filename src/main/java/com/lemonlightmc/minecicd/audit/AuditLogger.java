package com.lemonlightmc.minecicd.audit;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.events.DeploymentEvents.Event;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Append-only {@code *.jsonl} audit log under
 * {@code plugins/MineCICD/audit/YYYY-MM-DD.jsonl}. One JSON object per line:
 * who ran what, when, and the outcome. Secret values are redacted before
 * write.
 */
public class AuditLogger {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record Entry(long ts, String iso, String actor, String source, String action, String outcome,
            String message, String requestId, String branch) {
        public JSONObject toJson() {
            return new JSONObject()
                    .put("ts", ts)
                    .put("iso", iso)
                    .put("actor", actor == null ? "" : actor)
                    .put("source", source == null ? "" : source)
                    .put("action", action == null ? "" : action)
                    .put("outcome", outcome == null ? "" : outcome)
                    .put("message", message == null ? "" : message)
                    .put("requestId", requestId == null ? "" : requestId)
                    .put("branch", branch == null ? "" : branch);
        }
    }

    private final MineCICD plugin;
    private final Path dir;
    private final List<String> secrets = new ArrayList<>();

    public AuditLogger(final MineCICD plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("audit");
        collectSecrets();
        plugin.events().subscribe(this::onEvent);
    }

    private void collectSecrets() {
        try {
            final var cfg = plugin.config();
            addSecret(cfg.git().pass());
            addSecret(cfg.control().secret());
            addSecret(cfg.control().githubWebhook() == null ? null : cfg.control().githubWebhook().secret());
            addSecret(cfg.discord().url());
        } catch (final Exception ignored) {
        }
    }

    private void addSecret(final String value) {
        if (value != null && value.length() >= 4) {
            secrets.add(value);
        }
    }

    public void refresh() {
        secrets.clear();
        collectSecrets();
    }

    public String redact(final String message) {
        if (message == null) {
            return "";
        }
        String out = message;
        for (final String s : secrets) {
            out = out.replace(s, "***");
        }
        return out;
    }

    public void log(final String actor, final String source, final String action, final boolean ok,
            final String message) {
        log(actor, source, action, ok ? "success" : "failure", message, null, null);
    }

    public void log(final String actor, final String source, final String action, final String outcome,
            final String message, final String requestId, final String branch) {
        if (!plugin.config().audit().enabled()) {
            return;
        }
        final long ts = System.currentTimeMillis();
        final Entry entry = new Entry(ts, ISO.format(Instant.ofEpochMilli(ts)), actor, source, action, outcome,
                redact(message), requestId, branch);
        append(entry);
    }

    public void onEvent(final Event event) {
        if (!plugin.config().audit().enabled()) {
            return;
        }
        final String outcome = switch (event.type()) {
            case DEPLOY_STARTED -> "start";
            case DEPLOY_COMPLETED -> "success";
            case ROLLBACK_EXECUTED -> "rollback";
            default -> "failure";
        };
        log(event.actor(), event.source(), event.type().name(), outcome, event.message(), event.requestId(),
                event.branch());
    }

    /** Returns entries newest-first for the given page. */
    public List<Entry> read(final int page, final int pageSize) {
        if (page <= 0 || pageSize <= 0) {
            return List.of();
        }
        final List<Entry> all = readAll();
        final int from = (page - 1) * pageSize;
        if (from >= all.size()) {
            return List.of();
        }
        return new ArrayList<>(all.subList(from, Math.min(from + pageSize, all.size())));
    }

    private List<Entry> readAll() {
        final List<Entry> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                return out;
            }
            try (var stream = Files.list(dir)) {
                final List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted()
                        .toList();
                for (final Path file : files) {
                    for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                        if (line.isBlank()) {
                            continue;
                        }
                        try {
                            out.add(fromJson(line));
                        } catch (final Exception ignored) {
                        }
                    }
                }
            }
        } catch (final IOException ignored) {
        }
        out.sort(Comparator.comparingLong(Entry::ts).reversed());
        return out;
    }

    public int count() {
        return readAll().size();
    }

    public void trim(final int maxAgeDays) {
        try {
            if (!Files.isDirectory(dir)) {
                return;
            }
            final LocalDate cutoff = LocalDate.now().minusDays(maxAgeDays);
            try (var stream = Files.list(dir)) {
                for (final Path file : stream.filter(p -> p.getFileName().toString().endsWith(".jsonl")).toList()) {
                    try {
                        final LocalDate date = LocalDate.parse(file.getFileName().toString().substring(0, 10), FILE);
                        if (date.isBefore(cutoff)) {
                            Files.deleteIfExists(file);
                        }
                    } catch (final Exception ignored) {
                    }
                }
            }
        } catch (final IOException ignored) {
        }
    }

    private void append(final Entry entry) {
        try {
            Files.createDirectories(dir);
            final Path file = dir.resolve(LocalDate.now().format(FILE) + ".jsonl");
            Files.writeString(file, entry.toJson().toString() + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to write audit log: " + e.getMessage());
        }
    }

    private static Entry fromJson(final String line) {
        final JSONObject j = new JSONObject(line);
        return new Entry(
                j.optLong("ts"),
                j.optString("iso", ""),
                j.optString("actor", ""),
                j.optString("source", ""),
                j.optString("action", ""),
                j.optString("outcome", ""),
                j.optString("message", ""),
                j.optString("requestId", ""),
                j.optString("branch", ""));
    }
}