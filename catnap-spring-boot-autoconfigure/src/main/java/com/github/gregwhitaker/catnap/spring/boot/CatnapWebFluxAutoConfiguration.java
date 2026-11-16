package com.github.gregwhitaker.catnap.spring.boot;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.spring.webflux.CatnapWebFluxFilter;
import com.github.gregwhitaker.catnap.spring.webflux.CatnapWebFluxHandlerAdapter;
import com.github.gregwhitaker.catnap.spring.webflux.CatnapWebFluxStreams;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerAdapter;

/** Reactive-only configuration, without a servlet or Spring MVC dependency. */
@AutoConfiguration(after = CatnapAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "org.springframework.web.reactive.DispatcherHandler",
      "com.github.gregwhitaker.catnap.spring.webflux.CatnapWebFluxFilter"
    })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class CatnapWebFluxAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  CatnapWebFluxStreams catnapWebFluxStreams(ProjectionService service) {
    return new CatnapWebFluxStreams(service);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "catnap",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  CatnapWebFluxHandlerAdapter catnapWebFluxHandlerAdapter(
      ProjectionService service, RequestMappingHandlerAdapter delegate) {
    return new CatnapWebFluxHandlerAdapter(service, delegate);
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(
      prefix = "catnap",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  CatnapWebFluxFilter catnapWebFluxFilter(ProjectionService service) {
    return new CatnapWebFluxFilter(service.settings());
  }
}
