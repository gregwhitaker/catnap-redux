package com.github.gregwhitaker.catnap.spring.boot;

import com.github.gregwhitaker.catnap.http.ProjectionService;
import com.github.gregwhitaker.catnap.spring.mvc.CatnapMvcFilter;
import com.github.gregwhitaker.catnap.spring.mvc.CatnapMvcInterceptor;
import com.github.gregwhitaker.catnap.spring.mvc.CatnapMvcStreamFilter;
import com.github.gregwhitaker.catnap.spring.mvc.CatnapMvcStreams;
import com.github.gregwhitaker.catnap.spring.mvc.MvcStreamOptions;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Servlet-only configuration; a reactive-only application does not load these classes. */
@AutoConfiguration(after = CatnapAutoConfiguration.class)
@ConditionalOnClass(
    name = {
      "org.springframework.web.servlet.DispatcherServlet",
      "com.github.gregwhitaker.catnap.spring.mvc.CatnapMvcFilter"
    })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CatnapMvcAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  MvcStreamOptions catnapMvcStreamOptions() {
    return MvcStreamOptions.DEFAULT;
  }

  @Bean(destroyMethod = "close")
  @ConditionalOnMissingBean
  CatnapMvcStreams catnapMvcStreams(ProjectionService service, MvcStreamOptions options) {
    return new CatnapMvcStreams(service, options);
  }

  @Bean
  @ConditionalOnMissingBean(name = "catnapMvcStreamFilter")
  FilterRegistrationBean<CatnapMvcStreamFilter> catnapMvcStreamFilter() {
    var registration = new FilterRegistrationBean<>(new CatnapMvcStreamFilter());
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
    registration.setAsyncSupported(true);
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 101);
    return registration;
  }

  @Bean
  @ConditionalOnMissingBean(CatnapMvcInterceptor.class)
  @ConditionalOnProperty(
      prefix = "catnap",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  CatnapMvcInterceptor catnapMvcInterceptor(ProjectionService service) {
    return new CatnapMvcInterceptor(service);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "catnap",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  WebMvcConfigurer catnapMvcConfigurer(CatnapMvcInterceptor interceptor) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).order(Ordered.HIGHEST_PRECEDENCE);
      }
    };
  }

  @Bean
  @ConditionalOnMissingBean(name = "catnapMvcFilter")
  @ConditionalOnProperty(
      prefix = "catnap",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  FilterRegistrationBean<CatnapMvcFilter> catnapMvcFilter(ProjectionService service) {
    var registration = new FilterRegistrationBean<>(new CatnapMvcFilter(service.settings()));
    registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
    registration.setAsyncSupported(true);
    registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
    return registration;
  }
}
