package com.github.gregwhitaker.catnap.micronaut;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/** Creates one bounded query cache per application unless the application supplies its own. */
@Factory
public class CatnapFactory {
  @Singleton
  @Requires(missingBeans = ProjectionService.class)
  ProjectionService projectionService(CatnapConfiguration configuration) {
    return new ProjectionService(configuration.settings());
  }
}
