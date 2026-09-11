package com.lemonlightmc.minecicd.services;

import com.lemonlightmc.minecicd.MineCICDConfig.Control;
import com.lemonlightmc.minecicd.api.MineCICDApi;
import com.lemonlightmc.minecicd.http.ControlServer;

public class ControlService {
  private boolean controlActive;
  private String controlAddress = "disabled";
  private ControlServer server;
  private final Control control;

  public ControlService() {
    this.control = MineCICDApi.config().control();
  }

  public void setup() {
    reload();
    server = new ControlServer(control);
  }

  public void start() {
    if (server != null && server.start()) {
      controlActive = true;
      controlAddress = "http" + (server.hasSslContext() ? "s" : "") + "://" + control.host() + ":"
          + control.port()
          + "/" + control.path();
    } else {
      controlActive = false;
      controlAddress = "failed to start";
    }
  }

  public void stop() {
    server.stop();
    controlActive = false;
    controlAddress = null;
    server = null;
  }

  public void reload() {
    if (server != null) {
      server.stop();
    }
    if (control.port() <= 0) {
      controlActive = false;
      controlAddress = "disabled";
      MineCICDApi.logger().info("Control API is disabled (control.port <= 0).");
      return;
    }
    if (control.secret() == null || control.secret().isEmpty()) {
      controlActive = false;
      controlAddress = "disabled";
      MineCICDApi.logger().severe("Control API refused to start: control.secret is empty. Set a strong secret.");
      return;
    }
    if (control.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
      MineCICDApi.logger().warning("control.secret is weaker than 32 bytes; use a stronger secret.");
    }
  }

  public boolean isControlActive() {
    return controlActive;
  }

  public String controlAddress() {
    return controlAddress;
  }

  public String controlStatus() {
    return controlAddress;
  }
}
