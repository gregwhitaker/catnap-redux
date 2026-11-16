package com.github.gregwhitaker.catnap.http;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder;
import com.github.gregwhitaker.catnap.http.stream.StreamPayload;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StreamEncoderTest {
  @Test
  void missingQueryKeepsNativeSerializationWithoutAnAdditionalTree() throws Exception {
    var encoder =
        new JsonStreamEncoder<String>(
            (value, out) -> out.write(value.getBytes(StandardCharsets.UTF_8)),
            bytes -> {
              fail("Bypassed typed payload must not be parsed");
              return bytes;
            },
            false,
            1000);
    assertEquals(
        "{\"a\":\"spaces and \\\"quotes\\\"\"}",
        new String(
            encoder.ndjson("{\n  \"a\": \"spaces and \\\"quotes\\\"\"\n}"),
            StandardCharsets.UTF_8));
  }

  @Test
  void explicitTextBypassesProjectionWhileExplicitJsonIsValidated() throws Exception {
    var calls = new AtomicInteger();
    var encoder =
        new JsonStreamEncoder<String>(
            (value, out) -> out.write(value.getBytes(StandardCharsets.UTF_8)),
            bytes -> {
              calls.incrementAndGet();
              return "{}".getBytes(StandardCharsets.UTF_8);
            },
            false,
            100);
    String literal = "{\"private\":1}";
    assertEquals(
        literal, new String(encoder.payload(StreamPayload.text(literal)), StandardCharsets.UTF_8));
    assertEquals(
        "{}",
        new String(encoder.payload(StreamPayload.encodedJson(literal)), StandardCharsets.UTF_8));
    assertEquals(1, calls.get());
  }

  @Test
  void overflowPoisonsTheConnectionEncoderWithoutCallingProjection() {
    var calls = new AtomicInteger();
    var encoder =
        new JsonStreamEncoder<String>(
            (value, out) -> {
              calls.incrementAndGet();
              out.write(value.getBytes(StandardCharsets.UTF_8));
            },
            bytes -> {
              fail("Overflow must fail before parsing");
              return bytes;
            },
            true,
            4);
    assertThrows(IOException.class, () -> encoder.json("long value"));
    assertThrows(IOException.class, () -> encoder.json("{}"));
    assertEquals(1, calls.get());
  }

  @Test
  void metadataUsesUtf8BytesAndCannotBypassTheFailurePolicy() {
    var encoder = new JsonStreamEncoder<String>((value, out) -> {}, bytes -> bytes, false, 5);
    assertThrows(IOException.class, () -> encoder.metadata("éé", "é"));
    assertThrows(IOException.class, () -> encoder.payload(StreamPayload.text("ok")));
  }
}
