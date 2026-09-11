package com.lemonlightmc.minecicd.notify;

import com.lemonlightmc.minecicd.events.DeploymentEvents.Event;
import com.lemonlightmc.minecicd.events.DeploymentEvents.Type;
import com.lemonlightmc.minecicd.external.DiscordNotifier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordNotifierPayloadTest {

    private static Event event(final Type type, final String message, final long duration) {
        return new Event(type, System.currentTimeMillis(), "steve", "manual", message,
                "req-123", "master", duration);
    }

    @Test
    void successPayloadIsValidJsonShape() {
        final String payload = DiscordNotifier.buildPayload(
                event(Type.DEPLOY_COMPLETED, "pull applied 3 commit(s)", 5000),
                "deploy-success", "MineCICD", "", null);
        assertTrue(payload.startsWith("{"));
        assertTrue(payload.contains("\"title\":\"Deploy succeeded\""));
        assertTrue(payload.contains("\"color\":3066993"));
        assertTrue(payload.contains("**took:** 5s"));
    }

    @Test
    void failurePayloadPingsRole() {
        final String payload = DiscordNotifier.buildPayload(
                event(Type.DEPLOY_FAILED, "action 'pull' failed", 0),
                "deploy-fail", "MineCICD", "", "123456789");
        assertTrue(payload.contains("\"content\":\"<@&123456789>\""));
        assertTrue(payload.contains("\"title\":\"Deploy failed\""));
    }

    @Test
    void rollbackPayloadHasRollbackTitle() {
        final String payload = DiscordNotifier.buildPayload(
                event(Type.ROLLBACK_EXECUTED, "Auto-rollback after: health check failed", 0),
                "rollback", "MineCICD", "", null);
        assertTrue(payload.contains("\"title\":\"Auto-rollback executed\""));
    }

    @Test
    void startPayloadHasStartTitle() {
        final String payload = DiscordNotifier.buildPayload(
                event(Type.DEPLOY_STARTED, "manual pull started", 0),
                "deploy-start", "MineCICD", "", null);
        assertTrue(payload.contains("\"title\":\"Deploy started\""));
    }

    @Test
    void htmlIsEscaped() {
        final String payload = DiscordNotifier.buildPayload(
                event(Type.DEPLOY_FAILED, "line one\nquote \" and backslash \\", 0),
                "deploy-fail", "MineCICD", "", null);
        assertFalse(payload.contains("\n"));
        assertTrue(payload.contains("\\n"));
    }
}