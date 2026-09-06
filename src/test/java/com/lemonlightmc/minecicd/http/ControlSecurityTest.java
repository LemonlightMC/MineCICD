package com.lemonlightmc.minecicd.http;

import com.lemonlightmc.minecicd.MineCICDConfig;
import com.lemonlightmc.minecicd.git.CommitActions;
import com.lemonlightmc.minecicd.git.CommitActions.Action;
import com.lemonlightmc.minecicd.git.CommitActions.ActionType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlSecurityTest {

    private ControlSecurity security() {
        return new ControlSecurity(300, new MineCICDConfig.Actions(
                EnumSet.of(ActionType.PULL, ActionType.PUSH, ActionType.RESTART,
                        ActionType.RELOAD_PLUGIN, ActionType.COMMAND, ActionType.SCRIPT),
                Set.of("say", "save-all"),
                Set.of("deploy", "backup.sh")));
    }

    private static Action action(ActionType type, String arg) {
        return new Action(type, arg);
    }

    @Test
    void validCommandPassesAllowlist() {
        assertDoesNotThrow(() -> security().validateActions(
                List.of(action(ActionType.COMMAND, "say hello"))));
    }

    @Test
    void commandOutsideAllowlistRejected() {
        assertThrows(ControlSecurity.RejectException.class, () -> security().validateActions(
                List.of(action(ActionType.COMMAND, "ban steve"))));
    }

    @Test
    void exactNameOnly() {
        // allowing "say" must not allow "say-ip" or similar
        assertThrows(ControlSecurity.RejectException.class, () -> security().validateActions(
                List.of(action(ActionType.COMMAND, "say-ip x"))));
    }

    @Test
    void disabledActionRejected() {
        assertThrows(ControlSecurity.RejectException.class, () -> security().validateActions(
                List.of(action(ActionType.GLOBAL_RELOAD, null))));
    }

    @Test
    void scriptPathTraversalRejected() {
        assertThrows(ControlSecurity.RejectException.class, () -> security().validateActions(
                List.of(action(ActionType.SCRIPT, "../evil"))));
        assertThrows(ControlSecurity.RejectException.class, () -> security().validateActions(
                List.of(action(ActionType.SCRIPT, "/etc/passwd"))));
    }

    @Test
    void scriptAllowlistEnforced() {
        assertDoesNotThrow(() -> security().validateActions(
                List.of(action(ActionType.SCRIPT, "deploy"))));
        assertThrows(ControlSecurity.RejectException.class, () -> security().validateActions(
                List.of(action(ActionType.SCRIPT, "rmrf.sh"))));
    }

    @Test
    void hmacVerifiesAndDetectsTampering() {
        ControlSecurity sec = security();
        String secret = "a-very-strong-secret-value-0123456789";
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        String nonce = "nonce-1";
        String requestId = "req-1";
        String body = "{}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String bodyHash = sha256Hex(bodyBytes);
        String canonical = ts + "|" + nonce + "|" + requestId + "|" + bodyHash;
        String mac = ControlSecurity.hex(ControlSecurity.hmac(secret, canonical.getBytes(StandardCharsets.UTF_8)));

        assertDoesNotThrow(() -> sec.authenticate(secret, ts, nonce, requestId, mac, bodyBytes));

        // tampered body -> signature mismatch
        String badBody = "{\"x\":1}";
        assertThrows(ControlSecurity.RejectException.class, () -> sec.authenticate(
                secret, ts, nonce, requestId, mac, badBody.getBytes(StandardCharsets.UTF_8)));

        // replayed nonce rejected
        String nonce2 = "nonce-2";
        String canonical2 = ts + "|" + nonce2 + "|" + requestId + "|" + bodyHash;
        String mac2 = ControlSecurity.hex(ControlSecurity.hmac(secret, canonical2.getBytes(StandardCharsets.UTF_8)));
        assertDoesNotThrow(() -> sec.authenticate(secret, ts, nonce2, requestId, mac2, bodyBytes));
        assertThrows(ControlSecurity.RejectException.class, () -> sec.authenticate(
                secret, ts, nonce2, requestId, mac2, bodyBytes));
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return ControlSecurity.hex(md.digest(data == null ? new byte[0] : data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void futureTimestampRejected() {
        ControlSecurity sec = security();
        String secret = "a-very-strong-secret-value-0123456789";
        String farFuture = String.valueOf(System.currentTimeMillis() / 1000L + 100_000);
        byte[] bodyBytes = "body".getBytes(StandardCharsets.UTF_8);
        String bodyHash = sha256Hex(bodyBytes);
        String canonical = farFuture + "|n|r|" + bodyHash;
        String mac = ControlSecurity.hex(ControlSecurity.hmac(secret, canonical.getBytes(StandardCharsets.UTF_8)));
        assertThrows(ControlSecurity.RejectException.class, () -> sec.authenticate(
                secret, farFuture, "n", "r", mac, bodyBytes));
    }

    @Test
    void scriptNameValidation() {
        org.junit.jupiter.api.Assertions.assertTrue(ControlSecurity.isValidScriptName("backup.sh"));
        org.junit.jupiter.api.Assertions.assertTrue(ControlSecurity.isValidScriptName("deploy"));
        org.junit.jupiter.api.Assertions.assertFalse(ControlSecurity.isValidScriptName("../x"));
        org.junit.jupiter.api.Assertions.assertFalse(ControlSecurity.isValidScriptName("a/b"));
        org.junit.jupiter.api.Assertions.assertFalse(ControlSecurity.isValidScriptName(""));
    }
}