package com.lemonlightmc.minecicd.services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.data.Actor;
import com.lemonlightmc.minecicd.data.Script;

public class ScriptService {

  private final Path scriptsDir;
  private Map<String, Script> scripts;

  public ScriptService() {
    this.scriptsDir = MineCICDApi.dataFolder().resolve("scripts");
  }

  public void reload() {
    if (scripts == null) {
      scripts = new ConcurrentHashMap<>();
    } else {
      scripts.clear();
    }
    if (!Files.isDirectory(scriptsDir)) {
      return;
    }
    try (Stream<Path> stream = Files.list(scriptsDir)) {
      stream.filter(Files::isRegularFile).sorted()
          .forEach(p -> {
            final String name = p.getFileName().toString();
            scripts.put(name, new Script(scriptsDir, name));
          });
    } catch (final IOException e) {
      MineCICDApi.logger().warning("Unable to load scripts: " + e.getMessage());
    }
  }

  public boolean scriptExists(final String name) {
    return scripts.containsKey(name);
  }

  public Set<String> listScripts() {
    return scripts.keySet();
  }

  public Script getScript(final String name) {
    return scripts.get(name);
  }

  public void run(final String name, final Actor executor, final Consumer<String> consoleLine) {
    final Script script = scripts.get(name);
    if (script == null) {
      return;
    }
    script.run(executor, consoleLine);
  }
}
