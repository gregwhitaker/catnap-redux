package com.github.gregwhitaker.catnap.benchmarks;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.jackson3.Jackson3Json;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class WorkloadTest {
  @ParameterizedTest
  @ValueSource(strings = {"SMALL", "LARGE_ARRAY", "DEEP", "SPARSE", "WILDCARD"})
  void benchmarksMeasureValidEquivalentQueriesAndCompleteResponses(String name) throws Exception {
    var mapper = JsonMapper.builder().build();
    var json = new Jackson3Json(mapper);
    var data = Workload.named(name);
    var input = json.parse(mapper.writeValueAsBytes(data.value()));
    var expected = json.parse(mapper.writeValueAsBytes(data.expected()));
    for (var dialect : QueryDialect.values()) {
      assertEquals(
          expected, Catnap.compile(data.query(dialect), dialect).evaluate(input, json.adapter()));
    }
    var benchmark = new PipelineBenchmark();
    benchmark.workload = name;
    benchmark.setup();
    assertEquals(expected, json.parse(benchmark.completeResponseProcessing()));
    assertEquals(input, json.parse(benchmark.nativeSerializationBaseline()));
  }

  @ParameterizedTest
  @ValueSource(ints = {1024, 65536})
  void streamBaselinesEmitTheSameFullDocument(int bytes) throws Exception {
    var benchmark = new StreamItemBenchmark();
    benchmark.paddingBytes = bytes;
    benchmark.setup();
    assertArrayEquals(benchmark.nativeRecordEncoding(), benchmark.bypassRecordEncoding());
    assertArrayEquals(benchmark.nativeRecordEncoding(), benchmark.projectedRecordEncoding());
  }
}
