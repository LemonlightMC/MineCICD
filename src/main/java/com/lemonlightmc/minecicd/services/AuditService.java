package com.lemonlightmc.minecicd.services;

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

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.Action;
import com.lemonlightmc.minecicd.data.Actor;
import com.lemonlightmc.minecicd.util.Utils;

public class AuditService {
  private static final String ACTIVE_FILE = "audit.log";
  private static final long RESTART_MARKER_TTL_MS = 10 * 60 * 1000L;
  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS");
  private static final Pattern FIELD = Pattern.compile("([a-zA-Z]+)=([^ ]+)");

  public record Entry(long ts, String iso, Actor actor, Action action, String outcome,
      String message, String requestId, String branch) {
  }

  private final Path dir;
  private final Path marker;

  public AuditService() {
    this.dir = MineCICDApi.dataFolder().resolve("audit");
    this.marker = MineCICDApi.dataFolder().resolve(".minecicd-restart");
    startup();
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
      MineCICDApi.logger().warning("Unable to write restart marker: " + e.getMessage());
    }
  }

  public void log(final Actor actor, final Action action, final boolean ok,
      final String message) {
    log(actor, action, ok ? "success" : "failure", message, null, null);
  }

  public void log(final Actor actor, final Action action, final Exception ex) {
    log(actor, action, "failure", Utils.rootMessage(ex), null, null);
  }

  public void log(final Actor actor, final Action action, final String outcome,
      String message, final String requestId, final String branch) {
    if (!MineCICDApi.config().audit().enabled()) {
      return;
    }
    message = MineCICDApi.secretService().redact(message);
    final long ts = System.currentTimeMillis();
    final Entry entry = new Entry(ts, ISO.format(Instant.ofEpochMilli(ts)), actor, action, outcome, message, requestId,
        branch);
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
      MineCICDApi.logger().warning("Unable to initialise audit log: " + e.getMessage());
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
      MineCICDApi.logger().warning("Unable to rotate audit log: " + e.getMessage());
    }
  }

  /** Deletes the oldest files once more than {@code max-files} exist. */
  private void prune() {
    final int maxFiles = Math.max(1, MineCICDApi.config().audit().maxFiles());
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
      MineCICDApi.logger().warning("Unable to write audit log: " + e.getMessage());
    }
  }

  private static String formatLine(final Entry entry) {
    final StringBuilder sb = new StringBuilder();
    sb.append('[').append(entry.iso());
    sb.append("] actor=").append(entry.actor().toString());
    sb.append(" action=").append(entry.action());
    sb.append(" outcome=").append(Utils.nullToEmpty(entry.outcome()));
    if (entry.requestId() != null && !entry.requestId().isBlank()) {
      sb.append(" request=").append(entry.requestId());
    }
    if (entry.branch() != null && !entry.branch().isBlank()) {
      sb.append(" branch=").append(entry.branch());
    }
    sb.append(" message=").append(Utils.nullToEmpty(entry.message()));
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
    String actor = "", action = "", outcome = "", requestId = "", branch = "";
    final Matcher m = FIELD.matcher(rest);
    while (m.find()) {
      switch (m.group(1)) {
        case "actor" -> actor = m.group(2);
        case "action" -> action = m.group(2);
        case "outcome" -> outcome = m.group(2);
        case "request" -> requestId = m.group(2);
        case "branch" -> branch = m.group(2);
        default -> {
        }
      }
    }
    return new Entry(ts, line.substring(tsStart + 1, tsEnd), Actor.fromString(actor),
        Action.valueOf(action), outcome, message, requestId,
        branch);
  }
}
