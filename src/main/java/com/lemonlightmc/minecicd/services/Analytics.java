package com.lemonlightmc.minecicd.services;

import com.lemonlightmc.minecicd.MineCICD;
import org.json.JSONObject;

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

/**
 * Per-day deployment analytics stored under
 * {@code plugins/MineCICD/analytics/YYYY-MM-DD.json}. Tracks deploy count,
 * success/failure rate, average deploy time, rollback count, and a per-source
 * breakdown (manual / control-api / scheduler / webhook).
 */
public class Analytics {

    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final MineCICD plugin;
    private final Path dir;
    private final Path file;
    private final Map<LocalDate, Day> days = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public Analytics(final MineCICD plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath();
        this.file = dir.resolve("analytics.json");
    }

    private void prepare() {
        if (!initialized.getAndSet(true)) {
            load();
        }
    }

    public void recordSuccess(final String source, final long durationMillis) {
        prepare();
        final LocalDate today = LocalDate.now();
        final Day day = days.computeIfAbsent(today, (date) -> new Day(date));
        day.deploys++;
        day.successes++;
        day.totalDurationMillis += durationMillis;
        day.sources.merge(coalesce(source), 1, Integer::sum);
    }

    public void recordFailure(final String source, final long durationMillis) {
        prepare();
        final LocalDate today = LocalDate.now();
        final Day day = days.computeIfAbsent(today, (date) -> new Day(date));
        day.deploys++;
        day.failures++;
        day.totalDurationMillis += durationMillis;
        day.sources.merge(coalesce(source), 1, Integer::sum);

    }

    public void recordRollback(final String source, final long durationMillis) {
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
            plugin.getLogger().warning("Unable to write analytics: " + e.getMessage());
        }
    }

    public void reset() {
        try {
            if (!Files.isDirectory(dir)) {
                Files.deleteIfExists(file);
            }
            days.clear();
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to reset analytics: " + e.getMessage());
        }
    }

    private void load() {
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            final JSONObject j = new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
            final LocalDate maxAgeThreshold = LocalDate.now().minusDays(plugin.config().audit().maxAgeDays());
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
        final Map<String, Integer> sources = new HashMap<>();
        for (final Day d : days.values()) {
            final Day copy = d.copy();
            deploys += copy.deploys;
            successes += copy.successes;
            failures += copy.failures;
            rollbacks += copy.rollbacks;
            duration += copy.totalDurationMillis;
            for (final Map.Entry<String, Integer> e : copy.sources.entrySet()) {
                sources.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        return new Summary(deploys, successes, failures, rollbacks, duration, sources);
    }

    /** Aggregated view for the /minecicd analytics command. */
    public record Summary(int deploys, int successes, int failures, int rollbacks, long totalDurationMillis,
            Map<String, Integer> sources) {
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
        final Map<String, Integer> sources = new HashMap<>();

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
                    sources.put(key, s.optInt(key));
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
            for (final Map.Entry<String, Integer> e : sources.entrySet()) {
                s.put(e.getKey(), e.getValue());
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

    private static String coalesce(final String source) {
        return source == null || source.isBlank() ? "unknown" : source;
    }
}