package com.lemonlightmc.minecicd;

import com.lemonlightmc.minecicd.analytics.Analytics;
import com.lemonlightmc.minecicd.approval.ApprovalStore;
import com.lemonlightmc.minecicd.audit.AuditLogger;
import com.lemonlightmc.minecicd.bossbar.BossBars;
import com.lemonlightmc.minecicd.command.MineCICDCommand;
import com.lemonlightmc.minecicd.events.DeploymentEvents;
import com.lemonlightmc.minecicd.git.GitService;
import com.lemonlightmc.minecicd.health.HealthCheck;
import com.lemonlightmc.minecicd.http.ControlSecurity;
import com.lemonlightmc.minecicd.http.ControlServer;
import com.lemonlightmc.minecicd.messaging.Messages;
import com.lemonlightmc.minecicd.notify.DiscordNotifier;
import com.lemonlightmc.minecicd.pending.PendingStore;
import com.lemonlightmc.minecicd.schedule.AutoPullScheduler;
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
    private DeploymentEvents events;
    private AuditLogger auditLogger;
    private Analytics analytics;
    private ApprovalStore approvalStore;
    private DiscordNotifier discordNotifier;
    private HealthCheck healthCheck;
    private AutoPullScheduler autoPullScheduler;
    private volatile boolean controlActive;
    private volatile String controlAddress = "disabled";
    private boolean resumed;
    private Path serverRoot;
    private String remoteRoot;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        saveDefaultExampleScript();
        setupServices();

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                commands -> commands.registrar().register(new MineCICDCommand(cicdService, messages).build()));

        startControlServer();
        autoPullScheduler.start();

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

    private void setupServices() {
        this.config = new MineCICDConfig(this);
        this.serverRoot = hostServerRoot();
        this.remoteRoot = normalizeRemoteRoot(config.git().remoteServerRoot());

        this.events = new DeploymentEvents();
        this.auditLogger = new AuditLogger(this);
        this.analytics = new Analytics(this);
        this.approvalStore = new ApprovalStore(this);
        this.discordNotifier = new DiscordNotifier(this);
        this.healthCheck = new HealthCheck(this);
        this.autoPullScheduler = new AutoPullScheduler(this);

        this.messages = new Messages(this);
        this.bossBars = new BossBars(this);
        this.gitService = new GitService(this);
        this.scriptManager = new ScriptManager(this);
        this.secretManager = new SecretManager(this);
        this.pendingStore = new PendingStore(getDataFolder().toPath());
        this.cicdService = new CicdService(this);

        secretManager.load();
    }

    @Override
    public void onDisable() {
        if (autoPullScheduler != null) {
            autoPullScheduler.shutdown();
            autoPullScheduler = null;
        }
        if (healthCheck != null) {
            healthCheck.shutdown();
            healthCheck = null;
        }
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
        if (discordNotifier != null) {
            discordNotifier.shutdown();
            discordNotifier = null;
        }
    }

    public void reloadPlugin() {
        config.load();
        this.serverRoot = hostServerRoot();
        this.remoteRoot = normalizeRemoteRoot(config.git().remoteServerRoot());
        messages.load();
        bossBars.reload();
        secretManager.load();
        auditLogger.refresh();
        discordNotifier.refresh();
        if (controlServer != null) {
            controlServer.stop();
            controlServer = null;
        }
        startControlServer();
        if (autoPullScheduler != null) {
            autoPullScheduler.start();
        }
        getLogger().info("MineCICD reloaded.");
    }

    private void startControlServer() {
        final var control = config.control();
        security = new ControlSecurity(
                control.replayWindowSeconds(),
                control.actions());
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
        final ControlServer server = new ControlServer(this, control);

        if (server.start()) {
            controlActive = true;
            controlAddress = "http" + (server.hasSslContext() ? "s" : "") + "://" + control.host() + ":"
                    + control.port()
                    + "/" + control.path();
        } else {
            controlActive = false;
            controlAddress = "failed to start";
        }
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

    /**
     * The server root on the host: always the folder that contains
     * {@code plugins/}, i.e. the parent of {@code plugins/MineCICD/}. Never
     * derived from config — {@code git.remote-server-root} only describes where
     * that folder lives within the Git repository, not where it is on disk.
     */
    public Path serverRoot() {
        return serverRoot;
    }

    /**
     * The path of the server root <em>within</em> the Git repository (on the
     * remote), e.g. {@code servers/lobby} in a monorepo. Empty string means the
     * server root IS the repository root.
     */
    public String remoteRoot() {
        return remoteRoot;
    }

    private Path hostServerRoot() {
        return getDataFolder().toPath().toAbsolutePath().getParent().getParent();
    }

    /**
     * Normalizes the configured repository-relative path: forward slashes only,
     * no leading {@code /} or {@code ./}, no trailing slash, and no {@code .}
     * or {@code ..} segments. A blank value yields the empty string (the
     * repository root).
     */
    private static String normalizeRemoteRoot(final String configured) {
        if (configured == null || configured.isBlank()) {
            return "";
        }
        String p = configured.trim().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        for (final String segment : p.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return "";
            }
        }
        return p;
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

    public DeploymentEvents events() {
        return events;
    }

    public AuditLogger auditLogger() {
        return auditLogger;
    }

    public Analytics analytics() {
        return analytics;
    }

    public ApprovalStore approvalStore() {
        return approvalStore;
    }

    public DiscordNotifier discordNotifier() {
        return discordNotifier;
    }

    public HealthCheck healthCheck() {
        return healthCheck;
    }

    public AutoPullScheduler autoPullScheduler() {
        return autoPullScheduler;
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