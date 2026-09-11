package com.lemonlightmc.minecicd.api;

import java.nio.file.Path;
import java.util.logging.Logger;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.MineCICDConfig;
import com.lemonlightmc.minecicd.MineCICDHandler;
import com.lemonlightmc.minecicd.external.DiscordNotifier;
import com.lemonlightmc.minecicd.git.GitService;
import com.lemonlightmc.minecicd.services.*;
import com.lemonlightmc.minecicd.util.Utils;

public class MineCICDApi {
  private static MineCICD plugin;
  private static MessageService messages;

  private static Path serverRoot;
  private static String remoteRoot;

  private static CicdService cicdService;
  private static MineCICDHandler handler;

  private static AuditService auditService;
  private static AnalyticsService analyticsService;
  private static ScriptService scriptService;
  private static SecretFilterService secretFilterService;
  private static DiscordNotifier discordNotifier;
  private static HealthCheckService healthCheck;
  private static GitService gitService;
  private static PendingService pendingService;
  private static ApprovalService approvalService;

  private static ControlService controlService;

  public static void setPlugin(final MineCICD plugin) {
    if (MineCICDApi.plugin != null) {
      throw new IllegalStateException("MineCICDApi plugin has already been set");
    }
    MineCICDApi.plugin = plugin;
    MineCICDApi.messages = new MessageService();
    MineCICDApi.secretFilterService = new SecretFilterService();
    MineCICDApi.auditService = new AuditService();
    MineCICDApi.analyticsService = new AnalyticsService();
    MineCICDApi.scriptService = new ScriptService();
    MineCICDApi.discordNotifier = new DiscordNotifier();
    MineCICDApi.healthCheck = new HealthCheckService();
    MineCICDApi.controlService = new ControlService();
    MineCICDApi.approvalService = new ApprovalService();
    MineCICDApi.pendingService = new PendingService();

    MineCICDApi.gitService = new GitService();
    MineCICDApi.cicdService = new CicdService();
    MineCICDApi.handler = new MineCICDHandler();

    reload();
  }

  public static void reload() {
    serverRoot = dataFolder().toAbsolutePath().getParent().getParent();
    remoteRoot = Utils.normalizeRemoteRoot(plugin.config().git().remoteServerRoot());
    messages.load();
    secretFilterService.load();

    if (controlService != null) {
      controlService.reload();
    }
  }

  public static void shutDown() {
    if (controlService != null) {
      controlService.stop();
      controlService = null;
    }
    cicdService = null;
    handler.shutdown();
    handler = null;
    gitService.close();
    gitService = null;

    secretFilterService.unregisterFilters();
    secretFilterService = null;
    analyticsService.shutdown();
    analyticsService = null;
    auditService = null;
    scriptService = null;
    discordNotifier = null;
    healthCheck = null;
    pendingService = null;
    approvalService = null;
    messages = null;

    MineCICDApi.plugin = null;
  }

  public static MineCICD plugin() {
    return plugin;
  }

  public static Logger logger() {
    return plugin.getLogger();
  }

  public static MineCICDConfig config() {
    return plugin.config();
  }

  public static Path dataFolder() {
    return plugin.getDataFolder().toPath();
  }

  /**
   * The server root on the host: always the folder that contains
   * {@code plugins/}, i.e. the parent of {@code plugins/MineCICD/}. Never
   * derived from config — {@code git.remote-server-root} only describes where
   * that folder lives within the Git repository, not where it is on disk.
   */
  public static Path serverRoot() {
    return serverRoot;
  }

  /**
   * The path of the server root <em>within</em> the Git repository (on the
   * remote), e.g. {@code servers/lobby} in a monorepo. Empty string means the
   * server root IS the repository root.
   */
  public static String remoteRoot() {
    return remoteRoot;
  }

  public static CicdService cicdService() {
    return cicdService;
  }

  public static AuditService auditService() {
    return auditService;
  }

  public static AnalyticsService analyticsService() {
    return analyticsService;
  }

  public static ScriptService scriptService() {
    return scriptService;
  }

  public static SecretFilterService secretService() {
    return secretFilterService;
  }

  public static DiscordNotifier discordNotifier() {
    return discordNotifier;
  }

  public static HealthCheckService healthCheckService() {
    return healthCheck;
  }

  public static MessageService messages() {
    return messages;
  }

  public static MineCICDHandler handler() {
    return handler;
  }

  public static ControlService controlService() {
    return controlService;
  }

  public static GitService gitService() {
    return gitService;
  }

  public static PendingService pendingService() {
    return pendingService;
  }

  public static ApprovalService approvalService() {
    return approvalService;
  }
}
