package com.github.gregwhitaker.catnap.spring.boot;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Supplies validated settings and a bounded, application-scoped compiled-query cache. */
@AutoConfiguration
@EnableConfigurationProperties(CatnapProperties.class)
public class CatnapAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  ProjectionService catnapProjectionService(CatnapProperties properties) {
    return new ProjectionService(properties.settings());
  }
}
