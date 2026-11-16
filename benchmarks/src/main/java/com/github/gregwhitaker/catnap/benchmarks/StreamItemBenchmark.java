package com.github.gregwhitaker.catnap.benchmarks;

import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.stream.JsonStreamEncoder;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class StreamItemBenchmark {
  @Param({"1024", "65536"})
  public int paddingBytes;

  private Map<String, Object> value;
  private ObjectWriter writer;
  private JsonStreamEncoder<Map<String, Object>> bypass;
  private JsonStreamEncoder<Map<String, Object>> projected;

  @Setup
  public void setup() {
    value = Map.of("id", 1, "padding", "x".repeat(paddingBytes));
    var mapper = JsonMapper.builder().build();
    writer = mapper.writer();
    var json = new Jackson3Json(mapper);
    var query = Catnap.compile("*", QueryDialect.GOOGLE);
    JsonStreamEncoder.Projection projection =
        bytes -> {
          var result = query.evaluate(json.parse(bytes), json.adapter());
          var output = new BoundedBuffer(1_048_576);
          writer.writeValue(output, result);
          return output.toByteArray();
        };
    bypass =
        new JsonStreamEncoder<>(
            (item, output) -> writer.writeValue(output, item), projection, false, 1_048_576);
    projected =
        new JsonStreamEncoder<>(
            (item, output) -> writer.writeValue(output, item), projection, true, 1_048_576);
  }

  @Benchmark
  public byte[] nativeRecordEncoding() {
    var output = new ByteArrayOutputStream();
    writer.writeValue(output, value);
    return output.toByteArray();
  }

  @Benchmark
  public byte[] bypassRecordEncoding() throws IOException {
    return bypass.ndjson(value);
  }

  @Benchmark
  public byte[] projectedRecordEncoding() throws IOException {
    return projected.ndjson(value);
  }
}
