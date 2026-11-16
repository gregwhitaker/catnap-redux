package com.github.gregwhitaker.catnap.examples.mvc;

import com.github.gregwhitaker.catnap.examples.jackson3.JacksonProbe;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class ProbeApplication {
  public static void main(String[] args) {
    SpringApplication.run(ProbeApplication.class, args);
  }

  @Bean
  JsonMapper jsonMapper() {
    return JacksonProbe.configuredMapper();
  }

  @Bean
  JacksonProbe probe(JsonMapper mapper) {
    return new JacksonProbe(mapper);
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
