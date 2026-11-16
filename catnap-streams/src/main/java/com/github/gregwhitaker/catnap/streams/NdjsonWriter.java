package com.github.gregwhitaker.catnap.streams;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Writes already serialized compact JSON, with a byte limit and a flush after each LF record. */
public final class NdjsonWriter implements Closeable {
  private final OutputStream output;
  private final int maxRecordBytes;
  private long record = 1;
  private boolean failed;

  public NdjsonWriter(OutputStream output, int maxRecordBytes) {
    if (maxRecordBytes < 1) throw new IllegalArgumentException("Record limit must be positive");
    this.output = Objects.requireNonNull(output);
    this.maxRecordBytes = maxRecordBytes;
  }

  /** The caller's JSON serializer must provide one valid, compact JSON value. */
  public void writeRecord(String json) throws IOException {
    if (failed) throw new IOException("Writer cannot continue after a failed record");
    Objects.requireNonNull(json);
    try {
      if (json.isBlank()) throw new NdjsonException(NdjsonException.Code.BLANK_RECORD, record);
      if (json.indexOf('\n') >= 0 || json.indexOf('\r') >= 0)
        throw new NdjsonException(NdjsonException.Code.INVALID_FRAMING, record);
      if (json.length() > maxRecordBytes)
        throw new NdjsonException(NdjsonException.Code.RECORD_TOO_LARGE, record);
      var bytes =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(json));
      if (bytes.remaining() > maxRecordBytes)
        throw new NdjsonException(NdjsonException.Code.RECORD_TOO_LARGE, record);
      output.write(bytes.array(), bytes.position(), bytes.remaining());
      output.write('\n');
      output.flush();
      record++;
    } catch (IOException error) {
      failed = true;
      throw error;
    }
  }

  @Override
  public void close() throws IOException {
    output.close();
  }
}
