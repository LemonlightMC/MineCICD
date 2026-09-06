package com.lemonlightmc.minecicd.exceptions;

public class ScriptException extends RuntimeException {
  public ScriptException(final String message) {
    super(message);
  }

  public ScriptException(final String message, final Throwable cause) {
    super(message, cause);
  }
}