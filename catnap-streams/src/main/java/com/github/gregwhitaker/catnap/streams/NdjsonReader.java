package com.github.gregwhitaker.catnap.streams;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Pulls one bounded UTF-8 NDJSON record at a time. Accepts LF/CRLF and rejects blank or
 * unterminated records. JSON syntax validation belongs to the selected JSON integration. Closing
 * this reader closes its input. Instances are not thread-safe.
 */
public final class NdjsonReader implements Closeable {
  private final InputStream input;
  private final int maxRecordBytes;
  private long record = 1;
  private boolean failed;

  public NdjsonReader(InputStream input, int maxRecordBytes) {
    if (maxRecordBytes < 1) throw new IllegalArgumentException("Record limit must be positive");
    this.input = new BufferedInputStream(Objects.requireNonNull(input), 8192);
    this.maxRecordBytes = maxRecordBytes;
  }

  /** Return a complete record without its delimiter, or null at a clean end of stream. */
  public String nextRecord() throws IOException {
    if (failed) throw new IOException("Reader cannot continue after a failed record");
    try {
      var bytes = new ByteArrayOutputStream(Math.min(maxRecordBytes, 8192));
      while (true) {
        int b = input.read();
        if (b == -1) {
          if (bytes.size() == 0) return null;
          throw error(NdjsonException.Code.INVALID_FRAMING);
        }
        if (b == '\r') {
          if (input.read() != '\n') throw error(NdjsonException.Code.INVALID_FRAMING);
          break;
        }
        if (b == '\n') break;
        if (bytes.size() == maxRecordBytes) throw error(NdjsonException.Code.RECORD_TOO_LARGE);
        bytes.write(b);
      }
      String value;
      try {
        value =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
      } catch (CharacterCodingException malformed) {
        throw error(NdjsonException.Code.INVALID_UTF8);
      }
      if (value.chars().allMatch(c -> c == ' ' || c == '\t'))
        throw error(NdjsonException.Code.BLANK_RECORD);
      record++;
      return value;
    } catch (IOException error) {
      failed = true;
      throw error;
    }
  }

  private NdjsonException error(NdjsonException.Code code) {
    return new NdjsonException(code, record);
  }

  @Override
  public void close() throws IOException {
    input.close();
  }
}
