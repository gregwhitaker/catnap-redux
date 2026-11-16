package com.github.gregwhitaker.catnap.examples.standalone;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.QueryDialect;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StandaloneTest {
  @Test
  void transformsRecordsWithoutRemovingEmptyResults() throws Exception {
    var output = new ByteArrayOutputStream();
    Standalone.transform(
        new ByteArrayInputStream(
            "{\"id\":1,\"secret\":2}\r\n{\"secret\":3}\nnull\n".getBytes(StandardCharsets.UTF_8)),
        output,
        "id",
        QueryDialect.GOOGLE);
    assertEquals("{\"id\":1}\n{}\nnull\n", output.toString(StandardCharsets.UTF_8));
  }

  @Test
  void emitsBeforeSourceCompletes() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    var input = new PipedInputStream();
    var producer = new PipedOutputStream(input);
    var emitted = new CountDownLatch(1);
    var output =
        new ByteArrayOutputStream() {
          @Override
          public void flush() {
            emitted.countDown();
          }
        };
    try {
      var task =
          executor.submit(
              () -> {
                Standalone.transform(input, output, "id", QueryDialect.GOOGLE);
                return null;
              });
      producer.write("{\"id\":1,\"hidden\":2}\n".getBytes(StandardCharsets.UTF_8));
      producer.flush();
      assertTrue(emitted.await(5, TimeUnit.SECONDS));
      assertFalse(task.isDone(), "Source remains open after first projected record");
      assertEquals("{\"id\":1}\n", output.toString(StandardCharsets.UTF_8));
      producer.close();
      task.get(5, TimeUnit.SECONDS);
    } finally {
      producer.close();
      input.close();
      executor.shutdownNow();
    }
  }

  @Test
  void malformedRecordNeverFallsBackToUnprojectedData() throws Exception {
    var output = new ByteArrayOutputStream();
    assertThrows(
        Exception.class,
        () ->
            Standalone.transform(
                new ByteArrayInputStream(
                    "{\"id\":1}\n{\"secret\":2\n".getBytes(StandardCharsets.UTF_8)),
                output,
                "id",
                QueryDialect.GOOGLE));
    assertEquals("{\"id\":1}\n", output.toString(StandardCharsets.UTF_8));
  }
}
