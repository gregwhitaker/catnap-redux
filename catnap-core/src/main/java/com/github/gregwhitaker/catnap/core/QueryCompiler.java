package com.github.gregwhitaker.catnap.core;

import java.util.LinkedHashMap;

/**
 * Optional per-instance, bounded LRU compilation cache. Only successful queries are cached. Keys
 * include dialect, exact expression, and compilation bounds; payloads are never stored here.
 * Concurrent misses may compile independently, then share the winning immutable plan.
 */
public final class QueryCompiler {
  private record Key(String expression, QueryDialect dialect, CompileOptions options) {}

  private final int capacity;
  private final LinkedHashMap<Key, CompiledQuery> cache = new LinkedHashMap<>(16, 0.75f, true);

  /**
   * @param capacity maximum cached queries; zero disables caching
   */
  public QueryCompiler(int capacity) {
    if (capacity < 0) throw new IllegalArgumentException("Cache capacity must not be negative");
    this.capacity = capacity;
  }

  public CompiledQuery compile(String expression, QueryDialect dialect) {
    return compile(expression, dialect, CompileOptions.DEFAULT);
  }

  public CompiledQuery compile(String expression, QueryDialect dialect, CompileOptions options) {
    QueryParser.validateArguments(expression, dialect, options);
    if (capacity == 0) return Catnap.compile(expression, dialect, options);
    Key key = new Key(expression, dialect, options);
    synchronized (cache) {
      CompiledQuery found = cache.get(key);
      if (found != null) return found;
    }
    CompiledQuery compiled = Catnap.compile(expression, dialect, options);
    synchronized (cache) {
      CompiledQuery found = cache.get(key);
      if (found != null) return found;
      if (cache.size() == capacity) cache.remove(cache.keySet().iterator().next());
      cache.put(key, compiled);
      return compiled;
    }
  }

  public int cacheSize() {
    synchronized (cache) {
      return cache.size();
    }
  }

  /** Remove retained queries. Concurrent in-progress compilations may populate it again. */
  public void clearCache() {
    synchronized (cache) {
      cache.clear();
    }
  }
}
