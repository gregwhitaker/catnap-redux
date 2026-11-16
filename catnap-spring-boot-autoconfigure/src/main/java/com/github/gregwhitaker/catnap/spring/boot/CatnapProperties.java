package com.github.gregwhitaker.catnap.spring.boot;

import com.github.gregwhitaker.catnap.core.CompileOptions;
import com.github.gregwhitaker.catnap.core.EvaluationOptions;
import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Spring Boot settings shared by the servlet and reactive adapters. */
@ConfigurationProperties("catnap")
public record CatnapProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("fields") String parameter,
    @DefaultValue("GOOGLE") QueryDialect dialect,
    @DefaultValue("16384") int maxQueryLength,
    @DefaultValue("64") int maxQueryDepth,
    @DefaultValue("4096") int maxSelectors,
    @DefaultValue("128") int maxJsonDepth,
    @DefaultValue("1000000") long maxWork,
    @DefaultValue("256") int cacheCapacity,
    @DefaultValue("1048576") int maxPayloadBytes) {

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
