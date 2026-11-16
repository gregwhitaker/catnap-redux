package com.github.gregwhitaker.catnap.streams;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class NdjsonTest {
  @Test
  void splitUtf8CrLfMultipleRecordsAndEscapedNewlines() throws Exception {
    byte[] source =
        "{\"name\":\"東京😀\"}\r\n{\"name\":\"a\\nb\"}\nnull\n".getBytes(StandardCharsets.UTF_8);
    for (int chunk = 1; chunk <= source.length; chunk++) {
      final int size = chunk;
      var stream =
          new ByteArrayInputStream(source) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
              return super.read(bytes, offset, Math.min(length, size));
            }
          };
      try (var reader = new NdjsonReader(stream, 100)) {
        assertEquals("{\"name\":\"東京😀\"}", reader.nextRecord());
        assertEquals("{\"name\":\"a\\nb\"}", reader.nextRecord());
        assertEquals("null", reader.nextRecord());
        assertNull(reader.nextRecord());
      }
    }
  }

  @Test
  void strictFramingAndSizeLimits() throws Exception {
    for (String malformed : List.of("\n", " \t\n", "{}", "{}\r", "{}\r{}\n")) {
      var reader =
          new NdjsonReader(
              new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8)), 100);
      NdjsonException error = assertThrows(NdjsonException.class, reader::nextRecord);
      assertEquals(1, error.record());
      assertThrows(IOException.class, reader::nextRecord);
    }
    var oversized =
        new NdjsonReader(new ByteArrayInputStream("1234\n".getBytes(StandardCharsets.UTF_8)), 3);
    assertEquals(
        NdjsonException.Code.RECORD_TOO_LARGE,
        assertThrows(NdjsonException.class, oversized::nextRecord).code());
    var exact =
        new NdjsonReader(new ByteArrayInputStream("123\r\n".getBytes(StandardCharsets.UTF_8)), 3);
    assertEquals("123", exact.nextRecord());
    assertNull(exact.nextRecord());
    var badUtf8 =
        new NdjsonReader(new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28, '\n'}), 100);
    assertEquals(
        NdjsonException.Code.INVALID_UTF8,
        assertThrows(NdjsonException.class, badUtf8::nextRecord).code());
  }

  @Test
  void writerEmitsLfFlushesAndRejectsBeforeWritingBadRecords() throws Exception {
    class Output extends ByteArrayOutputStream {
      int flushes;

      @Override
      public void flush() {
        flushes++;
      }
    }
    var output = new Output();
    var writer = new NdjsonWriter(output, 7);
    writer.writeRecord("{}");
    writer.writeRecord("null");
    assertEquals("{}\nnull\n", output.toString(StandardCharsets.UTF_8));
    assertEquals(2, output.flushes);
    var error = assertThrows(NdjsonException.class, () -> writer.writeRecord("\"東京\""));
    assertEquals(NdjsonException.Code.RECORD_TOO_LARGE, error.code());
    assertEquals(3, error.record());
    assertEquals("{}\nnull\n", output.toString(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> writer.writeRecord("{}"));
    assertThrows(NdjsonException.class, () -> new NdjsonWriter(output, 100).writeRecord("{\n}"));
  }

  @Test
  void emptyStreamAndExplicitClose() throws Exception {
    class Input extends ByteArrayInputStream {
      boolean closed;

      Input() {
        super(new byte[0]);
      }

      @Override
      public void close() {
        closed = true;
      }
    }
    var input = new Input();
    try (var reader = new NdjsonReader(input, 1)) {
      assertNull(reader.nextRecord());
    }
    assertTrue(input.closed);
  }
}
