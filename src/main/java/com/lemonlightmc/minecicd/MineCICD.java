package com.lemonlightmc.minecicd;

import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.bossbar.BossBars;
import com.lemonlightmc.minecicd.command.MineCICDCommand;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class MineCICD extends JavaPlugin {

    private MineCICDConfig config;
    private BossBars bossBars;

    private boolean resumed;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        saveDefaultExampleScript();

        this.config = new MineCICDConfig(this);
        MineCICDApi.setPlugin(this);
        this.bossBars = new BossBars();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                commands -> commands.registrar().register(new MineCICDCommand().build()));

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onTickStart(final ServerTickStartEvent event) {
                if (!resumed) {
                    resumed = true;
                    getServer().getScheduler().runTaskLater(MineCICD.this, () -> {
                        MineCICDApi.controlService().start();
                        MineCICDApi.handler().onServerStarted();
                    }, 20L);
                }
            }
        }, this);

        getLogger().info("MineCICD " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        MineCICDApi.shutDown();
    }

    public void reloadPlugin() {
        config.load();
        MineCICDApi.reload();
        bossBars.reload();
        getLogger().info("MineCICD reloaded.");
    }

    private void saveDefaultExampleScript() {
        final File scriptsDir = new File(getDataFolder(), "scripts");
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs();
        }
        final File example = new File(scriptsDir, "example_script.sh");
        if (!example.exists() && getResource("scripts/example_script.sh") != null) {
            saveResource("scripts/example_script.sh", false);
        }
    }

    public MineCICDConfig config() {
        return config;
    }

    public BossBars bossBars() {
        return bossBars;
    }
}