package com.lemonlightmc.minecicd.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.Actor;

public class AnalyticsService {

  private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final Path dir;
  private final Path file;
  private final Map<LocalDate, Day> days = new ConcurrentHashMap<>();
  private final AtomicBoolean initialized = new AtomicBoolean(false);

  public AnalyticsService() {
    this.dir = MineCICDApi.dataFolder();
    this.file = dir.resolve("analytics.json");
  }

  private void prepare() {
    if (!initialized.getAndSet(true)) {
      load();
    }
  }

  public void recordSuccess(final Actor actor, final long durationMillis) {
    prepare();
    final LocalDate today = LocalDate.now();
    final Day day = days.computeIfAbsent(today, (date) -> new Day(date));
    day.deploys++;
    day.successes++;
    day.totalDurationMillis += durationMillis;
    day.sources.merge(actor, 1, Integer::sum);
  }

  public void recordFailure(final Actor actor, final long durationMillis) {
    prepare();
    final LocalDate today = LocalDate.now();
    final Day day = days.computeIfAbsent(today, (date) -> new Day(date));
    day.deploys++;
    day.failures++;
    day.totalDurationMillis += durationMillis;
    day.sources.merge(actor, 1, Integer::sum);

  }

  public void recordRollback(final Actor actor, final long durationMillis) {
    prepare();
    final LocalDate today = LocalDate.now();
    final Day day = days.computeIfAbsent(today, (date) -> new Day(date));
    day.rollbacks++;
  }

  public void shutdown() {
    if (!initialized.get()) {
      return;
    }
    try {
      Files.createDirectories(dir);
      final StringBuilder sb = new StringBuilder();
      sb.append("{\n");
      for (final Day d : days.values()) {
        sb.append("  \"").append(d.date.format(FILE)).append("\": ").append(d.toJson().toString(2))
            .append(",\n");
      }
      sb.append("}\n");
      Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      MineCICDApi.logger().warning("Unable to write analytics: " + e.getMessage());
    }
  }

  public void reset() {
    try {
      if (!Files.isDirectory(dir)) {
        Files.deleteIfExists(file);
      }
      days.clear();
    } catch (final IOException e) {
      MineCICDApi.logger().warning("Unable to reset analytics: " + e.getMessage());
    }
  }

  private void load() {
    try {
      if (!Files.isRegularFile(file)) {
        return;
      }
      final JSONObject j = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
      final LocalDate maxAgeThreshold = LocalDate.now().minusDays(MineCICDApi.config().audit().maxAgeDays());
      for (final String key : j.keySet()) {
        try {
          final LocalDate date = LocalDate.parse(key, FILE);
          if (date.isBefore(maxAgeThreshold)) {
            continue;
          }
          days.put(date, new Day(date, j.getJSONObject(key)));
        } catch (final Exception ignored) {
        }
      }
    } catch (final Exception ignored) {
    }
  }

  public Summary summary() {
    prepare();
    int deploys = 0, successes = 0, failures = 0, rollbacks = 0;
    long duration = 0;
    final Map<Actor, Integer> sources = new HashMap<>();
    for (final Day d : days.values()) {
      final Day copy = d.copy();
      deploys += copy.deploys;
      successes += copy.successes;
      failures += copy.failures;
      rollbacks += copy.rollbacks;
      duration += copy.totalDurationMillis;
      for (final Map.Entry<Actor, Integer> e : copy.sources.entrySet()) {
        sources.merge(e.getKey(), e.getValue(), Integer::sum);
      }
    }
    return new Summary(deploys, successes, failures, rollbacks, duration, sources);
  }

  /** Aggregated view for the /minecicd analytics command. */
  public record Summary(int deploys, int successes, int failures, int rollbacks, long totalDurationMillis,
      Map<Actor, Integer> sources) {
    public int successRatePercent() {
      return deploys == 0 ? 0 : (int) Math.round(100.0 * successes / deploys);
    }

    public long avgDurationMillis() {
      return deploys == 0 ? 0 : totalDurationMillis / deploys;
    }
  }

  private static final class Day {
    final LocalDate date;
    int deploys;
    int successes;
    int failures;
    int rollbacks;
    long totalDurationMillis;
    final Map<Actor, Integer> sources = new HashMap<>();

    Day(final LocalDate date, final JSONObject j) {
      this.date = date;
      deploys = j.optInt("deploys");
      successes = j.optInt("successes");
      failures = j.optInt("failures");
      rollbacks = j.optInt("rollbacks");
      totalDurationMillis = j.optLong("totalDurationMillis");
      final JSONObject s = j.optJSONObject("sources");
      if (s != null) {
        for (final String key : s.keySet()) {
          sources.put(Actor.fromString(key), s.optInt(key));
        }
      }
    }

    Day(final LocalDate date) {
      this.date = date;
      deploys = 0;
      successes = 0;
      failures = 0;
      rollbacks = 0;
      totalDurationMillis = 0;
    }

    synchronized Day copy() {
      final Day copy = new Day(date, new JSONObject());
      copy.deploys = deploys;
      copy.successes = successes;
      copy.failures = failures;
      copy.rollbacks = rollbacks;
      copy.totalDurationMillis = totalDurationMillis;
      copy.sources.putAll(sources);
      return copy;
    }

    synchronized JSONObject toJson() {
      final JSONObject s = new JSONObject();
      for (final Map.Entry<Actor, Integer> e : sources.entrySet()) {
        s.put(e.getKey().toString(), e.getValue());
      }
      return new JSONObject()
          .put("deploys", deploys)
          .put("successes", successes)
          .put("failures", failures)
          .put("rollbacks", rollbacks)
          .put("totalDurationMillis", totalDurationMillis)
          .put("sources", s);
    }
  }

}
