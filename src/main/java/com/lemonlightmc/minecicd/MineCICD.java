package com.lemonlightmc.minecicd;

import com.lemonlightmc.minecicd.bossbar.BossBars;
import com.lemonlightmc.minecicd.command.MineCICDCommand;
import com.lemonlightmc.minecicd.git.GitService;
import com.lemonlightmc.minecicd.http.ControlSecurity;
import com.lemonlightmc.minecicd.http.ControlServer;
import com.lemonlightmc.minecicd.messaging.Messages;
import com.lemonlightmc.minecicd.pending.PendingStore;
import com.lemonlightmc.minecicd.scripts.ScriptManager;
import com.lemonlightmc.minecicd.secrets.SecretManager;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Path;

public final class MineCICD extends JavaPlugin {

    private MineCICDConfig config;
    private Messages messages;
    private BossBars bossBars;
    private GitService gitService;
    private ScriptManager scriptManager;
    private SecretManager secretManager;
    private PendingStore pendingStore;
    private CicdService cicdService;
    private ControlSecurity security;
    private ControlServer controlServer;
    private volatile boolean controlActive;
    private volatile String controlAddress = "disabled";
    private boolean resumed;
    private Path serverRoot;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        saveDefaultExampleScript();

        this.config = new MineCICDConfig(this);
        this.serverRoot = resolveServerRoot();

        this.messages = new Messages(this);
        this.bossBars = new BossBars(this);
        this.gitService = new GitService(this);
        this.scriptManager = new ScriptManager(this);
        this.secretManager = new SecretManager(this);
        this.pendingStore = new PendingStore(getDataFolder().toPath());
        this.cicdService = new CicdService(this);

        secretManager.load();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                commands -> commands.registrar().register(new MineCICDCommand(cicdService, messages).build()));

        startControlServer();

        getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onTickStart(final ServerTickStartEvent event) {
                if (!resumed) {
                    resumed = true;
                    getServer().getScheduler().runTaskLater(MineCICD.this, () -> cicdService.onServerStarted(), 20L);
                }
            }
        }, this);

        getLogger().info("MineCICD " + getPluginMeta().getVersion() + " enabled.");
    }

    @Override
    public void onDisable() {
        if (secretManager != null) {
            secretManager.unregisterFilters();
        }
        if (controlServer != null) {
            controlServer.stop();
            controlServer = null;
        }
        if (cicdService != null) {
            cicdService.shutdown();
            cicdService = null;
        }
    }

    public void reloadPlugin() {
        onDisable();
        config.load();
        this.serverRoot = resolveServerRoot();
        messages.load();
        bossBars.reload();
        secretManager.load();
        startControlServer();
        getLogger().info("MineCICD reloaded.");
    }

    private void startControlServer() {
        final var control = config.control();
        if (control.port() <= 0) {
            controlActive = false;
            controlAddress = "disabled";
            getLogger().info("Control API is disabled (control.port <= 0).");
            return;
        }
        if (control.secret() == null || control.secret().isEmpty()) {
            controlActive = false;
            controlAddress = "disabled";
            getLogger().severe("Control API refused to start: control.secret is empty. Set a strong secret.");
            return;
        }
        if (control.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            getLogger().warning("control.secret is weaker than 32 bytes; use a stronger secret.");
        }
        security = new ControlSecurity(
                control.replayWindowSeconds(),
                control.actions());
        final ControlServer server = new ControlServer(this, control);

        if (server.start()) {
            controlActive = true;
            controlAddress = "http" + (server.hasSslContext() ? "s" : "") + "://" + control.host() + ":"
                    + control.port()
                    + "/" + control.path();
        } else {
            controlActive = true;
            controlAddress = "failed to start";
        }
    }

    private void saveDefaultExampleScript() {
        final File scriptsDir = new File(getDataFolder(), "scripts");
        if (!scriptsDir.exists()) {
            scriptsDir.mkdirs();
        }
        final File example = new File(scriptsDir, "example_script.sh");
        if (!example.exists() && getResource("example_script.sh") != null) {
            saveResource("example_script.sh", false);
        }
    }

    public Path serverRoot() {
        return serverRoot;
    }

    /**
     * Resolves the server root path from config. When {@code git.server-root} is
     * set, it is resolved relative to the Bukkit server root (the parent of
     * {@code plugins/MineCICD/}). This allows the Minecraft server to live in a
     * subdirectory of a larger Git repository (monorepo). When empty, the
     * server root equals the Bukkit server root (default behavior).
     */
    private Path resolveServerRoot() {
        final Path bukkitRoot = getDataFolder().getParentFile().getParentFile().toPath();
        final String sub = config.git().serverRoot();
        if (sub == null || sub.isBlank()) {
            return bukkitRoot;
        }
        return bukkitRoot.resolve(sub).normalize();
    }

    public MineCICDConfig config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public BossBars bossBars() {
        return bossBars;
    }

    public GitService gitService() {
        return gitService;
    }

    public ScriptManager scriptManager() {
        return scriptManager;
    }

    public SecretManager secretManager() {
        return secretManager;
    }

    public PendingStore pendingStore() {
        return pendingStore;
    }

    public CicdService cicdService() {
        return cicdService;
    }

    public ControlSecurity security() {
        return security;
    }

    public ControlServer controlServer() {
        return controlServer;
    }

    public boolean isControlActive() {
        return controlActive;
    }

    public String getControlAddress() {
        return controlAddress;
    }
}