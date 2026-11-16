package com.github.gregwhitaker.catnap.benchmarks.mvc;

import com.github.gregwhitaker.catnap.spring.mvc.MvcStreamOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class StreamingConfiguration implements WebMvcConfigurer {
  @Bean
  public MvcStreamOptions streamOptions() {
    return new MvcStreamOptions(4, 64, 16, 0);
  }

  @Bean
  public ThreadPoolTaskExecutor nativeExecutor() {
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(64);
    return executor;
  }

  @Override
  public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
    configurer.setTaskExecutor(nativeExecutor());
    configurer.setDefaultTimeout(0);
  }
}
