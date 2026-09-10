package com.lemonlightmc.minecicd.notify;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.events.DeploymentEvents.Event;
import com.lemonlightmc.minecicd.events.DeploymentEvents.Type;
import com.lemonlightmc.minecicd.util.Threads;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Outbound-only Discord webhook sender. Subscribes to deployment events and
 * POSTs JSON embeds to the configured webhook URL on its own executor so the
 * worker queue is never blocked. Never logs or leaks the webhook URL.
 */
public class DiscordNotifier {

    private static final int COLOR_START = 0x3498DB;
    private static final int COLOR_SUCCESS = 0x2ECC71;
    private static final int COLOR_FAILURE = 0xE74C3C;
    private static final int COLOR_ROLLBACK = 0xE67E22;

    private final MineCICD plugin;
    private final Set<String> secrets = new HashSet<>();
    private final ExecutorService executor;

    public DiscordNotifier(final MineCICD plugin) {
        this.plugin = plugin;
        this.executor = Threads.dameonThreadPool("minecicd-discord", 1);
        collectSecrets();
        validateUrl();
        plugin.events().subscribe(this::onEvent);
    }

    private void collectSecrets() {
        try {
            addSecret(plugin.config().git().pass());
            addSecret(plugin.config().control().secret());
            addSecret(plugin.config().control().githubWebhook().secret());
        } catch (final Exception ignored) {
        }
    }

    private void addSecret(final String value) {
        if (value != null && value.length() >= 4) {
            secrets.add(value);
        }
    }

    private void validateUrl() {
        final String url = plugin.config().discord().url();
        if (!plugin.config().discord().enabled()) {
            return;
        }
        if (url == null || url.isBlank()) {
            plugin.getLogger().warning("notifications.discord.enabled is true but url is empty; webhook disabled.");
            return;
        }
        if (!url.startsWith("https://discord.com/api/webhooks/")
                && !url.startsWith("https://ptb.discord.com/api/webhooks/")
                && !url.startsWith("https://canary.discord.com/api/webhooks/")) {
            plugin.getLogger().warning("notifications.discord.url does not look like a Discord webhook URL; "
                    + "notifications will still be attempted.");
        }
    }

    public void refresh() {
        secrets.clear();
        collectSecrets();
    }

    public void onEvent(final Event event) {
        final String name = discordEventName(event.type());
        if (name == null || !plugin.config().discord().events().contains(name)) {
            return;
        }
        final Event redacted = new Event(event.type(), event.timestampMillis(), event.actor(), event.source(),
                redact(event.message(), secrets), event.requestId(), event.branch(), event.durationMillis());
        final String payload = buildPayload(redacted, name, plugin.config().discord().username(),
                plugin.config().discord().avatarUrl(), plugin.config().discord().pingRoleId());
        executor.execute(() -> send(payload));
    }

    private void send(final String payload) {
        final String url = plugin.config().discord().url();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400 && response.statusCode() != 429) {
                plugin.getLogger().warning("Discord webhook returned HTTP " + response.statusCode());
            }
        } catch (final Exception e) {
            plugin.getLogger().warning("Discord webhook send failed: " + e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    static String discordEventName(final Type type) {
        return switch (type) {
            case DEPLOY_STARTED -> "deploy-start";
            case DEPLOY_COMPLETED -> "deploy-success";
            case DEPLOY_FAILED -> "deploy-fail";
            case ROLLBACK_EXECUTED -> "rollback";
        };
    }

    /** Builds the Discord JSON payload. Testable, no plugin state needed. */
    static String buildPayload(final Event event, final String eventName, final String username,
            final String avatarUrl, final String pingRoleId) {
        final String title = switch (eventName) {
            case "deploy-start" -> "Deploy started";
            case "deploy-success" -> "Deploy succeeded";
            case "deploy-fail" -> "Deploy failed";
            case "rollback" -> "Auto-rollback executed";
            default -> "MineCICD";
        };
        final int color = switch (eventName) {
            case "deploy-start" -> COLOR_START;
            case "deploy-success" -> COLOR_SUCCESS;
            case "deploy-fail" -> COLOR_FAILURE;
            case "rollback" -> COLOR_ROLLBACK;
            default -> COLOR_START;
        };
        final StringBuilder description = new StringBuilder(redact(event.message() == null ? "" : event.message()));
        if (event.requestId() != null && !event.requestId().isBlank()) {
            description.append("\nrequest: `").append(event.requestId()).append("`");
        }
        if (event.durationMillis() > 0) {
            description.append("\n**took:** ").append(durationText(event.durationMillis()));
        }
        final String content = (("rollback".equals(eventName) || "deploy-fail".equals(eventName))
                && pingRoleId != null && !pingRoleId.isBlank())
                        ? "<@&" + pingRoleId + ">"
                        : "";

        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        if (!content.isEmpty()) {
            sb.append("\"content\":\"").append(jsonEscape(content)).append("\",");
        }
        if (username != null && !username.isBlank()) {
            sb.append("\"username\":\"").append(jsonEscape(username)).append("\",");
        }
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            sb.append("\"avatar_url\":\"").append(jsonEscape(avatarUrl)).append("\",");
        }
        sb.append("\"embeds\":[{");
        sb.append("\"title\":\"").append(jsonEscape(title)).append("\",");
        sb.append("\"description\":\"").append(jsonEscape(description.toString())).append("\",");
        sb.append("\"color\":").append(color);
        sb.append("}]}");
        return sb.toString();
    }

    private static String durationText(final long millis) {
        final long seconds = millis / 1000;
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    static String redact(final String message) {
        return message == null ? "" : message;
    }

    static String redact(final String message, final Set<String> secrets) {
        if (message == null) {
            return "";
        }
        String out = message;
        if (secrets != null) {
            for (final String s : secrets) {
                if (s != null && s.length() >= 4) {
                    out = out.replace(s, "***");
                }
            }
        }
        return out;
    }

    private static String jsonEscape(final String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}