package com.github.gregwhitaker.catnap.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.core.QueryDialect;
import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import com.github.gregwhitaker.catnap.http.QueryRequestException;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatnapConfigurationTest {
  @Test
  void defaultsMatchEveryOtherAdapter() {
    var config =
        new SmallRyeConfigBuilder()
            .withMapping(CatnapConfig.class)
            .build()
            .getConfigMapping(CatnapConfig.class);
    assertEquals(
        ProjectionSettings.DEFAULT, new CatnapServices().projectionService(config).settings());
  }

  @Test
  void explicitDialectsOverrideTheDefault() {
    for (var dialect : QueryDialect.values()) {
      var config =
          new SmallRyeConfigBuilder()
              .withMapping(CatnapConfig.class)
              .withDefaultValue("catnap.dialect", dialect.name())
              .build()
              .getConfigMapping(CatnapConfig.class);
      var service = new CatnapServices().projectionService(config);
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

  @Test
  void invalidSettingsAreRejectedWhenTheStartupServiceIsCreated() {
    var config =
        new SmallRyeConfigBuilder()
            .withMapping(CatnapConfig.class)
            .withDefaultValue("catnap.max-payload-bytes", "0")
            .build()
            .getConfigMapping(CatnapConfig.class);
    assertThrows(
        IllegalArgumentException.class, () -> new CatnapServices().projectionService(config));
  }
}
