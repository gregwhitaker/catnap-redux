package com.github.gregwhitaker.catnap.micronaut;

import com.github.gregwhitaker.catnap.core.CompileOptions;
import com.github.gregwhitaker.catnap.core.EvaluationOptions;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.bind.annotation.Bindable;

/** Micronaut's native immutable configuration binding for the shared Catnap settings. */
@ConfigurationProperties("catnap")
public record CatnapConfiguration(
    @Bindable(defaultValue = "true") boolean enabled,
    @Bindable(defaultValue = "fields") String parameter,
    @Bindable(defaultValue = "GOOGLE") QueryDialect dialect,
    @Bindable(defaultValue = "16384") int maxQueryLength,
    @Bindable(defaultValue = "64") int maxQueryDepth,
    @Bindable(defaultValue = "4096") int maxSelectors,
    @Bindable(defaultValue = "128") int maxJsonDepth,
    @Bindable(defaultValue = "1000000") long maxWork,
    @Bindable(defaultValue = "256") int cacheCapacity,
    @Bindable(defaultValue = "1048576") int maxPayloadBytes) {
  @ConfigurationInject
  public CatnapConfiguration {}

  public ProjectionSettings settings() {
    return new ProjectionSettings(
        enabled,
        parameter,
        dialect,
        new CompileOptions(maxQueryLength, maxQueryDepth, maxSelectors),
        new EvaluationOptions(maxJsonDepth, maxWork),
        cacheCapacity,
        maxPayloadBytes);
  }
}
