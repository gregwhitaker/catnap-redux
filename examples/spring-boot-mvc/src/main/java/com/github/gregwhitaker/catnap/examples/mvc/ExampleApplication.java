package com.github.gregwhitaker.catnap.examples.mvc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class ExampleApplication {
  public static void main(String[] args) {
    SpringApplication.run(ExampleApplication.class, args);
  }

  @Bean
  JsonMapper jsonMapper() {
    return JsonMapper.builder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
        .build();
  }

  @Bean
  JacksonJsonHttpMessageConverter jsonConverter(JsonMapper mapper) {
    return new JacksonJsonHttpMessageConverter(mapper) {
      @Override
      protected ObjectWriter customizeWriter(
          ObjectWriter writer, JavaType type, MediaType mediaType) {
        return writer.withAttribute("currency", "EUR");
      }
    };
  }
}
