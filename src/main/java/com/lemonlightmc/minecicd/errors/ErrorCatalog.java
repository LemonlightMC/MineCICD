package com.lemonlightmc.minecicd.errors;

/**
 * Maps common failure signatures to an actionable fix suggestion, surfaced as
 * the {@code {suggestion}} placeholder in failure messages. Pure logic,
 * unit-testable.
 */
public final class ErrorCatalog {

    private ErrorCatalog() {
    }

    /**
     * @return a short suggested fix command, or an empty string when no known
     *         signature matches
     */
    public static String suggest(final String message) {
        if (message == null) {
            return "";
        }
        final String m = message.toLowerCase();
        if (m.contains("pullaborted") || (m.contains("unpushed") && m.contains("changes"))) {
            return "Push local changes, undo them, or run '/minecicd pull force'";
        }
        if (m.contains("conflict") || m.contains("merge")) {
            return "Run '/minecicd resolve merge-abort' and try '/minecicd pull' again";
        }
        if (m.contains("not authorized") || m.contains("401") || m.contains("403")
                || m.contains("authentication") || m.contains("auth")) {
            return "Check your git token: it needs 'contents: read + write' and must be set in git.user/git.pass";
        }
        if (m.contains("ssh") && (m.contains("host key") || m.contains("fingerprint") || m.contains("verify"))) {
            return "Add the server's SSH public key as a deploy key, then run 'ssh-keyscan <host>' to verify";
        }
        if (m.contains("rejected") || m.contains("push rejected") || m.contains("non-fast-forward")) {
            return "Fetch first, then push: run '/minecicd pull' to sync before pushing";
        }
        if (m.contains("invalid date")) {
            return "Use the format: dd.MM.yyyy HH:mm:ss (e.g. 01.01.2026 03:00:00)";
        }
        if (m.contains("invalid commit") || m.contains("unknown revision") || m.contains("not found")) {
            return "Provide a commit as a 40-character hash or full URL. See '/minecicd log'";
        }
        if (m.contains("no remote repository") || m.contains("git.repo")) {
            return "Set git.repo in plugins/MineCICD/config.yml, then run '/minecicd reload'";
        }
        if (m.contains(".git") && m.contains("not")) {
            return "Run '/minecicd init' (or '/minecicd pull' to initialize from the remote)";
        }
        if (m.contains("resolvable") || m.contains("timeout") || m.contains("unreachable")) {
            return "The remote is unreachable. Check the repository URL and that the server has network access";
        }
        if (m.contains("refusing") || m.contains("monorepo")) {
            return "This is a monorepo checkout managed by a parent repository; do not deinit here";
        }
        return "";
    }

    public static String suggest(final Throwable t) {
        return suggest(rootMessage(t));
    }

    private static String rootMessage(final Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}