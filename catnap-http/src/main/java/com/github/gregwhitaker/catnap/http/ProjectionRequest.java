package com.github.gregwhitaker.catnap.http;

import com.github.gregwhitaker.catnap.core.CompiledQuery;
import com.github.gregwhitaker.catnap.core.JsonTreeAdapter;

/** Per-request/connection state; never stored in a ThreadLocal or a global payload cache. */
public final class ProjectionRequest {
  private final CompiledQuery query;
  private final ProjectionSettings settings;

  ProjectionRequest(CompiledQuery query, ProjectionSettings settings) {
    this.query = query;
    this.settings = settings;
  }

  public boolean active() {
    return query != null;
  }

  public ProjectionSettings settings() {
    return settings;
  }

  /**
   * Check active() before serializing; an inactive request must retain the framework's native path.
   */
  public <N> N evaluate(N document, JsonTreeAdapter<N> adapter) {
    return query == null ? document : query.evaluate(document, adapter, settings.evaluation());
  }
}
