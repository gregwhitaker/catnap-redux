package com.github.gregwhitaker.catnap.testkit.probe;

import java.util.Set;

/** Immutable request context for serialization probes with three supported field selections. */
public record ProbeRequest(String field, String requestId, boolean internalView) {
  public static ProbeRequest from(String fields, String requestId, String view) {
    String field =
        switch (fields == null ? "payload(display_name)" : fields) {
          case "payload(display_name)" -> "display_name";
          case "payload(amount)" -> "amount";
          case "payload(internal)" -> "internal";
          default ->
              throw new IllegalArgumentException(
                  "Serialization probes accept only the three documented selections");
        };
    return new ProbeRequest(
        field, requestId == null ? "probe" : requestId, "internal".equals(view));
  }

  /** Proves that the serializer ran with the expected view before selection occurred. */
  public void verifyCompletePayload(Set<String> fields) {
    Set<String> expected =
        internalView
            ? Set.of("display_name", "amount", "internal")
            : Set.of("display_name", "amount");
    if (!fields.equals(expected)) {
      throw new IllegalStateException(
          "Serialization context was lost: expected " + expected + ", saw " + fields);
    }
  }
}
