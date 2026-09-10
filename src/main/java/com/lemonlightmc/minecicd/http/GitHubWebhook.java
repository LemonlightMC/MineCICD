package com.lemonlightmc.minecicd.http;

import java.security.MessageDigest;

/**
 * GitHub webhook signature verification (pure, unit-testable). GitHub signs the
 * raw request body with HMAC-SHA256 using the webhook secret and sends it in
 * the {@code X-Hub-Signature-256} header as {@code sha256=<hex>}.
 */
public final class GitHubWebhook {

    private GitHubWebhook() {
    }

    public static boolean verifySignature(final String secret, final String signatureHeader, final byte[] body) {
        if (secret == null || secret.isEmpty() || signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            return false;
        }
        final byte[] expected = ControlSecurity.hmac(secret, body == null ? new byte[0] : body);
        final byte[] actual = hexDecode(signatureHeader.substring(7));
        if (actual == null || actual.length != expected.length) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * Resolves a push event's {@code ref} (e.g. {@code refs/heads/master}) to a
     * bare branch name, or {@code null} when the ref is not a branch.
     */
    public static String branchForRef(final String ref) {
        if (ref == null || !ref.startsWith("refs/heads/")) {
            return null;
        }
        final String branch = ref.substring("refs/heads/".length());
        return branch.isEmpty() ? null : branch;
    }

    private static byte[] hexDecode(final String hex) {
        if (hex.length() % 2 != 0) {
            return null;
        }
        try {
            final byte[] out = new byte[hex.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}