package com.github.gregwhitaker.catnap.micronaut;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import io.micronaut.core.type.Argument;
import io.micronaut.jackson.core.tree.JsonNodeTreeCodec;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.JsonStreamConfig;
import io.micronaut.json.tree.JsonNode;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/** Native tree conversion plus strict, precise, bounded wire conversion for HTTP adapters. */
public final class MicronautJson {
  private final JsonFactory factory;
  private final JsonNodeTreeCodec codec =
      JsonNodeTreeCodec.getInstance()
          .withConfig(
              JsonStreamConfig.DEFAULT
                  .withUseBigDecimalForFloats(true)
                  .withUseBigIntegerForInts(true));

  public MicronautJson(int maxDepth) {
    if (maxDepth < 0 || maxDepth > 256)
      throw new IllegalArgumentException("JSON depth must be 0..256");
    factory =
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(maxDepth + 1).build())
            .build();
  }

  public <T> JsonNode toTree(JsonMapper mapper, Argument<T> type, T value) throws IOException {
    return mapper.writeValueToTree(type, value);
  }

  public JsonNode parse(byte[] bytes) throws IOException {
    try (var parser = factory.createParser(ObjectReadContext.empty(), bytes)) {
      if (parser.nextToken() == null) throw new IOException("Expected one JSON value");
      JsonNode node = codec.readTree(parser);
      if (parser.nextToken() != null) throw new IOException("Expected exactly one JSON value");
      return node;
    }
  }

  public byte[] write(JsonNode node, Charset charset, int maxBytes) throws IOException {
    BoundedBuffer bytes = new BoundedBuffer(maxBytes);
    try (var generator =
        factory.createGenerator(
            ObjectWriteContext.empty(), new OutputStreamWriter(bytes, charset))) {
      codec.writeTree(generator, node);
    }
    return bytes.toByteArray();
  }

  public byte[] project(byte[] bytes, ProjectionRequest request, Charset charset)
      throws IOException {
    if (bytes.length > request.settings().maxPayloadBytes())
      throw new IOException("Maximum JSON payload size exceeded");
    return write(
        request.evaluate(parse(bytes), MicronautTreeAdapter.INSTANCE),
        charset,
        request.settings().maxPayloadBytes());
  }
}
