package com.lemonlightmc.minecicd.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.lemonlightmc.minecicd.services.AuditLogger.Source;

/**
 * Deployment lifecycle event bus. Emitters publish {@link Event}s; consumers
 * (audit log, analytics, Discord notifier) subscribe. Listeners run on the
 * emitting thread and must never throw.
 */
public final class DeploymentEvents {

    public enum Type {
        /** A deploy pipeline started (pull begun or control request accepted). */
        DEPLOY_STARTED,
        /** A deploy finished successfully and changed things. */
        DEPLOY_COMPLETED,
        /** A deploy failed. */
        DEPLOY_FAILED,
        /** An automatic rollback to the previous commit was executed. */
        ROLLBACK_EXECUTED
    }

    /**
     * @param type            the event type
     * @param timestampMillis epoch millis when the event happened
     * @param actor           human/identity responsible (player name, "scheduler",
     *                        "webhook", ...)
     * @param source          trigger class: "manual", "control-api", "scheduler",
     *                        "webhook", "health", "rollback"
     * @param message         human-readable detail (already secret-redacted)
     * @param requestId       control request id, or null
     * @param branch          git branch, or null
     * @param durationMillis  duration of the deploy, or 0 when unknown
     */
    public record Event(Type type, long timestampMillis, String actor,
            Source source, String message,
            String requestId, String branch, long durationMillis) {
        public Map<String, String> meta() {
            return Map.of();
        }
    }

    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(final Consumer<Event> listener) {
        listeners.add(listener);
    }

    public void emit(final Type type, final String actor, final Source source, final String message) {
        emit(new Event(type, System.currentTimeMillis(), actor, source, message, null, null, 0L));
    }

    public void emit(final Type type, final String actor, final Source source, final String message,
            final String requestId, final String branch, final long durationMillis) {
        emit(new Event(type, System.currentTimeMillis(), actor, source, message, requestId, branch, durationMillis));
    }

    public void emit(final Event event) {
        for (final Consumer<Event> listener : listeners) {
            try {
                listener.accept(event);
            } catch (final Exception ignored) {
                // a misbehaving listener must never break the deploy pipeline
            }
        }
    }
}