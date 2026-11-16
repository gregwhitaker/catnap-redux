package com.github.gregwhitaker.catnap.quarkus;

import com.github.gregwhitaker.catnap.core.CompileOptions;
import com.github.gregwhitaker.catnap.core.EvaluationOptions;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import io.quarkus.arc.DefaultBean;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/** Creates and validates the application's bounded query service during startup. */
@Singleton
public class CatnapServices {
  @Produces
  @Singleton
  @DefaultBean
  @Startup
  public ProjectionService projectionService(CatnapConfig config) {
    return new ProjectionService(
        new ProjectionSettings(
            config.enabled(),
            config.parameter(),
            config.dialect(),
            new CompileOptions(
                config.maxQueryLength(), config.maxQueryDepth(), config.maxSelectors()),
            new EvaluationOptions(config.maxJsonDepth(), config.maxWork()),
            config.cacheCapacity(),
            config.maxPayloadBytes()));
  }
}
