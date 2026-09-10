package com.lemonlightmc.minecicd.analytics;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.events.DeploymentEvents.Event;
import com.lemonlightmc.minecicd.events.DeploymentEvents.Type;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<LocalDate, Day> days = new ConcurrentHashMap<>();

    public Analytics(final MineCICD plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("analytics");
        plugin.events().subscribe(this::onEvent);
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
        final Map<String, Integer> sources = new TreeMap<>();

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

    public void onEvent(final Event event) {
        final LocalDate today = LocalDate.now();
        final Day day = days.computeIfAbsent(today, this::load);
        synchronized (day) {
            switch (event.type()) {
                case DEPLOY_COMPLETED -> {
                    day.deploys++;
                    day.successes++;
                    day.totalDurationMillis += event.durationMillis();
                    day.sources.merge(coalesce(event.source()), 1, Integer::sum);
                }
                case DEPLOY_FAILED -> {
                    day.deploys++;
                    day.failures++;
                    day.totalDurationMillis += event.durationMillis();
                    day.sources.merge(coalesce(event.source()), 1, Integer::sum);
                }
                case ROLLBACK_EXECUTED -> day.rollbacks++;
                default -> {
                }
            }
            save(today, day.toJson());
        }
    }

    public Summary summary() {
        int deploys = 0, successes = 0, failures = 0, rollbacks = 0;
        long duration = 0;
        final Map<String, Integer> sources = new TreeMap<>();
        for (final Day d : allDays()) {
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

    public void reset() {
        try {
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    for (final Path p : stream.filter(pp -> pp.getFileName().toString().endsWith(".json")).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
            days.clear();
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to reset analytics: " + e.getMessage());
        }
    }

    private List<Day> allDays() {
        final List<Day> out = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                return out;
            }
            try (var stream = Files.list(dir)) {
                final List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted()
                        .toList();
                for (final Path file : files) {
                    try {
                        final LocalDate date = LocalDate.parse(file.getFileName().toString().substring(0, 10), FILE);
                        out.add(load(date));
                    } catch (final Exception ignored) {
                    }
                }
            }
        } catch (final IOException ignored) {
        }
        out.sort(Comparator.comparing(d -> d.date));
        return out;
    }

    private Day load(final LocalDate date) {
        final Path file = dir.resolve(date.format(FILE) + ".json");
        try {
            if (Files.isRegularFile(file)) {
                return new Day(date, new JSONObject(Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (final Exception ignored) {
        }
        return new Day(date, new JSONObject());
    }

    private void save(final LocalDate date, final JSONObject json) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(date.format(FILE) + ".json"), json.toString(2), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            plugin.getLogger().warning("Unable to write analytics: " + e.getMessage());
        }
    }

    private static String coalesce(final String source) {
        return source == null || source.isBlank() ? "unknown" : source;
    }
}