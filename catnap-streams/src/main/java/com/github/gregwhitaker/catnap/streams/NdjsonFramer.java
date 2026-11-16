package com.github.gregwhitaker.catnap.streams;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Incremental bounded LF/CRLF framing for asynchronous sources, independent of transport chunks.
 */
public final class NdjsonFramer {
  private final int maximum;
  private final ByteArrayOutputStream bytes;
  private long record = 1;
  private boolean carriageReturn;
  private boolean failed;
  private boolean ended;

  public NdjsonFramer(int maximum) {
    if (maximum < 1) throw new IllegalArgumentException("Record limit must be positive");
    this.maximum = maximum;
    bytes = new ByteArrayOutputStream(Math.min(maximum, 8192));
  }

  /** Supply one unsigned byte. Returns one complete record without its delimiter, or null. */
  public String accept(int value) throws IOException {
    if (failed || ended) throw new IOException("Framer cannot continue after failure or EOF");
    if (value < 0 || value > 255) throw new IllegalArgumentException("Expected an unsigned byte");
    try {
      if (carriageReturn && value != '\n') throw error(NdjsonException.Code.INVALID_FRAMING);
      if (value == '\r') {
        carriageReturn = true;
        return null;
      }
      if (value == '\n') {
        String result;
        try {
          result =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(ByteBuffer.wrap(bytes.toByteArray()))
                  .toString();
        } catch (CharacterCodingException malformed) {
          throw error(NdjsonException.Code.INVALID_UTF8);
        }
        if (result.chars().allMatch(c -> c == ' ' || c == '\t'))
          throw error(NdjsonException.Code.BLANK_RECORD);
        bytes.reset();
        carriageReturn = false;
        record++;
        return result;
      }
      if (bytes.size() == maximum) throw error(NdjsonException.Code.RECORD_TOO_LARGE);
      bytes.write(value);
      return null;
    } catch (IOException failure) {
      failed = true;
      throw failure;
    }
  }

  /** A clean EOF has no pending bytes or CR delimiter. */
  public void endOfInput() throws IOException {
    if (failed) throw new IOException("Framer cannot finish after failure");
    if (ended) return;
    ended = true;
    if (bytes.size() != 0 || carriageReturn) {
      failed = true;
      throw error(NdjsonException.Code.INVALID_FRAMING);
    }
  }

  private NdjsonException error(NdjsonException.Code code) {
    return new NdjsonException(code, record);
  }
}
