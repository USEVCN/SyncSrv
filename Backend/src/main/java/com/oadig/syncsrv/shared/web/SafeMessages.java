package com.oadig.syncsrv.shared.web;

import java.util.*;
import java.util.regex.Pattern;

/** Redact transport credentials before displaying errors or writing exception chains to logs. */
public final class SafeMessages {
  private static final Pattern PRIVATE = Pattern.compile("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----");
  private static final Pattern URL_USER = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/\\s@]+@");
  private static final Pattern AUTH = Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(?:basic|bearer)\\s+[^\\s,;]+(?![A-Za-z])");
  private static final Pattern COOKIE = Pattern.compile("(?i)(?:set-cookie|cookie)\\s*[:=][^\\r\\n]*");
  private static final Pattern FIELD = Pattern.compile("(?i)((?:password|passwd|secret|token|api[-_]?key|access[-_]?key)\\s*[=:]\\s*)(?:\"[^\"]*\"|'[^']*'|[^\\s,;&]+)");
  private SafeMessages() {}
  public static String text(String value) {
    if (value == null) return "操作失败";
    String redacted = PRIVATE.matcher(value).replaceAll("[已隐藏私钥]");
    redacted = URL_USER.matcher(redacted).replaceAll("$1[已隐藏凭据]@");
    redacted = AUTH.matcher(redacted).replaceAll("$1[已隐藏]");
    redacted = COOKIE.matcher(redacted).replaceAll("Cookie: [已隐藏]");
    redacted = FIELD.matcher(redacted).replaceAll("$1[已隐藏]");
    return redacted.substring(0, Math.min(redacted.length(), 600));
  }
  public static String message(Throwable error) {
    return text(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
  }
  public static Exception logged(Throwable error) { return logged(error, new IdentityHashMap<>(), 0); }
  private static Exception logged(Throwable error, IdentityHashMap<Throwable, Boolean> seen, int depth) {
    if (error == null || depth >= 8 || seen.put(error, true) != null) return null;
    Exception safe = new Exception(error.getClass().getSimpleName() + ": " + message(error), logged(error.getCause(), seen, depth + 1));
    safe.setStackTrace(error.getStackTrace());
    for (Throwable suppressed : error.getSuppressed()) {
      Exception child = logged(suppressed, seen, depth + 1);
      if (child != null) safe.addSuppressed(child);
    }
    return safe;
  }
}
