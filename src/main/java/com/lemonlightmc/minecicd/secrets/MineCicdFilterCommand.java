package com.lemonlightmc.minecicd.secrets;

import org.eclipse.jgit.attributes.FilterCommand;

import com.lemonlightmc.minecicd.secrets.SecretManager.SecretMapping;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JGit builtin clean/smudge filter command. Runs inside the plugin JVM instead
 * of spawning a {@code java -jar} process per filtered file.
 * <p>
 * Each instance is created by {@link MineCicdFilterFactory} for exactly one
 * file and carries only that file's mapping entries, so a secret from one file
 * is never substituted into another even though JGit's builtin filter
 * API does not pass the path of the file being filtered.
 * <p>
 * Stream contract (per {@link FilterCommand}): {@link #run()} is called in a
 * loop until it returns -1; the implementation owns the {@code in}/{@code out}
 * streams and must close them both on success and on failure. Any error is
 * rethrown as an {@link IOException}, which JGit propagates as a
 * {@link org.eclipse.jgit.api.errors.FilterFailedException} aborting the git
 * operation (fail-close).
 */
public final class MineCicdFilterCommand extends FilterCommand {

    /** Filter direction. */
    public enum Direction {
        /** Replace real secret values with placeholders (working tree -> index). */
        CLEAN,
        /**
         * Replace placeholders with real secret values (index/blob -> working tree).
         */
        SMUDGE
    }

    private final Direction direction;
    private final String file;
    private final List<SecretMapping> scopedMapping;
    private boolean finished;

    /**
     * @param direction     whether to clean or smudge
     * @param file          the repo-relative path of the file being filtered
     *                      (forward slashes), used to scope substitution
     * @param scopedMapping mapping entries belonging to {@code file} only
     * @param in            content to transform
     * @param out           destination for the transformed content
     */
    public MineCicdFilterCommand(final Direction direction, final String file,
            final List<SecretMapping> scopedMapping,
            final InputStream in, final OutputStream out) {
        super(in, out);
        this.direction = direction;
        this.file = file;
        this.scopedMapping = scopedMapping != null ? scopedMapping : List.of();
    }

    /**
     * Reads all input, applies the clean/smudge transformation with the
     * file-scoped mapping, writes the result and returns -1 (all input
     * consumed). Closes both streams in all paths.
     */
    @Override
    public int run() throws IOException {
        if (finished) {
            return -1;
        }
        finished = true;
        try {
            final String input = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            final String output = switch (direction) {
                case CLEAN -> clean(input, scopedMapping, file);
                case SMUDGE -> smudge(input, scopedMapping, file);
            };
            out.write(output.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return -1;
        } finally {
            try {
                in.close();
            } finally {
                out.close();
            }
        }
    }

    /**
     * Clean that only substitutes secrets belonging to the {@code targetFile} being
     * filtered.
     * Mapping entries whose file prefix does not match the target are ignored, so a
     * secret
     * or placeholder belonging to one file is never written into another file.
     */
    private static String clean(final String input, final List<SecretMapping> mappings, final String targetFile) {
        final String normalizedTarget = targetFile != null ? targetFile.replace('\\', '/') : null;
        String current = input;
        for (final SecretMapping entry : mappings) {
            if (!normalizedTarget.equals(entry.file().replace('\\', '/'))) {
                continue;
            }
            if (entry.isPresent()) {
                current = current.replace(entry.value(), entry.placeholder());
            }
        }
        return current;
    }

    /**
     * Smudge that only restores secrets belonging to the {@code targetFile} being
     * filtered.
     * Placeholders belonging to other files are left untouched, preventing one
     * file's secret
     * from being inserted into another.
     */
    private static String smudge(final String input, final List<SecretMapping> mappings, final String targetFile) {
        final String normalizedTarget = targetFile != null ? targetFile.replace('\\', '/') : null;
        String current = input;
        for (final SecretMapping entry : mappings) {
            if (!normalizedTarget.equals(entry.file().replace('\\', '/'))) {
                continue;
            }
            current = current.replace(entry.placeholder(), entry.value());
        }
        return current;
    }
}