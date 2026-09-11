package com.lemonlightmc.minecicd.services;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.messaging.Messages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only, plain-text audit log under
 * {@code plugins/MineCICD/audit/audit.log}. Exactly one text line per entry:
 * who ran what, when, and the outcome. Secret values are redacted before
 * write.
 * <p>
 * A single active file is used. When the server is (re)started the active file
 * is rotated to {@code audit-<timestamp>.log} and a fresh one is started — the
 * exception is restarts that this plugin scheduled itself ({@link
 * #markPluginRestart()}), which keep appending to the current file. Once more
 * than {@code audit.max-files} files exist, the oldest ones are deleted.
 */
public class AuditLogger {

    private static final String ACTIVE_FILE = "audit.log";
    private static final long RESTART_MARKER_TTL_MS = 10 * 60 * 1000L;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
    private static final Pattern FIELD = Pattern.compile("([a-zA-Z]+)=([^ ]+)");

    /** Audit entry action, persisted via {@link #value()} as its wire string. */
    public enum AuditAction {
        INIT("init"),
        DEINIT("deinit"),
        PULL("pull"),
        PUSH("push"),
        ADD("add"),
        REMOVE("remove"),
        RESET("reset"),
        REVERT("revert"),
        ROLLBACK("rollback"),
        SCRIPT("script"),
        RESOLVE("resolve"),
        ANALYTICS_RESET("analytics-reset"),
        APPROVAL_CREATED("approval-created"),
        APPROVAL_CONFIRM("approval-confirm"),
        APPROVAL_CANCEL("approval-cancel"),
        AUTO_ROLLBACK("auto-rollback");

        private final String value;

        AuditAction(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /** Audit entry source (trigger class), persisted via {@link #value()}. */
    public enum Source {
        COMMAND("command"),
        MANUAL("manual"),
        CONTROL_API("control-api"),
        WEBHOOK("webhook"),
        COMMIT_ACTION("commit-action");

        private final String value;

        Source(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /** Resolves a wire string (e.g. from a persisted request) back to a value. */
        public static Source from(final String value) {
            if (value != null) {
                for (final Source s : values()) {
                    if (s.value.equals(value)) {
                        return s;
                    }
                }
            }
            return MANUAL;
        }
    }

    public record Entry(long ts, String iso, String actor, Source source, AuditAction action, String outcome,
            String message, String requestId, String branch) {
    }

    private final MineCICD plugin;
    private final Path dir;
    private final Path marker;
    private final List<String> secrets = new ArrayList<>();

    public AuditLogger(final MineCICD plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("audit");
        this.marker = plugin.getDataFolder().toPath().resolve(".minecicd-restart");
        refresh();
        startup();
    }

    public void refresh() {
        secrets.clear();
        try {
            final var cfg = plugin.config();
            addSecret(cfg.git().pass());
            addSecret(cfg.control().secret());
            addSecret(cfg.control().githubWebhook() == null ? null : cfg.control().githubWebhook().secret());
            addSecret(cfg.discord().url());
        } catch (final Exception ignored) {
        }
    }

    /**
     * Records that the upcoming shutdown is a restart this plugin scheduled, so
     * the current audit file is kept instead of rotated. Call right before the
     * server restarts.
     */
    public void markPluginRestart() {
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, Instant.now().toString(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to write restart marker: " + e.getMessage());
        }
    }

    public void log(final String actor, final Source source, final AuditAction action, final boolean ok,
            final String message) {
        log(actor, source, action, ok ? "success" : "failure", message, null, null);
    }

    public void log(final String actor, final Source source, final AuditAction action, final Exception ex) {
        log(actor, source, action, "failure", Messages.rootMessage(ex), null, null);
    }

    public void log(final String actor, final Source source, final AuditAction action, final String outcome,
            String message, final String requestId, final String branch) {
        if (!plugin.config().audit().enabled()) {
            return;
        }
        if (message == null) {
            message = "";
        } else if (!message.isEmpty()) {
            for (final String s : secrets) {
                message = message.replace(s, "***");
            }
            message = message.replace("\r", " ").replace("\n", " ");
        }
        final long ts = System.currentTimeMillis();
        final Entry entry = new Entry(ts, ISO.format(Instant.ofEpochMilli(ts)), actor, source, action,
                outcome, message, requestId, branch);
        append(entry);
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

    private void startup() {
        try {
            Files.createDirectories(dir);
            if (!consumePluginRestartMarker()) {
                rotate();
            }
            prune();
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to initialise audit log: " + e.getMessage());
        }
    }

    /**
     * Consumes the restart marker left by {@link #markPluginRestart()}. Returns
     * true when the previous shutdown was a restart this plugin scheduled (and
     * happened recently), in which case the current file is kept.
     */
    private boolean consumePluginRestartMarker() {
        try {
            if (!Files.isRegularFile(marker)) {
                return false;
            }
            final String stamp = Files.readString(marker, StandardCharsets.UTF_8).trim();
            final boolean fresh = System.currentTimeMillis()
                    - Instant.parse(stamp).toEpochMilli() <= RESTART_MARKER_TTL_MS;
            Files.deleteIfExists(marker);
            return fresh;
        } catch (final Exception e) {
            try {
                Files.deleteIfExists(marker);
            } catch (final IOException ignored) {
            }
            return false;
        }
    }

    /** Moves the active file to a timestamped name so a fresh one starts. */
    private void rotate() {
        try {
            final Path active = dir.resolve(ACTIVE_FILE);
            if (!Files.exists(active)) {
                return;
            }
            Files.move(active, dir.resolve("audit-" + LocalDateTime.now().format(STAMP) + ".log"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to rotate audit log: " + e.getMessage());
        }
    }

    /** Deletes the oldest files once more than {@code max-files} exist. */
    private void prune() {
        final int maxFiles = Math.max(1, plugin.config().audit().maxFiles());
        final List<Path> files = listFiles();
        int excess = files.size() - maxFiles;
        for (final Path file : files) {
            if (excess <= 0) {
                break;
            }
            try {
                Files.deleteIfExists(file);
                excess--;
            } catch (final IOException ignored) {
            }
        }
    }

    private List<Path> listFiles() {
        final List<Path> out = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            final List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(p -> isLogFile(p.getFileName().toString()))
                    .sorted().toList();
            for (final Path p : files) {
                out.add(p);
            }
        } catch (final IOException ignored) {
        }
        return out;
    }

    private static boolean isLogFile(final String name) {
        return name.equals(ACTIVE_FILE) || name.matches("audit-\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}-\\d{3}\\.log");
    }

    private List<Entry> readAll() {
        final List<Entry> out = new ArrayList<>();
        for (final Path file : listFiles()) {
            for (final String line : readLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    final Entry entry = fromFileLine(line);
                    if (entry != null) {
                        out.add(entry);
                    }
                } catch (final Exception ignored) {
                }
            }
        }
        out.sort(Comparator.comparingLong(Entry::ts).reversed());
        return out;
    }

    private List<String> readLines(final Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            return List.of();
        }
    }

    public int count() {
        return readAll().size();
    }

    private void append(final Entry entry) {
        try {
            Files.createDirectories(dir);
            final Path file = dir.resolve(ACTIVE_FILE);
            Files.writeString(file, formatLine(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to write audit log: " + e.getMessage());
        }
    }

    private static String formatLine(final Entry entry) {
        final StringBuilder sb = new StringBuilder();
        sb.append('[').append(entry.iso()).append("] actor=").append(nullToEmpty(entry.actor()));
        sb.append(" source=").append(nullToEmpty(entry.source().toString()));
        sb.append(" action=").append(nullToEmpty(entry.action().toString()));
        sb.append(" outcome=").append(nullToEmpty(entry.outcome()));
        if (entry.requestId() != null && !entry.requestId().isBlank()) {
            sb.append(" request=").append(entry.requestId());
        }
        if (entry.branch() != null && !entry.branch().isBlank()) {
            sb.append(" branch=").append(entry.branch());
        }
        sb.append(" message=").append(nullToEmpty(entry.message()));
        return sb.toString();
    }

    private static Entry fromFileLine(final String line) {
        final int tsStart = line.indexOf('[');
        final int tsEnd = line.indexOf(']', tsStart);
        if (tsStart < 0 || tsEnd < 0) {
            return null;
        }
        final long ts;
        try {
            ts = Instant.parse(line.substring(tsStart + 1, tsEnd)).toEpochMilli();
        } catch (final Exception e) {
            return null;
        }
        String rest = line.substring(tsEnd + 1).trim();
        String message = "";
        final int split = rest.indexOf(" message=");
        if (split >= 0) {
            message = rest.substring(split + " message=".length());
            rest = rest.substring(0, split);
        }
        String actor = "", source = "", action = "", outcome = "", requestId = "", branch = "";
        final Matcher m = FIELD.matcher(rest);
        while (m.find()) {
            switch (m.group(1)) {
                case "actor" -> actor = m.group(2);
                case "source" -> source = m.group(2);
                case "action" -> action = m.group(2);
                case "outcome" -> outcome = m.group(2);
                case "request" -> requestId = m.group(2);
                case "branch" -> branch = m.group(2);
                default -> {
                }
            }
        }
        return new Entry(ts, line.substring(tsStart + 1, tsEnd), actor, Source.from(source),
                AuditAction.valueOf(action), outcome, message, requestId,
                branch);
    }

    private static String nullToEmpty(final String value) {
        return value == null ? "" : value;
    }

    private void addSecret(final String value) {
        if (value != null && value.length() >= 4) {
            secrets.add(value);
        }
    }
}