package com.lemonlightmc.minecicd.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.regex.Pattern;

public class Utils {
  private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  public static String rootMessage(final Throwable t) {
    Throwable current = t;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    final String message = current.getMessage();
    return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
  }

  public static String escape(final String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
  }

  public static String nullToEmpty(final String value) {
    return value == null ? "" : value;
  }

  public static String newRequestId() {
    return UUID.randomUUID().toString();
  }

  public static boolean isValidRequestId(final String id) {
    return id != null && REQUEST_ID.matcher(id).matches();
  }

  public static String hex(final byte[] bytes) {
    final StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (int i = 0; i < 8; i++) {
      sb.append(String.format("%02x", bytes[i]));
    }
    return sb.toString();
  }

  public static byte[] hexDecode(final String hex) {
    if (hex.length() % 2 != 0) {
      return null;
    }
    try {
      final byte[] out = new byte[hex.length() / 2];
      for (int i = 0; i < out.length; i++) {
        out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      }
      return out;
    } catch (final NumberFormatException e) {
      return null;
    }
  }

  public static String sha256Hex(final byte[] data) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(data == null ? new byte[0] : data));
    } catch (final NoSuchAlgorithmException e) {
      // SHA-256 is mandated by the JCA specification.
      throw new IllegalStateException("SHA-256 not available", e);
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to digest data to sha256", e);
    }
  }

  /**
   * Normalizes the configured repository-relative path: forward slashes only,
   * no leading {@code /} or {@code ./}, no trailing slash, and no {@code .}
   * or {@code ..} segments. A blank value yields the empty string (the
   * repository root).
   */
  public static String normalizeRemoteRoot(final String configured) {
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

  public static String jsonEscape(final String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  public static boolean isHexColor(final String s) {
    if (s.length() != 6) {
      return false;
    }
    for (int i = 0; i < 6; i++) {
      if (!isHex(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  public static boolean isHex(final char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  public static String legacyCode(final char code) {
    return switch (code) {
      case '0' -> "<black>";
      case '1' -> "<dark_blue>";
      case '2' -> "<dark_green>";
      case '3' -> "<dark_aqua>";
      case '4' -> "<dark_red>";
      case '5' -> "<dark_purple>";
      case '6' -> "<gold>";
      case '7' -> "<gray>";
      case '8' -> "<dark_gray>";
      case '9' -> "<blue>";
      case 'a' -> "<green>";
      case 'b' -> "<aqua>";
      case 'c' -> "<red>";
      case 'd' -> "<light_purple>";
      case 'e' -> "<yellow>";
      case 'f' -> "<white>";
      case 'k' -> "<obfuscated>";
      case 'l' -> "<bold>";
      case 'm' -> "<strikethrough>";
      case 'n' -> "<underlined>";
      case 'o' -> "<italic>";
      case 'r' -> "<reset>";
      default -> null;
    };
  }
}
