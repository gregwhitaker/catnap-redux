package com.github.gregwhitaker.catnap.testkit.probe;

import static com.github.gregwhitaker.catnap.testkit.probe.FiniteResponseContract.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Wire-level assertions shared by production framework streaming examples. */
public final class StreamingResponseContract {
  private StreamingResponseContract() {}

  public record Case(String path, List<String> records) {}

  public record Event(String data, String event, String id, String retry, List<String> comments) {}

  public static List<Case> ndjsonCases(String parameter, boolean linkedIn) {
    String nested =
        linkedIn ? "payload:(amount,internal,ignored)" : "payload(amount,internal,ignored)";
    String full = "{\"payload\":{\"display_name\":\"ACME\",\"amount\":\"EUR:12.50\"}}";
    String selected = "{\"payload\":{\"amount\":\"EUR:12.50\"}}";
    return List.of(
        new Case("stream/ndjson", List.of(full, full)),
        new Case("stream/ndjson" + query(parameter, " \t"), List.of(full, full)),
        new Case("stream/ndjson" + query(parameter, nested), List.of(selected, selected)),
        new Case("stream/ndjson" + query(parameter, "missing"), List.of("{}", "{}")),
        new Case(
            "stream/shapes" + query(parameter, "id"),
            List.of("[{\"id\":1},{},null,7]", "null", "42", "[]", "{}")),
        new Case("stream/empty" + query(parameter, "id"), List.of()));
  }

  public static void verifyNdjson(String base, Case test, BiPredicate<String, String> equivalent)
      throws Exception {
    var response = get(base, test.path());
    require(response.statusCode() == 200, test.path() + " status: " + response.statusCode());
    require(
        response.headers().firstValue("content-type").orElse("").startsWith("application/x-ndjson"),
        "NDJSON content type");
    String body = response.body();
    require(body.isEmpty() || body.endsWith("\n"), "Every NDJSON record has a trailing LF");
    var lines = body.lines().toList();
    require(lines.size() == test.records().size(), "Record count: " + body);
    for (int i = 0; i < lines.size(); i++) {
      require(!lines.get(i).isBlank(), "No blank records");
      require(
          equivalent.test(test.records().get(i), lines.get(i)),
          "Record " + i + ": " + lines.get(i));
    }
  }

  public static void verifySse(
      String base,
      String parameter,
      String expression,
      String expectedJson,
      BiPredicate<String, String> equivalent)
      throws Exception {
    var response =
        get(base, "stream/sse" + (expression == null ? "" : query(parameter, expression)));
    require(response.statusCode() == 200, "SSE status: " + response.statusCode());
    require(
        response.headers().firstValue("content-type").orElse("").startsWith("text/event-stream"),
        "SSE content type");
    List<Event> events = events(response.body());
    require(events.size() == 4, "Event count: " + response.body());
    Event json = events.get(0);
    require(equivalent.test(expectedJson, json.data()), "JSON event: " + json);
    require(
        "quote".equals(json.event()) && "42".equals(json.id()) && "1500".equals(json.retry()),
        "JSON envelope: " + json);
    require(json.comments().equals(List.of("first", "second")), "Multiline comments: " + json);
    require(
        "null".equals(events.get(1).data()) && "43".equals(events.get(1).id()),
        "Explicit JSON null is data");
    require(
        "{\"private\":true}\nplain text".equals(events.get(2).data())
            && "text".equals(events.get(2).event()),
        "Intentional text is unchanged");
    Event control = events.get(3);
    require(
        control.data() == null
            && "".equals(control.id())
            && "0".equals(control.retry())
            && control.comments().equals(List.of("heartbeat")),
        "Control event: " + control);
  }

  /**
   * Reads logical events including comment-only frames; multiline data and comments stay ordered.
   */
  public static List<Event> events(String wire) {
    var result = new ArrayList<Event>();
    var data = new ArrayList<String>();
    var comments = new ArrayList<String>();
    String event = null, id = null, retry = null;
    boolean present = false;
    for (String line : wire.split("\\r?\\n", -1)) {
      if (line.isEmpty()) {
        if (present)
          result.add(
              new Event(
                  data.isEmpty() ? null : String.join("\n", data),
                  event,
                  id,
                  retry,
                  List.copyOf(comments)));
        data.clear();
        comments.clear();
        event = null;
        id = null;
        retry = null;
        present = false;
        continue;
      }
      present = true;
      int colon = line.indexOf(':');
      String field = colon < 0 ? line : line.substring(0, colon);
      String value = colon < 0 ? "" : line.substring(colon + 1);
      if (value.startsWith(" ")) value = value.substring(1);
      switch (field) {
        case "data" -> data.add(value);
        case "event" -> event = value;
        case "id" -> id = value;
        case "retry" -> retry = value;
        case "" -> comments.add(value);
        default -> {}
      }
    }
    require(!present, "SSE event must end with a blank line");
    return List.copyOf(result);
  }
}
