package com.lemonlightmc.minecicd.external;

import com.lemonlightmc.minecicd.MineCICDConfig.Discord;
import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.Action;
import com.lemonlightmc.minecicd.data.Actor;
import com.lemonlightmc.minecicd.data.Results.DeployResult;
import com.lemonlightmc.minecicd.util.Utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Outbound-only Discord webhook sender. Subscribes to deployment events and
 * POSTs JSON embeds to the configured webhook URL on its own executor so the
 * worker queue is never blocked. Never logs or leaks the webhook URL.
 */
public class DiscordNotifier {

    private final Discord config;

    public DiscordNotifier() {
        this.config = MineCICDApi.plugin().config().discord();
        validateUrl();
    }

    private void validateUrl() {
        final String url = config.url();
        if (!config.enabled()) {
            return;
        }
        if (url == null || url.isBlank()) {
            MineCICDApi.logger().warning("notifications.discord.enabled is true but url is empty; webhook disabled.");
            return;
        }
        if (!url.startsWith("https://discord.com/api/webhooks/")
                && !url.startsWith("https://ptb.discord.com/api/webhooks/")
                && !url.startsWith("https://canary.discord.com/api/webhooks/")) {
            MineCICDApi.logger().warning("notifications.discord.url does not look like a Discord webhook URL; "
                    + "notifications will still be attempted.");
        }
    }

    public void execute(final Actor actor, final Action action, final DeployResult result) {
        execute(actor, action, result, null, 0);
    }

    public void execute(final Actor actor, final Action action, final DeployResult result, final String requestId,
            final long millis) {
        final String name = action.value();
        if (name == null || !config.events().contains(name)) {
            return;
        }
        final String message = MineCICDApi.secretService().redact("");

        final String payload = buildPayload(config,
                action,
                result,
                message,
                requestId, millis);
        send(payload);
    }

    private void send(final String payload) {
        final String url = config.url();
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
                MineCICDApi.logger().warning("Discord webhook returned HTTP " + response.statusCode());
            }
        } catch (final Exception e) {
            MineCICDApi.logger().warning("Discord webhook send failed: " + e.getMessage());
        }
    }

    static String buildPayload(final Discord config, final Action action, final DeployResult result,
            final String message, final String requestId, final long millis) {

        final StringBuilder description = new StringBuilder(message);
        if (requestId != null && !requestId.isBlank()) {
            description.append("\nrequest: `").append(requestId).append("`");
        }
        if (millis > 0) {
            description.append("\n**took:** ").append(durationText(millis));
        }
        final String content = shouldPing(action,
                result)
                && config
                        .pingRoleId() != null
                && !config.pingRoleId().isBlank()
                        ? "<@&" + config.pingRoleId() + ">"
                        : "";

        final StringBuilder sb = new StringBuilder();
        sb.append('{');
        if (!content.isEmpty()) {
            sb.append("\"content\":\"").append(Utils.jsonEscape(content)).append("\",");
        }
        if (config.username() != null && !config.username().isBlank()) {
            sb.append("\"username\":\"").append(Utils.jsonEscape(config.username())).append("\",");
        }
        if (config.avatarUrl() != null && !config.avatarUrl().isBlank()) {
            sb.append("\"avatar_url\":\"").append(Utils.jsonEscape(config.avatarUrl())).append("\",");
        }
        sb.append("\"embeds\":[{");
        sb.append("\"title\":\"").append(Utils.jsonEscape(getResultTitle(result))).append("\",");
        sb.append("\"description\":\"").append(Utils.jsonEscape(description.toString())).append("\",");
        sb.append("\"color\":").append(getResultColor(result));
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

    private static boolean shouldPing(final Action action, final DeployResult result) {
        return result == DeployResult.FAILED || result == DeployResult.ROLLBACK;
    }

    private static String getResultTitle(final DeployResult result) {
        return switch (result) {
            case SUCCESS -> "Deploy succeeded";
            case NO_CHANGES -> "No Changes";
            case FAILED -> "Deploy failed";
            case ROLLBACK -> "Auto-rollback executed";
            default -> "MineCICD";
        };
    }

    private static String getResultColor(final DeployResult result) {
        return switch (result) {
            case SUCCESS -> "0x00FF00"; // Green
            case NO_CHANGES -> "0xFFFF00"; // Yellow
            case FAILED -> "0xFF0000"; // Red
            case ROLLBACK -> "0xFFA500"; // Orange
            default -> "0x808080"; // Gray
        };
    }
}