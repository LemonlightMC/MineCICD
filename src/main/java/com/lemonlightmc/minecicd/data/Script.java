package com.lemonlightmc.minecicd.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.bukkit.Bukkit;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.exceptions.ScriptException;
import com.lemonlightmc.minecicd.util.Utils;

public class Script {

  private final String name;
  private final Path path;
  private String[] lines = null;

  private int timesRan = 0;
  private long lastRunTimestamp;
  private long lastRunDuration;
  private long avgDuration;

  public Script(final Path dir, final String name) {
    this.name = name;
    this.path = resolvePath(dir, name);
    if (this.path == null) {
      throw new ScriptException("Script not found: " + name);
    }
  }

  public String name() {
    return name;
  }

  public Path path() {
    return path;
  }

  public int timesRan() {
    return timesRan;
  }

  public long lastRunTimestamp() {
    return lastRunTimestamp;
  }

  public long lastRunDuration() {
    return lastRunDuration;
  }

  public long avgDuration() {
    return avgDuration;
  }

  public void compile() {
    List<String> lines;
    try {
      lines = Files.readAllLines(this.path, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new ScriptException("Unable to read script: " + e.getMessage());
    }
    lines.stream().filter(line -> line == null || line.isBlank() || line.startsWith("#")).map(line -> line.trim());
    this.lines = lines.toArray(String[]::new);
  }

  /**
   * Runs a script by file name. Console lines are dispatched as server commands
   * on the
   * main thread; {@code !} lines are executed in the system shell. Returns the
   * number of
   * lines processed, throwing on the first failing console command or shell
   * error.
   */
  public void run(final Actor actor, final Consumer<String> consoleLine) {
    final long start = System.currentTimeMillis();

    for (int i = 0; i < this.lines.length; i++) {
      final String line = lines[i];
      MineCICDApi.logger().info("[script]" + name + " - Executing (line " + i + "): " + line);
      if (line.startsWith("! ")) {
        final String command = line.substring(2).trim();
        runShell(command, actor, i + 1);
      } else {
        final String command = line.startsWith("/") ? line.substring(1) : line;
        dispatchCommand(command, actor, i + 1);
      }
      if (consoleLine != null) {
        consoleLine.accept(line);
      }
    }

    recordRun(start);
  }

  private void dispatchCommand(final String command, final Actor actor, final int line) {
    try {
      final boolean success = MineCICDApi.plugin().getServer().getScheduler()
          .callSyncMethod(MineCICDApi
              .plugin(),
              () -> MineCICDApi.plugin().getServer().dispatchCommand(
                  actor != null ? actor.getCommandSender() : Bukkit.getConsoleSender(), command))
          .get();
      if (!success) {
        throw new ScriptException(name + " - Command failed (line " + line + "): /" + command);
      }
    } catch (final ScriptException e) {
      throw e;
    } catch (final Exception e) {
      throw new ScriptException(
          name + " - Command failed (line " + line + "): /" + command + " -> " + Utils.rootMessage(e));
    }
  }

  private void runShell(final String command, final Actor actor, final int line) {
    try {
      final ProcessBuilder pb = new ProcessBuilder();
      final String os = System.getProperty("os.name", "").toLowerCase();
      if (os.contains("win")) {
        pb.command("cmd", "/c", command);
      } else {
        pb.command("sh", "-c", command);
      }
      pb.redirectErrorStream(true);
      // M-04: sandbox - restrict working dir, sanitize env
      pb.directory(MineCICDApi.serverRoot().toFile());
      // clear and allowlist minimal env
      final java.util.Map<String, String> env = pb.environment();
      final String path = env.get("PATH");
      final String home = env.get("HOME");
      env.clear();
      if (path != null)
        env.put("PATH", path);
      if (home != null)
        env.put("HOME", home);
      final Process process = pb.start();
      final InputStream out = process.getInputStream();
      final Thread drain = new Thread(() -> {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(out, StandardCharsets.UTF_8))) {
          String l;
          while ((l = reader.readLine()) != null) {
            MineCICDApi.logger().info("[script] " + l);
          }
        } catch (final IOException ignored) {
        }
      }, "minecicd-script-out");
      drain.setDaemon(true);
      drain.start();
      // M-04: per-script timeout 30s
      final boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new ScriptException(name + " - Shell command timed out (line " + line + "): " + command);
      }
      final int exit = process.exitValue();
      if (exit != 0) {
        throw new ScriptException(
            name + " - Shell command failed (line " + line + "): " + command + " (exit " + exit + ")");
      }
    } catch (final ScriptException e) {
      throw e;
    } catch (final Exception e) {
      throw new ScriptException(
          name + " - Shell command failed (line " + line + "): " + command + " -> " + Utils.rootMessage(e));
    }
  }

  private void recordRun(final long start) {
    this.timesRan++;
    this.lastRunTimestamp = System.currentTimeMillis();
    this.lastRunDuration = lastRunTimestamp - start;
    this.avgDuration = (avgDuration + lastRunDuration) / 2;
  }

  private static Path resolvePath(final Path dir, final String name) {
    if (name == null || name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || "..".equals(name)
        || name.startsWith(".")) {
      return null;
    }
    Path file;
    if (name.contains(".")) {
      file = dir.resolve(name);
    } else {
      file = dir.resolve(name + ".sh");
    }
    if (!Files.isRegularFile(file)) {
      file = dir.resolve(name + ".sh");
      if (!Files.isRegularFile(file)) {
        return null;
      }
    }
    // M-03: canonical-path confinement - follow symlinks in all parent components
    final Path normalized = file.normalize().toAbsolutePath();
    final Path base = dir.normalize().toAbsolutePath();
    if (!normalized.startsWith(base)) {
      return null;
    }
    try {
      final Path realBase = base.toRealPath();
      final Path realFile = normalized.toRealPath();
      if (!realFile.startsWith(realBase)) {
        return null;
      }
    } catch (final IOException ignored) {
      // base or file does not exist yet - fall back to normalized check above
    }
    return normalized;
  }
}
