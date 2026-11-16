package com.github.gregwhitaker.catnap.streams;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NdjsonFramerTest {
  @Test
  void acceptsSplitUnicodeAndMixedDelimitersWithoutChangingRecordText() throws Exception {
    var framer = new NdjsonFramer(64);
    var actual = new ArrayList<String>();
    for (byte value : "{\"label\":\"é😀\"}\r\n{}\nnull\r\n".getBytes(StandardCharsets.UTF_8)) {
      String record = framer.accept(value & 255);
      if (record != null) actual.add(record);
    }
    framer.endOfInput();
    framer.endOfInput();
    assertEquals(List.of("{\"label\":\"é😀\"}", "{}", "null"), actual);
    assertThrows(IOException.class, () -> framer.accept('x'));
  }

  @Test
  void rejectsBlankBareCarriageReturnAndUnterminatedRecords() {
    for (String input : List.of("\n", " \t\r\n", "{}\r{}\n", "{}", "{}\r")) {
      var framer = new NdjsonFramer(100);
      assertThrows(
          NdjsonException.class,
          () -> {
            for (byte value : input.getBytes(StandardCharsets.UTF_8)) framer.accept(value & 255);
            framer.endOfInput();
          },
          input);
      assertThrows(IOException.class, () -> framer.accept('x'));
    }
  }

  @Test
  void byteLimitExcludesDelimiterAndReportsOneBasedRecord() throws Exception {
    var framer = new NdjsonFramer(2);
    framer.accept('{');
    framer.accept('}');
    framer.accept('\r');
    assertEquals("{}", framer.accept('\n'));
    framer.accept('1');
    framer.accept('2');
    var failure = assertThrows(NdjsonException.class, () -> framer.accept('3'));
    assertEquals(NdjsonException.Code.RECORD_TOO_LARGE, failure.code());
    assertEquals(2, failure.record());
  }

  @Test
  void rejectsInvalidUtf8BeforeEmittingAnyRecord() throws Exception {
    var framer = new NdjsonFramer(8);
    framer.accept(0xc3);
    framer.accept(0x28);
    var failure = assertThrows(NdjsonException.class, () -> framer.accept('\n'));
    assertEquals(NdjsonException.Code.INVALID_UTF8, failure.code());
    assertThrows(IOException.class, framer::endOfInput);
  }
}
