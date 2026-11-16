package com.github.gregwhitaker.catnap.http;

import com.github.gregwhitaker.catnap.core.LimitExceededException;
import com.github.gregwhitaker.catnap.core.QueryCompiler;
import com.github.gregwhitaker.catnap.core.QuerySyntaxException;
import java.util.List;
import java.util.Objects;

/** Binds already-decoded HTTP parameter values without depending on a particular web framework. */
public final class ProjectionService {
  private final ProjectionSettings settings;
  private final QueryCompiler compiler;

  public ProjectionService(ProjectionSettings settings) {
    this.settings = Objects.requireNonNull(settings);
    this.compiler = new QueryCompiler(settings.cacheCapacity());
  }

  public ProjectionSettings settings() {
    return settings;
  }

  public ProjectionRequest bind(List<String> values) {
    return bind(values, true);
  }

  public ProjectionRequest bind(List<String> values, boolean endpointEnabled) {
    if (!settings.enabled() || !endpointEnabled || values == null || values.isEmpty())
      return bypass();
    if (values.size() != 1)
      throw new QueryRequestException("MULTIPLE_QUERY_PARAMETERS", null, null);
    String value = Objects.requireNonNull(values.get(0), "Decoded parameter value");
    if (value.chars().allMatch(c -> c == ' ' || c == '\t' || c == '\n' || c == '\r'))
      return bypass();
    try {
      return new ProjectionRequest(
          compiler.compile(value, settings.dialect(), settings.compilation()), settings);
    } catch (QuerySyntaxException error) {
      throw new QueryRequestException(error.code().name(), error.position(), error);
    } catch (LimitExceededException error) {
      throw new QueryRequestException(error.limit().name(), error.position(), error);
    }
  }

  private ProjectionRequest bypass() {
    return new ProjectionRequest(null, settings);
  }
}
