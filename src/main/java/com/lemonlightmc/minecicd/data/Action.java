package com.lemonlightmc.minecicd.data;

public enum Action {
  INIT("init"),
  DEINIT("deinit"),
  PULL("pull"),
  PUSH("push"),
  ADD("add"),
  REMOVE("remove"),
  RESET("reset"),
  REVERT("revert"),
  ROLLBACK("rollback"),
  RESOLVE("resolve"),
  SCRIPT("script"),
  ANALYTICS_RESET("analytics-reset"),
  APPROVAL_CREATED("approval-created"),
  APPROVAL_CONFIRM("approval-confirm"),
  APPROVAL_CANCEL("approval-cancel"),
  AUTO_ROLLBACK("auto-rollback");

  private final String value;

  Action(final String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }
}