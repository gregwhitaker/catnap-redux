package com.github.gregwhitaker.catnap.http.stream;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * A connection-owned bounded serialization/projection boundary. It holds no payload after a call.
 * Adapters must also serialize sends or preserve source demand so encoded output cannot queue
 * without a bound.
 */
public final class JsonStreamEncoder<T> {
  @FunctionalInterface
  public interface Serializer<T> {
    /** Writes exactly one JSON value as UTF-8 using the application's native serializer context. */
    void write(T value, OutputStream output) throws IOException;
  }

  @FunctionalInterface
  public interface Projection {
    byte[] apply(byte[] json) throws IOException;
  }

  private final Serializer<T> serializer;
  private final Projection projection;
  private final boolean active;
  private final int maximum;
  private boolean failed;

  public JsonStreamEncoder(
      Serializer<T> serializer, Projection projection, boolean active, int maximum) {
    this.serializer = Objects.requireNonNull(serializer);
    this.projection = Objects.requireNonNull(projection);
    if (maximum < 1) throw new IllegalArgumentException("Payload limit must be positive");
    this.active = active;
    this.maximum = maximum;
  }

  /** Missing queries skip the projection callback and do not construct an additional tree. */
  public synchronized byte[] json(T value) throws IOException {
    checkUsable();
    try {
      var output = new BoundedBuffer(maximum);
      serializer.write(value, output);
      byte[] bytes = output.toByteArray();
      return checked(active ? projection.apply(bytes) : bytes);
    } catch (IOException | RuntimeException failure) {
      failed = true;
      throw failure;
    }
  }

  /** An explicitly encoded JSON value is always parsed and validated by the projection callback. */
  public synchronized byte[] encodedJson(String value) throws IOException {
    checkUsable();
    try {
      return checked(projection.apply(utf8(value, maximum)));
    } catch (IOException | RuntimeException failure) {
      failed = true;
      throw failure;
    }
  }

  public synchronized byte[] payload(StreamPayload<T> payload) throws IOException {
    checkUsable();
    try {
      if (payload instanceof StreamPayload.Json<T> json) return json(json.value());
      if (payload instanceof StreamPayload.EncodedJson<T> json) return encodedJson(json.value());
      if (payload instanceof StreamPayload.Text<T> text) return utf8(text.value(), maximum);
      throw new IllegalArgumentException("Absent event data is a control event, not a payload");
    } catch (IOException | RuntimeException failure) {
      failed = true;
      throw failure;
    }
  }

  /** Bounds the combined application-supplied SSE envelope fields before native framing. */
  public synchronized void metadata(String... fields) throws IOException {
    checkUsable();
    try {
      int remaining = maximum;
      for (String field : fields) if (field != null) remaining -= utf8(field, remaining).length;
    } catch (IOException | RuntimeException failure) {
      failed = true;
      throw failure;
    }
  }

  /**
   * Removes insignificant JSON whitespace without constructing a tree on the native bypass path.
   */
  public synchronized byte[] ndjson(T value) throws IOException {
    checkUsable();
    try {
      return compact(json(value));
    } catch (IOException | RuntimeException failure) {
      failed = true;
      throw failure;
    }
  }

  public synchronized byte[] ndjsonEncoded(String value) throws IOException {
    checkUsable();
    try {
      return compact(encodedJson(value));
    } catch (IOException | RuntimeException failure) {
      failed = true;
      throw failure;
    }
  }

  private byte[] checked(byte[] bytes) throws IOException {
    if (bytes.length > maximum)
      throw new IOException("Projected stream payload exceeds " + maximum + " bytes");
    return bytes;
  }

  private void checkUsable() throws IOException {
    if (failed) throw new IOException("Stream encoder cannot continue after a payload failure");
  }

  private static byte[] utf8(String value, int maximum) throws IOException {
    if (value.length() > maximum)
      throw new IOException("Stream payload or metadata exceeds its byte limit");
    var bytes =
        StandardCharsets.UTF_8
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value));
    if (bytes.remaining() > maximum)
      throw new IOException("Stream payload or metadata exceeds its byte limit");
    byte[] result = new byte[bytes.remaining()];
    bytes.get(result);
    return result;
  }

  private static byte[] compact(byte[] source) throws IOException {
    byte[] result = new byte[source.length];
    int size = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (byte value : source) {
      if (quoted) {
        if ((value & 255) < 32)
          throw new IOException("A JSON string contains an unescaped control character");
        result[size++] = value;
        if (escaped) escaped = false;
        else if (value == '\\') escaped = true;
        else if (value == '"') quoted = false;
      } else if (value != ' ' && value != '\t' && value != '\r' && value != '\n') {
        result[size++] = value;
        if (value == '"') quoted = true;
      }
    }
    if (quoted || size == 0) throw new IOException("Expected one complete JSON record");
    return Arrays.copyOf(result, size);
  }
}
