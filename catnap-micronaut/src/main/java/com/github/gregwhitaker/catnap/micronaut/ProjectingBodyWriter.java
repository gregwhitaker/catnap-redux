package com.github.gregwhitaker.catnap.micronaut;

import com.github.gregwhitaker.catnap.http.BoundedBuffer;
import com.github.gregwhitaker.catnap.http.ProjectionRequest;
import com.github.gregwhitaker.catnap.http.ResponsePolicy;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.MutableHeaders;
import io.micronaut.http.ByteBodyHttpResponse;
import io.micronaut.http.ByteBodyHttpResponseWrapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.body.ByteBodyFactory;
import io.micronaut.http.body.CloseableByteBody;
import io.micronaut.http.body.MessageBodyWriter;
import io.micronaut.http.body.ResponseBodyWriter;
import io.micronaut.http.codec.CodecException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class ProjectingBodyWriter<T> implements ResponseBodyWriter<T> {
  private final MessageBodyWriter<T> delegate;
  private final ProjectionRequest projection;
  private final MicronautJson json;

  ProjectingBodyWriter(
      MessageBodyWriter<T> delegate, ProjectionRequest projection, MicronautJson json) {
    this.delegate = delegate;
    this.projection = projection;
    this.json = json;
  }

  @Override
  public boolean isWriteable(Argument<T> type, MediaType mediaType) {
    return delegate.isWriteable(type, mediaType);
  }

  @Override
  public boolean isBlocking() {
    return delegate.isBlocking();
  }

  @Override
  public void writeTo(
      Argument<T> type, MediaType mediaType, T value, MutableHeaders headers, OutputStream output) {
    try {
      output.write(encode(type, mediaType, value, headers));
    } catch (IOException error) {
      throw new CodecException("JSON response projection failed", error);
    }
  }

  private byte[] encode(Argument<T> type, MediaType mediaType, T value, MutableHeaders headers)
      throws IOException {
    BoundedBuffer captured = new BoundedBuffer(projection.settings().maxPayloadBytes());
    delegate.writeTo(type, mediaType, value, headers, captured);
    return json.project(
        captured.toByteArray(),
        projection,
        MessageBodyWriter.findCharset(mediaType, headers).orElse(StandardCharsets.UTF_8));
  }

  @Override
  public ByteBodyHttpResponse<?> write(
      ByteBodyFactory factory,
      HttpRequest<?> request,
      MutableHttpResponse<T> response,
      Argument<T> type,
      MediaType mediaType,
      T value) {
    try {
      byte[] bytes = encode(type, mediaType, value, response.getHeaders());
      ResponsePolicy.BODY_HEADERS.forEach(response.getHeaders()::remove);
      response.getHeaders().contentTypeIfMissing(mediaType);
      response.getHeaders().set("Content-Length", Integer.toString(bytes.length));
      return ByteBodyHttpResponseWrapper.wrap(response, factory.adapt(bytes));
    } catch (IOException error) {
      throw new CodecException("JSON response projection failed", error);
    }
  }

  @Override
  public CloseableByteBody writePiece(
      ByteBodyFactory factory,
      HttpRequest<?> request,
      HttpResponse<?> response,
      Argument<T> type,
      MediaType mediaType,
      T value) {
    throw new CodecException(
        "Finite JSON writer cannot be used for streaming; use the streaming integration");
  }
}
