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

    public boolean add(final String entry) {
        final String normalized = entry.trim();
        final List<String> lines = lines();
        final int begin = lines.indexOf(BEGIN_MARKER);
        final int end = begin < 0 ? -1 : lines.indexOf(END_MARKER);
        if (begin < 0 || end < begin) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add(BEGIN_MARKER);
            lines.add(normalized);
            lines.add(END_MARKER);
            write(lines);
            return true;
        }
        boolean exists = false;
        for (int i = begin + 1; i < end; i++) {
            if (lines.get(i).trim().equals(normalized)) {
                exists = true;
                break;
            }
        }
        if (exists) {
            return false;
        }
        lines.add(end, normalized);
        write(lines);
        return true;
    }

    public boolean remove(final String entry) {
        final String normalized = entry.trim();
        final List<String> lines = lines();
        final int begin = lines.indexOf(BEGIN_MARKER);
        int end = begin < 0 ? -1 : lines.indexOf(END_MARKER);
        if (begin < 0 || end < begin) {
            return false;
        }
        boolean removed = false;
        for (int i = begin + 1; i < end; i++) {
            if (lines.get(i).trim().equals(normalized)) {
                lines.remove(i);
                removed = true;
                end--;
                i--;
            }
        }
        if (removed) {
            write(lines);
        }
        return removed;
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