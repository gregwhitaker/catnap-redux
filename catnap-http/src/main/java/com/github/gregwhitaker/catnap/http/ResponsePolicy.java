package com.github.gregwhitaker.catnap.http;

import java.util.Locale;
import java.util.Set;

/** Shared response eligibility and invalidated representation-header rules. */
public final class ResponsePolicy {
  private ResponsePolicy() {}

  public static final Set<String> BODY_HEADERS =
      Set.of(
          "content-length",
          "etag",
          "content-md5",
          "digest",
          "content-digest",
          "repr-digest",
          "content-range",
          "accept-ranges");

  public static boolean finiteJson(String contentType, int status, String contentEncoding) {
    if (status < 200 || status >= 300 || status == 204 || status == 205 || status == 206)
      return false;
    if (contentEncoding != null
        && !contentEncoding.isBlank()
        && !contentEncoding.equalsIgnoreCase("identity")) return false;
    if (contentType == null) return false;
    String media = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return media.equals("application/json")
        || (media.startsWith("application/") && media.endsWith("+json"));
  }

  public static boolean invalidatedHeader(String name) {
    return BODY_HEADERS.contains(name.toLowerCase(Locale.ROOT));
  }
}
