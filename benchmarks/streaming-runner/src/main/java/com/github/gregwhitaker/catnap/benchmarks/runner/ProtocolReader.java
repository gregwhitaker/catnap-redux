package com.github.gregwhitaker.catnap.benchmarks.runner;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/** Incremental benchmark client: retains at most one record/event and its bounded line. */
final class ProtocolReader {
  record Frame(String json, String id, String event, String retry, String comment) {}

  private final BufferedReader input;
  private final boolean sse;
  private final AtomicLong bytes = new AtomicLong();

  ProtocolReader(InputStream stream, boolean sse) {
    this.sse = sse;
    var counted =
        new FilterInputStream(stream) {
          @Override
          public int read() throws IOException {
            int value = in.read();
            if (value >= 0) bytes.incrementAndGet();
            return value;
          }

          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            int size = in.read(buffer, offset, length);
            if (size > 0) bytes.addAndGet(size);
            return size;
          }
        };
    input =
        new BufferedReader(
            new InputStreamReader(
                counted,
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)),
            8192);
  }

  long bytes() {
    return bytes.get();
  }

  private String line() throws IOException {
    var result = new StringBuilder();
    while (true) {
      int next = input.read();
      if (next < 0) {
        if (!result.isEmpty()) throw new EOFException("Unterminated protocol line");
        return null;
      }
      if (next == '\n') {
        if (!result.isEmpty() && result.charAt(result.length() - 1) == '\r')
          result.setLength(result.length() - 1);
        return result.toString();
      }
      if (result.length() >= 65536)
        throw new IOException("Benchmark protocol line exceeded 64 KiB");
      result.append((char) next);
    }
  }

  Frame next() throws IOException {
    if (!sse) {
      String value = line();
      if (value == null) return null;
      if (value.isEmpty()) throw new IOException("Blank NDJSON record");
      return new Frame(value, null, null, null, null);
    }
    var data = new StringBuilder();
    var comments = new StringBuilder();
    String id = null, event = null, retry = null;
    boolean hasData = false, hasLine = false;
    while (true) {
      String line = line();
      if (line == null) {
        if (hasLine) throw new EOFException("Unterminated SSE event");
        return null;
      }
      if (line.isEmpty()) {
        if (hasData) return new Frame(data.toString(), id, event, retry, comments.toString());
        id = event = retry = null;
        comments.setLength(0);
        hasLine = false;
        continue;
      }
      hasLine = true;
      int colon = line.indexOf(':');
      String name = colon < 0 ? line : line.substring(0, colon);
      String value = colon < 0 ? "" : line.substring(colon + 1);
      if (value.startsWith(" ")) value = value.substring(1);
      switch (name) {
        case "data" -> {
          if (hasData) data.append('\n');
          data.append(value);
          hasData = true;
        }
        case "id" -> id = value;
        case "event" -> event = value;
        case "retry" -> retry = value;
        case "" -> {
          if (!comments.isEmpty()) comments.append('\n');
          comments.append(value);
        }
        default -> {
          /* SSE receivers ignore extension fields, including native Quarkus content-type. */
        }
      }
      if (data.length() + comments.length() > 65536)
        throw new IOException("Benchmark event exceeded 64 KiB");
    }
  }
}
