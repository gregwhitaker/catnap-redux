package com.github.gregwhitaker.catnap.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import com.github.gregwhitaker.catnap.http.ProjectionSettings;
import io.smallrye.config.SmallRyeConfigBuilder;
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
