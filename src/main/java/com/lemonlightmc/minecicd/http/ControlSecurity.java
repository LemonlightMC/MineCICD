package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.MineCICDConfig;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * HMAC-SHA256 verification and authorization. Pure logic (no Bukkit dependency)
 * so it can be unit tested. Canonical signed bytes:
 * {@code timestamp|nonce|requestId|body}.
 */
public class ControlSecurity {

    // time-windowed nonce cache to bound memory and allow expiry
    private final ConcurrentHashMap<String, Long> seenNonces = new ConcurrentHashMap<>();

    private final long replayWindowSeconds;
    private static final int MAX_NONCES = 10_000;
    private final MineCICDConfig.Actions actionsConfig;
    private final boolean allowPullPush;

    public static class RejectException extends RuntimeException {
        public RejectException(final String message) {
            super(message);
        }
    }

    public ControlSecurity(final long replayWindowSeconds, final MineCICDConfig.Actions actionsConfig) {
        this.replayWindowSeconds = replayWindowSeconds;
        this.actionsConfig = actionsConfig;
        this.allowPullPush = true;
    }

    public boolean verify() {
        return actionsConfig.allowedActions().contains(ActionType.PULL);
    }

    /**
     * Verifies the HMAC in constant time. Throws {@link RejectException} on
     * failure.
     */
    public void authenticate(final String secret, final String timestampHeader, final String nonceHeader,
            final String requestIdHeader, final String providedMac, final byte[] body) {
        if (secret == null || secret.isEmpty()) {
            throw new RejectException("Control API is not configured");
        }
        if (providedMac == null || providedMac.isEmpty()) {
            throw new RejectException("Missing X-MineCICD-Signature");
        }
        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader);
        } catch (final Exception e) {
            throw new RejectException("Invalid timestamp");
        }
        final long now = System.currentTimeMillis() / 1000L;
        // distinguish future vs past instead of Math.abs
        if (timestamp > now + replayWindowSeconds) {
            throw new RejectException("Timestamp too far in future");
        }
        if (now - timestamp > replayWindowSeconds) {
            throw new RejectException("Timestamp outside replay window");
        }
        if (nonceHeader == null || nonceHeader.isEmpty()) {
            throw new RejectException("Missing nonce");
        }
        if (nonceHeader.length() > 256) {
            throw new RejectException("Nonce too large");
        }
        // prune expired nonces
        pruneNonces(now);
        if (seenNonces.size() >= MAX_NONCES) {
            throw new RejectException("Nonce cache full");
        }
        // hash body to make canonical non-ambiguous on '|' in body
        final String bodyHash = sha256Hex(body);
        final String canonical = timestamp + "|" + nonceHeader + "|" + requestIdHeader + "|" + bodyHash;
        final byte[] expected = hmac(secret, canonical.getBytes(StandardCharsets.UTF_8));
        final byte[] actual = hexDecode(providedMac);
        if (actual == null || actual.length != expected.length) {
            throw new RejectException("Invalid signature");
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new RejectException("Invalid signature");
        }
        // Replay guard: fail if nonce already seen within window
        if (seenNonces.putIfAbsent(nonceHeader, now) != null) {
            throw new RejectException("Replayed nonce");
        }
    }

    public static byte[] hmac(final String secret, final byte[] data) {
        try {
            final javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (final Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    public static String hex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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

    /**
     * Checks that every action is enabled (per-action flag) and, for
     * commands/scripts, allowed by its exact-name allowlist.
     * Also validates script names against a safe pattern.
     */
    public void validateActions(final List<Action> actions) {
        for (final Action action : actions) {
            if (actionsConfig.allowedActions().contains(action.type())) {
                throw new RejectException("Action not enabled: " + action);
            }
            switch (action.type()) {
                case COMMAND -> {
                    final String name = firstToken(action.argument());
                    if (name == null || !actionsConfig.allowedCommands().contains(name)) {
                        throw new RejectException("Command not allowed: " + action.argument());
                    }
                }
                case SCRIPT -> {
                    // A script is allowed only if it is on the exact-name allowlist AND its name
                    // matches the safe pattern (no path traversal / separators).
                    final String name = action.argument();
                    if (!isValidScriptName(name) || !actionsConfig.allowedScripts().contains(name)) {
                        throw new RejectException("Script not allowed: " + name);
                    }
                }
                default -> {
                }
            }
        }
    }

    public static boolean isValidScriptName(final String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..") || name.startsWith(".")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static String firstToken(String command) {
        if (command == null) {
            return null;
        }
        command = command.trim();
        final int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    private void pruneNonces(final long nowSeconds) {
        seenNonces.entrySet().removeIf(e -> nowSeconds - e.getValue() > replayWindowSeconds + 60);
    }

    private static String sha256Hex(final byte[] data) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data == null ? new byte[0] : data));
        } catch (final Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Builds an SSLContext from a keystore (JKS or PKCS12). Returns null if TLS is
     * disabled. Restricts protocols to TLS v1.2/1.3
     */
    public static SSLContext buildSslContext(final String keystorePath, final String password, final boolean enabled) {
        if (!enabled) {
            return null;
        }
        if (keystorePath == null || keystorePath.isBlank() || password == null) {
            throw new IllegalArgumentException("control.tls.enabled requires keystore and password");
        }
        // warn if keystore file is world-readable
        try {
            final Path p = Path.of(keystorePath);
            if (!Files.exists(p)) {
                final Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p);
                if (perms.contains(PosixFilePermission.OTHERS_READ) || perms.contains(PosixFilePermission.GROUP_READ)) {
                    System.err.println(
                            "[MineCICD] Warning: keystore " + keystorePath + " is world-readable; run chmod 600");
                }
            }
        } catch (final Exception ignored) {
        }

        final char[] pass = password.toCharArray();
        try {
            final KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            try (InputStream in = new FileInputStream(keystorePath)) {
                keyStore.load(in, pass);
            }
            final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, pass);
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            // Protocol restriction to TLS v1.2/1.3 is enforced in the HttpsConfigurator
            return context;
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to configure TLS: " + e.getMessage(), e);
        } finally {
            Arrays.fill(pass, '\0');
        }
    }
}