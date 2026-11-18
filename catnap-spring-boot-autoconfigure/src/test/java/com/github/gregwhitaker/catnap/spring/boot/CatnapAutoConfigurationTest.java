package com.github.gregwhitaker.catnap.spring.boot;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CatnapAutoConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CatnapAutoConfiguration.class,
                  CatnapMvcAutoConfiguration.class,
                  CatnapWebFluxAutoConfiguration.class));

  @Test
  void defaultsMatchTheSharedContractWithoutMvcOnTheRuntimeClasspath() {
    runner.run(
        context -> {
          assertNull(context.getStartupFailure());
          assertEquals(
              ProjectionSettings.DEFAULT, context.getBean(ProjectionService.class).settings());
          assertFalse(context.containsBean("catnapMvcFilter"));
        });
  }

  @Test
  void explicitDialectsOverrideTheDefault() {
    for (var dialect : QueryDialect.values()) {
      runner
          .withPropertyValues("catnap.dialect=" + dialect)
          .run(
              context -> {
                assertNull(context.getStartupFailure());
                var service = context.getBean(ProjectionService.class);
                assertEquals(dialect, service.settings().dialect());
                assertTrue(
                    service
                        .bind(
                            List.of(
                                dialect == QueryDialect.LINKEDIN ? "images:(url)" : "images(url)"))
                        .active());
                if (dialect == QueryDialect.CATNAP) {
                  assertTrue(service.bind(List.of("images(url)[size==medium]")).active());
                } else {
                  assertThrows(
                      QueryRequestException.class,
                      () -> service.bind(List.of("images[size==medium]")));
                }
              });
    }
  }

  @Test
  void invalidBoundsFailAtStartup() {
    runner
        .withPropertyValues("catnap.max-payload-bytes=0")
        .run(context -> assertNotNull(context.getStartupFailure()));
  }
}
