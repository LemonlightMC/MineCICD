package com.lemonlightmc.minecicd.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubWebhookTest {

    @Test
    void validSignatureAccepted() {
        final byte[] body = "{\"ref\":\"refs/heads/master\"}".getBytes(StandardCharsets.UTF_8);
        final String sig = "sha256=" + ControlSecurity.hex(ControlSecurity.hmac("webhook-secret", body));
        assertTrue(GitHubWebhook.verifySignature("webhook-secret", sig, body));
    }

    @Test
    void wrongSecretRejected() {
        final byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        final String sig = "sha256=" + ControlSecurity.hex(ControlSecurity.hmac("other-secret", body));
        assertFalse(GitHubWebhook.verifySignature("webhook-secret", sig, body));
    }

    @Test
    void missingOrMalformedHeaderRejected() {
        final byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        assertFalse(GitHubWebhook.verifySignature("secret", null, body));
        assertFalse(GitHubWebhook.verifySignature("secret", "sha256=nothex", body));
        assertFalse(GitHubWebhook.verifySignature("secret", "sha1=bogus", body));
        assertFalse(GitHubWebhook.verifySignature("", "sha256=deadbeef", body));
    }

    @Test
    void sigWithGarbageHexRejected() {
        final byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        final String sig = "sha256=zzzz";
        assertFalse(GitHubWebhook.verifySignature("secret", sig, body));
    }

    @Test
    void branchRefResolved() {
        assertEquals("master", GitHubWebhook.branchForRef("refs/heads/master"));
        assertEquals("dev/feature", GitHubWebhook.branchForRef("refs/heads/dev/feature"));
    }

    @Test
    void nonBranchRefRejected() {
        assertNull(GitHubWebhook.branchForRef("refs/tags/v1.0"));
        assertNull(GitHubWebhook.branchForRef("refs/heads/"));
        assertNull(GitHubWebhook.branchForRef(null));
    }
}