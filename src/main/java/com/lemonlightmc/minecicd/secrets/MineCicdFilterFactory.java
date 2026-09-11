package com.lemonlightmc.minecicd.secrets;

import org.eclipse.jgit.attributes.FilterCommand;
import org.eclipse.jgit.attributes.FilterCommandFactory;
import org.eclipse.jgit.lib.Repository;

import com.lemonlightmc.minecicd.services.SecretFilterService.SecretMapping;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Creates {@link MineCicdFilterCommand} instances for one specific secrets.yml
 * file. The submap passed to the command is built from the mapping supplier at
 * creation time, so a {@code secrets.yml} reload (which never re-registers the
 * factory) takes effect immediately.
 * <p>
 * {@link #create} MUST NOT throw: on the smudge path JGit treats a
 * create-time {@link java.io.IOException} as "builtin filter unavailable" and,
 * when not mandatory, silently passes the raw (placeholder) content through to
 * the working tree. All transformation failures are deferred to
 * {@link MineCicdFilterCommand#run()}, where an exception aborts the git
 * operation (fail-close). A missing/empty mapping therefore yields an empty
 * submap (identity passthrough) rather than an exception.
 */
public final class MineCicdFilterFactory implements FilterCommandFactory {

    private final Supplier<List<SecretMapping>> mappingSupplier;
    private final String file;
    private final MineCicdFilterCommand.Direction direction;

    /**
     * @param mappingSupplier supplies the live secret mapping (may be null only
     *                        in tests; yields an identity-passthrough command)
     * @param file            repo-relative path of the file this factory serves
     *                        (separators normalized to '/')
     * @param direction       filter direction applied by created commands
     */
    public MineCicdFilterFactory(final Supplier<List<SecretMapping>> mappingSupplier, final String file,
            final MineCicdFilterCommand.Direction direction) {
        this.mappingSupplier = mappingSupplier;
        this.file = file;
        this.direction = direction;
    }

    @Override
    public FilterCommand create(final Repository db, final InputStream in, final OutputStream out) {
        return new MineCicdFilterCommand(direction, file, scopedMapping(), in, out);
    }

    /**
     * Filters a mapping down to the entries whose file prefix matches
     * {@code file}, preserving insertion order. This is the S-07 scoping that
     * keeps one file's secrets out of every other file, applied per driver.
     */
    private List<SecretMapping> scopedMapping() {
        try {
            final List<SecretMapping> mappings = mappingSupplier.get();
            final List<SecretMapping> submappings = new ArrayList<>();
            for (final SecretMapping entry : mappings) {
                if (file.equals(entry.normalizedFile())) {
                    submappings.add(entry);
                }
            }
            return submappings;
        } catch (final RuntimeException ignored) {
            // Mapping may be mid-reload; never propagate here (see class javadoc).
            return List.of();
        }
    }
}