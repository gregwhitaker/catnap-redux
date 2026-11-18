package com.github.gregwhitaker.catnap.micronaut;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import io.micronaut.context.ApplicationContext;
import java.util.List;
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
  void explicitDialectsOverrideTheDefault() {
    for (var dialect : QueryDialect.values()) {
      try (var context = ApplicationContext.run(Map.of("catnap.dialect", dialect.name()))) {
        var service = context.getBean(ProjectionService.class);
        assertEquals(dialect, service.settings().dialect());
        assertTrue(
            service
                .bind(List.of(dialect == QueryDialect.LINKEDIN ? "images:(url)" : "images(url)"))
                .active());
        if (dialect == QueryDialect.CATNAP) {
          assertTrue(service.bind(List.of("images(url)[size==medium]")).active());
        } else {
          assertThrows(
              QueryRequestException.class, () -> service.bind(List.of("images[size==medium]")));
        }
      }
    }
  }

  @Test
  void invalidLimitsFailWhenCreatingTheApplicationService() {
    try (var context = ApplicationContext.run(Map.of("catnap.max-payload-bytes", 0))) {
      assertThrows(Exception.class, () -> context.getBean(ProjectionService.class));
    }
  }
}
