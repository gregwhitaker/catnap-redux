package com.github.gregwhitaker.catnap.benchmarks;

import com.github.gregwhitaker.catnap.core.Catnap;
import com.github.gregwhitaker.catnap.core.CompiledQuery;
import com.github.gregwhitaker.catnap.core.QueryCompiler;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class QueryBenchmark {
  @Param({"SMALL", "LARGE_ARRAY", "DEEP", "SPARSE", "WILDCARD"})
  public String workload;

  @Param({"GOOGLE", "LINKEDIN"})
  public QueryDialect dialect;

  private String expression;
  private QueryCompiler compiler;

  @Setup
  public void setup() {
    expression = Workload.named(workload).query(dialect);
    compiler = new QueryCompiler(256);
    compiler.compile(expression, dialect);
  }

  /** Deliberately bypasses the query cache. */
  @Benchmark
  public CompiledQuery compile() {
    return Catnap.compile(expression, dialect);
  }

  /** One hot key in the bounded default-capacity cache. */
  @Benchmark
  public CompiledQuery cachedCompile() {
    return compiler.compile(expression, dialect);
  }
}
