package com.lemonlightmc.minecicd.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.services.SecretFilterService;
import com.lemonlightmc.minecicd.services.SecretFilterService.SecretFileEntry;

public class GitConfigWriter {
  private static final String ATTR_FILE = ".gitattributes";
  private static final String CONFIG_FILE = "config";
  private static final String GIT_DIR = ".git";
  private static final String LINE_SEP = System.lineSeparator();
  private static final String FILTERS_BEGIN = "# MineCICD FILTERS BEGIN";
  private static final String FILTERS_END = "# MineCICD FILTERS END";

  // writes the secrets.yml file entries to .gitattributes and .git/config,
  // replacing any previous MineCICD-managed blocks
  // "<escaped-file> filter=minecicd-<hex>"" per file
  public static void writeAttributes(final List<SecretFileEntry> files) {
    final Path attributes = MineCICDApi.serverRoot().resolve(ATTR_FILE);
    try {
      final StringBuilder builder = new StringBuilder();
      // Remove any MineCICD block already written (and any lone marker
      // lines an interrupted legacy write left behind) so the block is
      // replaced rather than duplicated on every reload.
      if (Files.exists(attributes)) {
        builder.append(stripStaleAttributesBlock(Files.readString(attributes, StandardCharsets.UTF_8)));
      }
      // Check Line Seperator
      if (builder.length() > LINE_SEP.length()
          && builder.lastIndexOf(LINE_SEP) < builder.length() - LINE_SEP.length()) {
        builder.append(LINE_SEP);
      }

      // append filters
      builder.append(FILTERS_BEGIN).append(LINE_SEP);
      for (final SecretFileEntry entry : files) {
        if (entry == null || entry.file() == null || entry.file().isBlank()) {
          continue;
        }
        builder.append(escapeAttr(entry.file())).append(" filter=").append(entry.driverName())
            .append(LINE_SEP);
      }
      builder.append(FILTERS_END).append(LINE_SEP);

      // write to file
      Files.write(attributes, builder.toString().getBytes(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      MineCICDApi.logger().warning("Unable to write " + ATTR_FILE + ": " + e.getMessage());
    }
  }

  public static void writeGitConfig(final List<SecretFileEntry> files) {
    final Path gitDir = MineCICDApi.serverRoot().resolve(GIT_DIR);
    if (!Files.isDirectory(gitDir)) {
      return;
    }
    final Path config = gitDir.resolve(CONFIG_FILE);

    try {
      final StringBuilder builder = new StringBuilder();
      if (Files.exists(config)) {
        builder.append(stripStaleSections(Files.readString(config, StandardCharsets.UTF_8)));
      }
      // Check Line Seperator
      if (builder.length() > LINE_SEP.length()
          && builder.lastIndexOf(LINE_SEP) < builder.length() - LINE_SEP.length()) {
        builder.append(LINE_SEP);
      }

      // append per-file filter sections. Each filter is named minecicd-<hex>
      // and refers to a JGit builtin registered in FilterCommandRegistry; the
      // git CLI (on other machines) treats these names as commands that do
      // not exist, and because required=false it skips the transformation
      // with a warning instead of aborting.
      // The clean/smudge values are the exact JGit builtin registry keys
      builder.append(FILTERS_BEGIN).append(LINE_SEP);
      for (final SecretFileEntry entry : files) {
        if (entry == null || entry.file() == null || entry.file().isBlank()) {
          continue;
        }
        builder.append("[filter \"").append(entry.driverName()).append("\"]").append(LINE_SEP);
        builder.append("\tclean = ").append(entry.cleanKeyFor()).append(LINE_SEP);
        builder.append("\tsmudge = ").append(entry.smudgeKeyFor()).append(LINE_SEP);
        builder.append("\trequired = false").append(LINE_SEP);
        builder.append(LINE_SEP);
      }
      builder.append(FILTERS_END).append(LINE_SEP);

      // write to file
      Files.write(config, builder.toString().getBytes(StandardCharsets.UTF_8));
    } catch (final IOException e) {
      MineCICDApi.logger().warning("Unable to write " + CONFIG_FILE + "filters: " + e.getMessage());
    }
  }

  /**
   * Removes every MineCICD-managed region from .gitattributes: each
   * {@code # MineCICD FILTERS BEGIN/END} block as well as any lone marker
   * line left behind by an interrupted legacy write. Other attribute rules
   * are kept line-by-line (trailing {@code \r} dropped, then re-appended
   * with {@code LINE_SEP}), so the managed block can never accumulate.
   */
  private static String stripStaleAttributesBlock(final String content) {
    final String[] lines = content.split("\\r?\\n", -1);
    // split(..., -1) yields one trailing empty element for a final newline;
    // that element must not become an extra (growing) empty line on each pass.
    int last = lines.length;
    if (last > 0 && lines[last - 1].isEmpty()) {
      last--;
    }
    final StringBuilder out = new StringBuilder();
    boolean inBlock = false;
    for (int i = 0; i < last; i++) {
      final String line = lines[i];
      final String trimmed = line.trim();
      if (inBlock) {
        if (trimmed.equals(FILTERS_END)) {
          inBlock = false;
        }
        continue;
      }
      if (trimmed.equals(FILTERS_BEGIN) || trimmed.equals(FILTERS_END)) {
        if (trimmed.equals(FILTERS_BEGIN)) {
          inBlock = true;
        }
        continue;
      }
      out.append(stripTrailingCr(line)).append(LINE_SEP);
    }
    return out.toString();
  }

  /**
   * Removes everything MineCICD previously wrote into .git/config: the
   * {@code # MineCICD FILTERS BEGIN/END} marker block, the legacy
   * {@code [filter "minecicd"]} java -jar section, and any stale
   * {@code [filter "minecicd-*"]} builtin sections. Other sections are kept
   * byte-for-byte (line endings normalized by the caller).
   */
  private static String stripStaleSections(final String content) {
    final String[] lines = content.split("\\r?\\n", -1);
    // split(..., -1) yields one trailing empty element for a final newline;
    // that element must not become an extra (growing) empty line on each pass.
    int last = lines.length;
    if (last > 0 && lines[last - 1].isEmpty()) {
      last--;
    }
    final StringBuilder out = new StringBuilder();
    boolean skipToEndMarker = false;
    boolean skipToNextSection = false;
    for (int i = 0; i < last; i++) {
      final String line = lines[i];
      final String trimmed = line.trim();
      if (skipToEndMarker) {
        if (trimmed.equals(FILTERS_END)) {
          skipToEndMarker = false;
        }
        continue;
      }
      if (skipToNextSection) {
        if (trimmed.startsWith("[")) {
          skipToNextSection = false;
        } else {
          continue;
        }
      }
      // Drop stray marker lines outside a marker block (leftovers of an
      // interrupted legacy write) so they cannot confuse later stripping.
      if (trimmed.equals(FILTERS_BEGIN)) {
        skipToEndMarker = true;
        continue;
      }
      if (trimmed.equals(FILTERS_END)) {
        continue;
      }
      if (isMineCicdSectionHeader(trimmed)) {
        skipToNextSection = true;
        continue;
      }
      out.append(stripTrailingCr(line)).append(LINE_SEP);
    }
    return out.toString();
  }

  private static boolean isMineCicdSectionHeader(final String trimmed) {
    return trimmed.startsWith("[filter \"" + SecretFilterService.FILTER_NAME + "\"]")
        || trimmed.startsWith("[filter \"" + SecretFilterService.FILTER_NAME + "-");
  }

  /**
   * Drops a single trailing {@code \r} from a line read out of a CRLF file,
   * so re-appending {@code LINE_SEP} never produces doubled {@code \r\r\n}.
   * LF-only files are untouched.
   */
  private static String stripTrailingCr(final String line) {
    return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
  }

  private static String escapeAttr(final String file) {
    return file.replace("\\", "\\\\").replace("\"", "\\\"");
  }

}
