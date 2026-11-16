package com.github.gregwhitaker.catnap.examples.webflux;

import com.github.gregwhitaker.catnap.examples.jackson3.JacksonProbe;
import com.github.gregwhitaker.catnap.spring.webflux.CatnapWebFluxStreams;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.ResolvableType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.util.MimeType;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
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
  RouterFunction<ServerResponse> streamingFunction(
      CatnapWebFluxStreams streams, JsonMapper mapper) {
    return RouterFunctions.route(
        RequestPredicates.GET("/catnap/stream/functional"),
        request ->
            ServerResponse.ok()
                .build(
                    (exchange, context) ->
                        streams.writeNdjson(
                            exchange,
                            Flux.just(Map.of("id", 1, "other", 2)),
                            streams.encoder(
                                exchange, (value, output) -> mapper.writeValue(output, value)))));
  }

  @Bean
  JacksonProbe probe(JsonMapper mapper) {
    return new JacksonProbe(mapper);
  }

  @Bean
  WebFluxConfigurer jsonEncoder(JsonMapper mapper) {
    return new WebFluxConfigurer() {
      @Override
      public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer
            .defaultCodecs()
            .jacksonJsonEncoder(
                new JacksonJsonEncoder(mapper) {
                  @Override
                  protected ObjectWriter customizeWriter(
                      ObjectWriter writer,
                      MimeType mimeType,
                      ResolvableType elementType,
                      Map<String, Object> hints) {
                    return super.customizeWriter(writer, mimeType, elementType, hints)
                        .withAttribute("currency", "EUR");
                  }
                });
      }
    };
  }
}
