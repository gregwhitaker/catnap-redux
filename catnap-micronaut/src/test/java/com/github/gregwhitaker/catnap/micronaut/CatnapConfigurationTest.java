package com.github.gregwhitaker.catnap.micronaut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CatnapConfigurationTest {
  @Test
  void nativeConfigurationDefaultsMatchTheCommonContract() {
    try (var context = ApplicationContext.run()) {
      assertEquals(ProjectionSettings.DEFAULT, context.getBean(ProjectionService.class).settings());
    }
  }

  @Test
  void invalidLimitsFailWhenCreatingTheApplicationService() {
    try (var context = ApplicationContext.run(Map.of("catnap.max-payload-bytes", 0))) {
      assertThrows(Exception.class, () -> context.getBean(ProjectionService.class));
    }
  }
}
