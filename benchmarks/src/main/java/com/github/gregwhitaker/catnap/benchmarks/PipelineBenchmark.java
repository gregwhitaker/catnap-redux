package com.github.gregwhitaker.catnap.benchmarks;

import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.CompiledQuery;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class PipelineBenchmark {
  @Param({"SMALL", "LARGE_ARRAY", "DEEP", "SPARSE", "WILDCARD"})
  public String workload;

  private Workload data;
  private Jackson3Json json;
  private ObjectWriter writer;
  private CompiledQuery query;
  private byte[] encoded;
  private JsonNode tree;
  private JsonNode selected;

  @Setup
  public void setup() {
    data = Workload.named(workload);
    var mapper = JsonMapper.builder().build();
    writer = mapper.writer();
    json = new Jackson3Json(mapper);
    query = Catnap.compile(data.google(), QueryDialect.GOOGLE);
    encoded = writer.writeValueAsBytes(data.value());
    tree = json.parse(encoded);
    selected = query.evaluate(tree, json.adapter());
    var expected = json.parse(writer.writeValueAsBytes(data.expected()));
    if (!selected.equals(expected)) throw new IllegalStateException("Wrong workload: " + workload);
    if (encoded.length > 1_048_576)
      throw new IllegalStateException("Input exceeds default byte cap");
  }

  @Benchmark
  public JsonNode parseTree() {
    return json.parse(encoded);
  }

  @Benchmark
  public JsonNode domainToTree() {
    return json.toTree(data.value(), writer);
  }

  @Benchmark
  public JsonNode evaluateExistingTree() {
    return query.evaluate(tree, json.adapter());
  }

  @Benchmark
  public byte[] serializeSelectedTree() {
    return writer.writeValueAsBytes(selected);
  }

  @Benchmark
  public byte[] nativeSerializationBaseline() {
    return writer.writeValueAsBytes(data.value());
  }

  /**
   * Actual bounded writer capture, parse, projection and encoding; excludes HTTP transport/binding.
   */
  @Benchmark
  public byte[] completeResponseProcessing() throws IOException {
    var captured = new BoundedBuffer(1_048_576);
    writer.writeValue(captured, data.value());
    var result = query.evaluate(json.parse(captured.toByteArray()), json.adapter());
    var output = new BoundedBuffer(1_048_576);
    writer.writeValue(output, result);
    return output.toByteArray();
  }
}
