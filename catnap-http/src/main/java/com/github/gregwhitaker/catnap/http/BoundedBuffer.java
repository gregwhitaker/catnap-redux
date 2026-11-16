package com.github.gregwhitaker.catnap.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Capture complete serialized JSON before response commitment, failing before exceeding its cap.
 */
public final class BoundedBuffer extends OutputStream {
  private final ByteArrayOutputStream bytes;
  private final int maximum;
  private boolean failed;

  public BoundedBuffer(int maximum) {
    if (maximum < 1) throw new IllegalArgumentException("Payload limit must be positive");
    this.maximum = maximum;
    bytes = new ByteArrayOutputStream(Math.min(maximum, 8192));
  }

  @Override
  public void write(int value) throws IOException {
    check(1);
    bytes.write(value);
  }

  @Override
  public void write(byte[] value, int offset, int length) throws IOException {
    Objects.checkFromIndexSize(offset, length, value.length);
    check(length);
    bytes.write(value, offset, length);
  }

  public byte[] toByteArray() throws IOException {
    if (failed) throw new IOException("Cannot use a truncated payload after exceeding its limit");
    return bytes.toByteArray();
  }

  private void check(int additional) throws IOException {
    if (failed || additional > maximum - bytes.size()) {
      failed = true;
      throw new IOException(
          "Serialized JSON exceeds maximum payload size of " + maximum + " bytes");
    }
  }
}
