package com.lemonlightmc.minecicd.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GitIgnoreEditor {

    public static final String BEGIN_MARKER = "# MineCICD GITIGNORE PART BEGIN MARKER";
    public static final String END_MARKER = "# MineCICD GITIGNORE PART END MARKER";

    private final Path ignoreFile;

    public GitIgnoreEditor(final Path serverRoot) {
        this.ignoreFile = serverRoot.resolve(".gitignore");
    }

    public List<String> readEntries() {
        final List<String> lines = lines();
        final int begin = lines.indexOf(BEGIN_MARKER);
        final int end = begin < 0 ? -1 : lines.indexOf(END_MARKER);
        if (begin < 0 || end < begin) {
            return new ArrayList<>();
        }
        final List<String> entries = new ArrayList<>();
        for (int i = begin + 1; i < end; i++) {
            final String line = lines.get(i).trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                entries.add(line);
            }
        }
        return entries;
    }

    /**
     * Adds a whitelist negation so the given path (or the whole server root when
     * empty / {@code "."}) becomes trackable by git despite the default-ignore
     * template. Stores {@code "!path/**"} for directories (trailing {@code /}),
     * {@code "!path"} for files, and {@code "!*"} for the root. Drops any plain
     * exclusion lines that refer to the same path.
     *
     * @param path normalized path; empty means the server root
     * @return {@code true} if the managed section changed
     */
    public boolean track(final String path) {
        final String canonical = canonicalOf(path);
        if (canonical.isEmpty()) {
            return trackAll();
        }
        final List<String> lines = lines();
        int begin = lines.indexOf(BEGIN_MARKER);
        int end = begin < 0 ? -1 : lines.indexOf(END_MARKER);
        if (begin < 0 || end < begin) {
            insertManaged(lines, whitelistEntry(path));
            return true;
        }
        boolean changed = false;
        for (int i = begin + 1; i < end; i++) {
            final String line = lines.get(i).trim();
            if (isWhitelist(line) || line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (canonicalOf(line).equals(canonical)) {
                lines.remove(i);
                changed = true;
                end--;
                i--;
            }
        }
        if (containsWhitelist(lines, begin, end, canonical)) {
            return changed;
        }
        lines.add(end, whitelistEntry(path));
        write(lines);
        return true;
    }

    /**
     * Removes the whitelist negation(s) for the given path (or the whole root
     * when empty / {@code "."}), so the path falls back to being ignored.
     *
     * @param path normalized path; empty means the server root
     * @return {@code true} if the managed section changed
     */
    public boolean untrack(final String path) {
        if (canonicalOf(path).isEmpty()) {
            return untrackAll();
        }
        final List<String> lines = lines();
        final int begin = lines.indexOf(BEGIN_MARKER);
        int end = begin < 0 ? -1 : lines.indexOf(END_MARKER);
        if (begin < 0 || end < begin) {
            return false;
        }
        final String canonical = canonicalOf(path);
        boolean changed = false;
        for (int i = begin + 1; i < end; i++) {
            if (isWhitelist(lines.get(i)) && canonicalOf(lines.get(i)).equals(canonical)) {
                lines.remove(i);
                changed = true;
                end--;
                i--;
            }
        }
        if (changed) {
            write(lines);
        }
        return changed;
    }

    /**
     * Tracks every file in the server root by negating the template's default
     * ignore-all rule ({@code "!*"}); priority exclusions still apply.
     *
     * @return {@code true} if the managed section changed
     */
    public boolean trackAll() {
        return trackEntry("!*");
    }

    /**
     * Reverts {@link #trackAll()}.
     *
     * @return {@code true} if the managed section changed
     */
    public boolean untrackAll() {
        final List<String> lines = lines();
        final int begin = lines.indexOf(BEGIN_MARKER);
        int end = begin < 0 ? -1 : lines.indexOf(END_MARKER);
        if (begin < 0 || end < begin) {
            return false;
        }
        boolean changed = false;
        for (int i = begin + 1; i < end; i++) {
            final String trimmed = lines.get(i).trim();
            if (trimmed.equals("!*") || trimmed.equals("!**")) {
                lines.remove(i);
                changed = true;
                end--;
                i--;
            }
        }
        if (changed) {
            write(lines);
        }
        return changed;
    }

    /**
     * Returns whether a whitelist negation for the given path is present.
     */
    public boolean isTracked(final String path) {
        final String canonical = canonicalOf(path);
        final List<String> lines = lines();
        final int begin = lines.indexOf(BEGIN_MARKER);
        final int end = begin < 0 ? -1 : lines.indexOf(END_MARKER);
        if (begin < 0 || end < begin) {
            return false;
        }
        return containsWhitelist(lines, begin, end, canonical);
    }

    private boolean trackEntry(final String entry) {
        final List<String> lines = lines();
        final int begin = lines.indexOf(BEGIN_MARKER);
        final int end = begin < 0 ? -1 : lines.indexOf(END_MARKER);
        if (begin < 0 || end < begin) {
            insertManaged(lines, entry);
            return true;
        }
        for (int i = begin + 1; i < end; i++) {
            if (lines.get(i).trim().equals(entry)) {
                return false;
            }
        }
        lines.add(end, entry);
        write(lines);
        return true;
    }

    private boolean containsWhitelist(final List<String> lines, final int begin, final int end, final String canonical) {
        for (int i = begin + 1; i < end; i++) {
            final String line = lines.get(i).trim();
            if (isWhitelist(line) && canonicalOf(line).equals(canonical)) {
                return true;
            }
        }
        return false;
    }

    private void insertManaged(final List<String> lines, final String entry) {
        if (!lines.isEmpty()) {
            lines.add("");
        }
        lines.add(BEGIN_MARKER);
        lines.add(entry);
        lines.add(END_MARKER);
        write(lines);
    }

    private static boolean isWhitelist(final String line) {
        final String t = line.trim();
        return t.startsWith("!") && !t.equals("!*/") && !t.startsWith("!#");
    }

    private static String whitelistEntry(final String path) {
        String p = path.trim();
        final boolean directory = p.endsWith("/");
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return directory ? "!" + p + "/**" : "!" + p;
    }

    private static String canonicalOf(final String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("!")) {
            s = s.substring(1).trim();
        }
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        if (s.endsWith("/**")) {
            s = s.substring(0, s.length() - 3);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.equals("*") || s.equals("**")) {
            return "";
        }
        return s;
    }

    private List<String> lines() {
        if (!Files.exists(ignoreFile)) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Files.readAllLines(ignoreFile, StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to read .gitignore: " + e.getMessage(), e);
        }
    }

    private void write(final List<String> lines) {
        try {
            final String content = String.join("\n", lines) + "\n";
            Files.write(ignoreFile, content.getBytes(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new IllegalStateException("Unable to write .gitignore: " + e.getMessage(), e);
        }
    }
}