package com.github.gregwhitaker.catnap.streams;

import java.io.IOException;

/** Invalid or oversized NDJSON record, with its one-based logical record number. */
public final class NdjsonException extends IOException {
  public enum Code {
    RECORD_TOO_LARGE,
    INVALID_UTF8,
    INVALID_FRAMING,
    BLANK_RECORD
  }

  private final Code code;
  private final long record;

  NdjsonException(Code code, long record) {
    super(code + " at NDJSON record " + record);
    this.code = code;
    this.record = record;
  }

  public Code code() {
    return code;
  }

  public long record() {
    return record;
  }
}
