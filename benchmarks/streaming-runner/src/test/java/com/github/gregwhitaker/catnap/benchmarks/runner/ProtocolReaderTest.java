package com.github.gregwhitaker.catnap.benchmarks.runner;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProtocolReaderTest {
  private ProtocolReader reader(String value, boolean sse) {
    return new ProtocolReader(
        new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)), sse);
  }

  @Test
  void readsNdjsonAndCountsUtf8BodyBytes() throws Exception {
    String wire = "{\"name\":\"é\"}\n{\"id\":2}\r\n";
    var input = reader(wire, false);
    assertEquals("{\"name\":\"é\"}", input.next().json());
    assertEquals("{\"id\":2}", input.next().json());
    assertNull(input.next());
    assertEquals(wire.getBytes(StandardCharsets.UTF_8).length, input.bytes());
  }

  @Test
  void readsSseMetadataAndMultilineDataAfterControlFrames() throws Exception {
    var input =
        reader(
            ": heartbeat\nretry: 500\n\n: benchmark\r\nid:1\r\nevent: sample\r\nretry:1000\r\ncontent-type:application/json\r\ndata: {\r\ndata: \"id\":1}\r\n\r\n",
            true);
    assertEquals(
        new ProtocolReader.Frame("{\n\"id\":1}", "1", "sample", "1000", "benchmark"), input.next());
    assertNull(input.next());
  }

  @Test
  void rejectsTruncatedRecordsAndEvents() {
    assertThrows(EOFException.class, () -> reader("{}", false).next());
    assertThrows(EOFException.class, () -> reader("data:{}\n", true).next());
  }

  @Test
  void rejectsMalformedUtf8AndBlankNdjson() {
    var input =
        new ProtocolReader(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28, 10}), false);
    assertThrows(IOException.class, input::next);
    assertThrows(IOException.class, () -> reader("\n", false).next());
  }

  @Test
  void boundsLinesAndEvents() {
    assertThrows(IOException.class, () -> reader("x".repeat(65537) + "\n", false).next());
    assertThrows(
        IOException.class,
        () -> reader(("data:" + "x".repeat(40000) + "\n").repeat(2) + "\n", true).next());
  }
}
