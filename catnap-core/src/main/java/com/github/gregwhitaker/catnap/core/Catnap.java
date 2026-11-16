package com.github.gregwhitaker.catnap.core;

/** Framework-independent entry point. Static compilation does not retain a global cache. */
public final class Catnap {
  private Catnap() {}

  public static CompiledQuery compile(String expression, QueryDialect dialect) {
    return compile(expression, dialect, CompileOptions.DEFAULT);
  }

  public static CompiledQuery compile(
      String expression, QueryDialect dialect, CompileOptions options) {
    return new CompiledQuery(new QueryParser(expression, dialect, options).parse());
  }
}
